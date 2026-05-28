# Runbook: GossipPartitionDetected

## Alert Summary

**Severity**: CRITICAL  
**Component**: Gossip  
**SLO Impact**: Split-brain risk → data inconsistency → availability SLO breach  

## Symptoms

- `edgefabric_gossip_reachable_nodes < 2` for 2 minutes
- Cache nodes cannot communicate via gossip (port 7946/udp)
- Membership list shows only self as ALIVE
- Failure detector logs show "all peers unreachable"

## Diagnosis

### 1. Check reachable node count per cache node
```bash
for ip in $(dig cache-nodes.cache-cluster.internal +short); do
  echo "$ip: $(curl -s http://$ip:8082/internal/cluster/members | jq '[.members[] | select(.status=="ALIVE")] | length')";
done
```

### 2. Test gossip connectivity (UDP port 7946)
```bash
CACHE_INSTANCE_IDS=$(aws ec2 describe-instances --filters "Name=tag:Role,Values=hermes-cache-node" "Name=instance-state-name,Values=running" --query 'Reservations[*].Instances[*].InstanceId' --output text)
for iid in $CACHE_INSTANCE_IDS; do
  echo "Testing from $iid:";
  aws ssm send-command --instance-ids "$iid" --document-name "AWS-RunShellScript" --parameters 'commands=["nc -vzu <other-node-ip> 7946"]';
done
```

### 3. Check security group rules for gossip port
```bash
aws ec2 describe-security-groups --group-ids sg-<cache-sg-id> --query 'SecurityGroups[0].IpPermissions[?FromPort==`7946`]'
```

## Remediation

### Scenario A: Security Group Missing UDP 7946 Rule

**Fix**:
```bash
aws ec2 authorize-security-group-ingress \
  --group-id sg-<cache-sg-id> \
  --protocol udp \
  --port 7946 \
  --source-group sg-<cache-sg-id>
```

### Scenario B: Network Partition (Subnet Isolation)

**Fix**:
1. Check VPC route tables and NACLs
2. If AWS networking issue: engage AWS support immediately
3. **Mitigation**: Restart all cache nodes sequentially to force rejoin:
   ```bash
   for iid in $CACHE_INSTANCE_IDS; do
     aws ssm send-command --instance-ids "$iid" --document-name "AWS-RunShellScript" --parameters 'commands=["systemctl restart edgefabric-cache"]';
     sleep 60;  # Wait for rejoin before restarting next
   done
   ```

### Scenario C: All Nodes in SUSPECT State (False Positive Storm)

**Fix**:
- Restart one seed node to break the deadlock:
  ```bash
  aws ssm send-command --instance-ids "<first-cache-instance>" --document-name "AWS-RunShellScript" --parameters 'commands=["systemctl restart edgefabric-cache"]'
  ```

## Escalation

- **Immediate**: Page on-call SRE + platform lead
- **If unresolved in 5 minutes**: Escalate to CTO (data consistency risk)

---

**Last Updated**: 2026-05-12  
**Owner**: EdgeFabric SRE Team
