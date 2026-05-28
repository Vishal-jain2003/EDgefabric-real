# Runbook: HighCacheErrorRate

## Alert Summary

**Severity**: CRITICAL  
**Component**: Cache  
**SLO Impact**: Availability SLO at risk — error budget burning 14.4x faster than allowed  

## Symptoms

- Cache miss rate > 5% sustained over 2 minutes
- User-facing applications reporting slow response times
- Increased backend database load (cache not serving traffic)

## Diagnosis

### 1. Check current error rate
```bash
curl -s "http://prometheus:9090/api/v1/query?query=sum(rate(edgefabric_cache_misses_total[1m]))/sum(rate(edgefabric_cache_hits_total[1m])+rate(edgefabric_cache_misses_total[1m]))" | jq '.data.result[0].value[1]'
```

### 2. Check which tenants are affected
```bash
curl -s "http://prometheus:9090/api/v1/query?query=topk(10,rate(edgefabric_cache_misses_total[5m])%20by%20(tenant))" | jq -r '.data.result[] | "\(.metric.tenant): \(.value[1])"'
```

### 3. Check cache node health
```bash
aws ec2 describe-instances --filters "Name=tag:Role,Values=hermes-cache-node" "Name=instance-state-name,Values=running" --query 'Reservations[*].Instances[*].[InstanceId,PrivateIpAddress,State.Name]' --output table
```

### 4. Check CloudWatch logs for errors
```bash
aws logs filter-log-events --log-group-name /aws/ec2/edgefabric-cache --filter-pattern "ERROR" --start-time $(date -u -d '10 minutes ago' +%s)000 --limit 50
```

## Remediation

### Scenario A: Node Down

**Symptom**: One or more cache nodes unreachable.

**Fix**:
1. Restart failed node via SSM:
   ```bash
   INSTANCE_ID="<failed-instance-id>"
   aws ssm send-command --instance-ids "$INSTANCE_ID" --document-name "AWS-RunShellScript" --parameters 'commands=["systemctl restart edgefabric-cache"]'
   ```
2. Verify node rejoins cluster:
   ```bash
   curl -s http://<cache-node-ip>:8082/internal/cluster/members | jq '.members[] | select(.status=="ALIVE")'
   ```

### Scenario B: Memory Pressure (Eviction Storm)

**Symptom**: High eviction rate + high miss rate.

**Fix**:
1. Check current memory usage:
   ```bash
   curl -s "http://prometheus:9090/api/v1/query?query=edgefabric_cache_memory_used_bytes" | jq -r '.data.result[] | "\(.metric.instance): \(.value[1])"'
   ```
2. Temporarily scale out (add cache node):
   ```bash
   # Use AWS console or Terraform to add node to ASG
   # Node will auto-register via Cloud Map
   ```
3. Long-term: increase memory limit in application.yml or add more nodes permanently.

### Scenario C: TTL Misconfiguration

**Symptom**: Sustained 30%+ miss rate, but nodes are healthy.

**Fix**:
1. Review recent TTL changes in Jira/Confluence.
2. Identify keys with problematic TTLs:
   ```bash
   curl -s http://<lb-ip>:8080/internal/cache/stats | jq '.topEvictedKeys'
   ```
3. Adjust TTL in client code or via tenant-specific override config.

### Scenario D: Sudden Traffic Spike

**Symptom**: Miss rate spike correlates with request rate spike.

**Fix**:
1. Check if spike is legitimate (new feature launch, marketing campaign).
2. If attack: enable rate limiting:
   ```bash
   kubectl apply -f k8s/rate-limit-configmap.yaml
   kubectl rollout restart deployment/edgefabric-loadbalancer
   ```

## Escalation

- **After 15 minutes**: Page on-call SRE via PagerDuty
- **After 30 minutes**: Engage EdgeFabric platform team lead
- **If data loss risk**: Escalate to CTO

## Post-Incident

1. File incident report in Jira (template: EPMICMPHE-INC-XXX)
2. Update this runbook with any new failure modes discovered
3. Schedule post-mortem within 48 hours

---

**Last Updated**: 2026-05-12  
**Owner**: EdgeFabric SRE Team
