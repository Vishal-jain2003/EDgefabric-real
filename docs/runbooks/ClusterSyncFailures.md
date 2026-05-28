# Runbook: ClusterSyncFailures

## Alert Summary

**Severity**: WARNING  
**Component**: Cluster  
**SLO Impact**: Hash ring may be stale → misrouted requests → increased cache misses  

## Symptoms

- Alert `edgefabric_cluster_sync_errors_total` rate > 0.1/s for 3 minutes
- Load balancer logs show "Cluster sync failed"
- Hash ring size not matching actual cluster size
- Requests being routed to dead nodes

## Diagnosis

### 1. Check sync error rate
```bash
curl -s "http://prometheus:9090/api/v1/query?query=rate(edgefabric_cluster_sync_errors_total[5m])" | jq '.data.result[0].value[1]'
```

### 2. Check hash ring vs actual cluster
```bash
# Hash ring size (LB view)
curl -s http://<lb-ip>:8080/internal/cluster/ring | jq '.nodeCount'

# Actual cluster size (gossip view)
curl -s http://<cache-node-ip>:8082/internal/cluster/members | jq '.members | length'
```

### 3. Check DNS resolution
```bash
dig cache-nodes.cache-cluster.internal +short
```

### 4. Check Cloud Map registration
```bash
aws servicediscovery list-instances --service-id srv-6lnd44knosnojplq --query 'Instances[*].{Id:Id,IPv4:Attributes.AWS_INSTANCE_IPV4}' --output table
```

## Remediation

### Scenario A: DNS Resolution Failure

**Symptom**: dig returns no results or stale IPs.

**Fix**:
1. Check Cloud Map service health:
   ```bash
   aws servicediscovery get-service --id srv-6lnd44knosnojplq
   ```
2. If service healthy but no instances: cache nodes failed to register. Restart them:
   ```bash
   CACHE_INSTANCE_IDS=$(aws ec2 describe-instances --filters "Name=tag:Role,Values=hermes-cache-node" "Name=instance-state-name,Values=running" --query 'Reservations[*].Instances[*].InstanceId' --output text)
   aws ssm send-command --instance-ids $CACHE_INSTANCE_IDS --document-name "AWS-RunShellScript" --parameters 'commands=["systemctl restart edgefabric-cache"]'
   ```

### Scenario B: Peer Sync Timeout

**Symptom**: LB can't reach cache nodes for membership API.

**Fix**:
1. Test LB → cache connectivity:
   ```bash
   LB_INSTANCE_ID="<lb-instance-id>"
   aws ssm send-command --instance-ids "$LB_INSTANCE_ID" --document-name "AWS-RunShellScript" --parameters "commands=[\"curl -v http://<cache-ip>:8082/internal/cluster/members\"]"
   ```
2. Check firewall rules:
   - Cache SG must allow inbound 8082 from LB SG
   - LB SG must allow outbound 8082 to cache SG

### Scenario C: Cache Nodes Returning Empty Membership List

**Symptom**: GET /internal/cluster/members returns `{"members": []}`.

**Fix**:
1. Check gossip protocol health on cache node:
   ```bash
   curl -s http://<cache-ip>:8082/internal/cluster/members | jq '.members[] | {nodeId, status, reachable}'
   ```
2. If all nodes DEAD or SUSPECT: gossip partition detected. Restart one node to trigger rejoin:
   ```bash
   aws ssm send-command --instance-ids "<one-cache-instance-id>" --document-name "AWS-RunShellScript" --parameters 'commands=["systemctl restart edgefabric-cache"]'
   ```
3. Wait 30s for gossip to converge, then verify:
   ```bash
   curl -s http://<cache-ip>:8082/internal/cluster/members
   ```

### Scenario D: Race Condition (New Node Not Yet Registered)

**Symptom**: Node just launched, not yet in Cloud Map or gossip.

**Fix**:
- Wait 2-3 minutes for:
  1. Node to start (30s)
  2. Health check to pass (30s)
  3. Cloud Map registration (30s)
  4. Gossip convergence (60s)
- If still not registered after 5 minutes → restart node.

## Escalation

- **After 10 minutes**: Page on-call SRE
- **If hash ring drift causes data inconsistency**: Escalate to platform lead

## Post-Incident

1. Review Cloud Map service configuration (TTL, health check settings)
2. Add alerting for: `abs(ring_size - gossip_size) > 0` sustained > 2min
3. Consider: increase sync interval if transient network flaps are common

---

**Last Updated**: 2026-05-12  
**Owner**: EdgeFabric SRE Team
