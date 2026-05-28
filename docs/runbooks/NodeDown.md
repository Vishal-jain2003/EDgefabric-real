# Runbook: CacheNodeDown

## Alert Summary

**Severity**: CRITICAL  
**Component**: Cache  
**SLO Impact**: Availability SLO breach imminent — reduced replica count threatens quorum writes  

## Symptoms

- Prometheus scrape target `up{job="edgefabric-cache"}` == 0 for specific instance
- Load balancer logs show connection refused to cache node
- Cloud Map still lists node as registered (stale registration)

## Diagnosis

### 1. Identify which node is down
```bash
curl -s "http://prometheus:9090/api/v1/query?query=up{job=%22edgefabric-cache%22}==0" | jq -r '.data.result[] | .metric.instance'
```

### 2. Check EC2 instance state
```bash
NODE_IP="<ip-from-step-1>"
aws ec2 describe-instances --filters "Name=private-ip-address,Values=$NODE_IP" --query 'Reservations[*].Instances[*].[InstanceId,State.Name,LaunchTime]' --output table
```

### 3. Check application logs
```bash
INSTANCE_ID="<from-step-2>"
aws ssm send-command --instance-ids "$INSTANCE_ID" --document-name "AWS-RunShellScript" --parameters 'commands=["journalctl -u edgefabric-cache -n 100"]' --output text
```

### 4. Check Java process status
```bash
aws ssm send-command --instance-ids "$INSTANCE_ID" --document-name "AWS-RunShellScript" --parameters 'commands=["ps aux | grep java"]' --output text
```

## Remediation

### Scenario A: Java Process Crashed (OOM or Uncaught Exception)

**Symptom**: EC2 instance running, but Java process exited.

**Fix**:
1. Restart application via SSM:
   ```bash
   aws ssm send-command --instance-ids "$INSTANCE_ID" --document-name "AWS-RunShellScript" --parameters 'commands=["systemctl restart edgefabric-cache"]'
   ```
2. Verify node rejoins gossip cluster:
   ```bash
   curl -s http://<any-cache-node>:8082/internal/cluster/members | jq '.members[] | select(.nodeId=="<restarted-node-id>")'
   ```
3. Check for OOM in logs:
   ```bash
   aws ssm send-command --instance-ids "$INSTANCE_ID" --document-name "AWS-RunShellScript" --parameters 'commands=["dmesg | grep -i oom"]'
   ```
   If OOM found → increase heap size in application.yml or instance type.

### Scenario B: EC2 Instance Stopped/Terminated

**Symptom**: Instance state is "stopped" or "terminated".

**Fix**:
1. If stopped: start instance:
   ```bash
   aws ec2 start-instances --instance-ids "$INSTANCE_ID"
   ```
2. If terminated: Auto Scaling Group will replace it automatically. Verify:
   ```bash
   aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names edgefabric-cache-asg --query 'AutoScalingGroups[0].Instances'
   ```
3. Wait 3-5 minutes for new node to register in Cloud Map and join gossip.

### Scenario C: Network Partition (Node Running, Not Reachable)

**Symptom**: EC2 instance running, Java process alive, but unreachable from LB.

**Fix**:
1. Check security group rules:
   ```bash
   aws ec2 describe-security-groups --group-ids sg-<cache-node-sg-id> --query 'SecurityGroups[0].IpPermissions'
   ```
   Verify port 8082 ingress from LB security group.

2. Check VPC route tables:
   ```bash
   aws ec2 describe-route-tables --filters "Name=vpc-id,Values=<vpc-id>" --query 'RouteTables[*].Routes'
   ```

3. Test connectivity from LB:
   ```bash
   LB_INSTANCE_ID="<lb-instance-id>"
   aws ssm send-command --instance-ids "$LB_INSTANCE_ID" --document-name "AWS-RunShellScript" --parameters "commands=[\"curl -v http://$NODE_IP:8082/actuator/health\"]"
   ```

### Scenario D: Disk Full (Application Cannot Write Logs)

**Symptom**: Application frozen due to log write failures.

**Fix**:
1. Check disk usage:
   ```bash
   aws ssm send-command --instance-ids "$INSTANCE_ID" --document-name "AWS-RunShellScript" --parameters 'commands=["df -h"]'
   ```
2. Clean old logs:
   ```bash
   aws ssm send-command --instance-ids "$INSTANCE_ID" --document-name "AWS-RunShellScript" --parameters 'commands=["find /var/log/edgefabric -name \"*.log\" -mtime +7 -delete"]'
   ```
3. Restart application after cleanup.

## Escalation

- **After 5 minutes**: Auto-escalate to on-call SRE (PagerDuty high-urgency)
- **After 15 minutes**: Engage AWS TAM if infrastructure issue suspected
- **If quorum writes failing**: Escalate to EdgeFabric platform lead immediately

## Post-Incident

1. If root cause is code bug: file Jira ticket with stack trace
2. If infrastructure failure: request AWS incident report
3. Update monitoring: add pre-failure indicators (e.g., memory trend alert)

---

**Last Updated**: 2026-05-12  
**Owner**: EdgeFabric SRE Team
