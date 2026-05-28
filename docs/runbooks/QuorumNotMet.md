# Runbook: QuorumWritesNotMet

## Alert Summary

**Severity**: CRITICAL  
**Component**: Quorum  
**SLO Impact**: Data durability at risk — writes may not be replicated to W=2 nodes  

## Symptoms

- Alert `edgefabric_quorum_writes_total{result="quorum_not_met"}` firing
- API write requests returning 503 Service Unavailable
- Load balancer logs show "Quorum WRITE failed"
- Cache nodes may be unreachable or overloaded

## Diagnosis

### 1. Check current quorum write failure rate
```bash
curl -s "http://prometheus:9090/api/v1/query?query=rate(edgefabric_quorum_writes_total{result=%22quorum_not_met%22}[1m])" | jq '.data.result[0].value[1]'
```

### 2. Check cluster size and reachability
```bash
curl -s http://<cache-node-ip>:8082/internal/cluster/members | jq '.members[] | select(.status=="ALIVE") | {nodeId, host, status}'
```

Expected: 3 ALIVE nodes (N=3, W=2, R=2)

### 3. Check load balancer hash ring
```bash
curl -s http://<lb-ip>:8080/internal/cluster/ring | jq '.nodeCount'
```

### 4. Check network connectivity from LB to cache nodes
```bash
LB_INSTANCE_ID="<lb-instance-id>"
aws ssm send-command --instance-ids "$LB_INSTANCE_ID" --document-name "AWS-RunShellScript" --parameters 'commands=["for ip in 10.0.1.1 10.0.1.2 10.0.1.3; do echo \"Testing $ip\"; curl -s http://$ip:8082/actuator/health; done"]'
```

## Remediation

### Scenario A: Only 1 Node ALIVE (N < W)

**Symptom**: Cluster size < W=2 → quorum mathematically impossible.

**Fix**:
1. Check Auto Scaling Group:
   ```bash
   aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names edgefabric-cache-asg
   ```
2. If ASG desired < 3: update desired capacity:
   ```bash
   aws autoscaling set-desired-capacity --auto-scaling-group-name edgefabric-cache-asg --desired-capacity 3
   ```
3. Wait 3-5 minutes for new nodes to launch and register in Cloud Map.
4. Verify cluster health:
   ```bash
   curl -s http://<any-cache-node>:8082/internal/cluster/members
   ```

### Scenario B: Nodes ALIVE but Network Partition

**Symptom**: Cache nodes show each other as ALIVE, but LB cannot reach them.

**Fix**:
1. Check security groups:
   ```bash
   aws ec2 describe-security-groups --group-ids sg-<lb-sg-id> sg-<cache-sg-id> --query 'SecurityGroups[*].{GroupId:GroupId,Ingress:IpPermissions}'
   ```
   Verify:
   - LB SG allows outbound to cache SG on port 8082
   - Cache SG allows inbound from LB SG on port 8082

2. Check NACLs:
   ```bash
   aws ec2 describe-network-acls --filters "Name=vpc-id,Values=<vpc-id>" --query 'NetworkAcls[*].Entries'
   ```

3. Test connectivity from LB (SSM):
   ```bash
   aws ssm send-command --instance-ids "$LB_INSTANCE_ID" --document-name "AWS-RunShellScript" --parameters "commands=[\"telnet <cache-node-ip> 8082\"]"
   ```

### Scenario C: Nodes Overloaded (High Latency)

**Symptom**: Nodes reachable but quorum write timeout (5s default).

**Fix**:
1. Check node CPU/memory:
   ```bash
   curl -s "http://prometheus:9090/api/v1/query?query=process_cpu_usage{job=%22edgefabric-cache%22}" | jq '.data.result'
   ```
2. Check JVM heap:
   ```bash
   curl -s "http://prometheus:9090/api/v1/query?query=jvm_memory_used_bytes{job=%22edgefabric-cache%22,area=%22heap%22}" | jq '.data.result'
   ```
3. If overloaded: scale out immediately:
   ```bash
   aws autoscaling set-desired-capacity --auto-scaling-group-name edgefabric-cache-asg --desired-capacity 5
   ```
4. Long-term: increase instance type or optimize write path.

### Scenario D: Transient DNS Resolution Failure

**Symptom**: LB cannot resolve `cache-nodes.cache-cluster.internal`.

**Fix**:
1. Check Cloud Map service instances:
   ```bash
   aws servicediscovery list-instances --service-id srv-6lnd44knosnojplq
   ```
2. If empty or stale: force cache nodes to re-register:
   ```bash
   CACHE_INSTANCE_IDS=$(aws ec2 describe-instances --filters "Name=tag:Role,Values=hermes-cache-node" "Name=instance-state-name,Values=running" --query 'Reservations[*].Instances[*].InstanceId' --output text)
   aws ssm send-command --instance-ids $CACHE_INSTANCE_IDS --document-name "AWS-RunShellScript" --parameters 'commands=["systemctl restart edgefabric-cache"]'
   ```

## Escalation

- **Immediate**: Page on-call SRE (PagerDuty critical)
- **After 5 minutes**: Engage EdgeFabric platform lead
- **If cluster < W for > 10 minutes**: Escalate to CTO (data durability impact)

## Post-Incident

1. Calculate data loss risk: # failed writes × avg value size
2. Review WAL replay logs to confirm anti-entropy repaired missed writes
3. If infrastructure failure: request AWS RCA
4. Update monitoring: add pre-emptive alerts for N=2 (one node failure away from quorum loss)

---

**Last Updated**: 2026-05-12  
**Owner**: EdgeFabric SRE Team
