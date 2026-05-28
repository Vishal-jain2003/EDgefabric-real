# Runbook: HighMemoryUsage

## Alert Summary

**Severity**: WARNING  
**Component**: Cache  
**SLO Impact**: Impending eviction storm → increased miss rate → SLO breach imminent  

## Symptoms

- Cache memory usage > 80% of 10GB limit for 5 minutes
- Increased eviction rate (`edgefabric_cache_evictions_total`)
- Potential OutOfMemoryError in logs
- Node may become unresponsive

## Diagnosis

### 1. Check current memory usage per node
```bash
curl -s "http://prometheus:9090/api/v1/query?query=edgefabric_cache_memory_used_bytes%2F10737418240" | jq -r '.data.result[] | "\(.metric.instance): \(.value[1] * 100 | floor)%"'
```

### 2. Check entry count and eviction rate
```bash
curl -s "http://prometheus:9090/api/v1/query?query={edgefabric_cache_entries,rate(edgefabric_cache_evictions_total[5m])}" | jq '.data.result'
```

### 3. Check JVM heap usage
```bash
curl -s http://<cache-node-ip>:8082/actuator/metrics/jvm.memory.used | jq '.measurements[] | select(.statistic=="VALUE") | .value'
```

### 4. Identify top tenants by memory usage
```bash
curl -s http://<cache-node-ip>:8082/internal/cache/stats | jq '.topTenantsByMemory | sort_by(.memoryBytes) | reverse | .[0:10]'
```

## Remediation

### Scenario A: Sudden Traffic Spike (Legitimate)

**Symptom**: Memory spike correlates with request rate spike.

**Fix**:
1. **Short-term**: Scale out immediately:
   ```bash
   aws autoscaling set-desired-capacity --auto-scaling-group-name edgefabric-cache-asg --desired-capacity 5
   ```
2. Wait 3-5 minutes for new nodes to join cluster.
3. Verify load distribution:
   ```bash
   for ip in $(dig cache-nodes.cache-cluster.internal +short); do
     echo "$ip: $(curl -s http://$ip:8082/actuator/metrics/edgefabric.cache.entries | jq '.measurements[0].value')";
   done
   ```

### Scenario B: Memory Leak (Gradual Growth)

**Symptom**: Memory steadily climbing over hours/days, not correlated with traffic.

**Fix**:
1. Capture heap dump for analysis:
   ```bash
   INSTANCE_ID="<cache-instance-id>"
   aws ssm send-command --instance-ids "$INSTANCE_ID" --document-name "AWS-RunShellScript" --parameters 'commands=["jmap -dump:live,format=b,file=/tmp/heapdump.hprof $(pgrep java)"]'
   ```
2. Download heap dump:
   ```bash
   aws ssm start-session --target "$INSTANCE_ID"
   # In session: copy dump to S3 or local
   ```
3. Analyze with VisualVM or Eclipse MAT.
4. **Immediate mitigation**: Restart affected node:
   ```bash
   aws ssm send-command --instance-ids "$INSTANCE_ID" --document-name "AWS-RunShellScript" --parameters 'commands=["systemctl restart edgefabric-cache"]'
   ```

### Scenario C: Large Keys (Single Tenant Abuse)

**Symptom**: One tenant consuming disproportionate memory.

**Fix**:
1. Identify abusive tenant:
   ```bash
   curl -s http://<cache-node-ip>:8082/internal/cache/stats | jq '.topTenantsByMemory[0]'
   ```
2. Check that tenant's entry sizes:
   ```bash
   curl -s http://<cache-node-ip>:8082/internal/cache/stats | jq '.topTenantsByMemory[0] | {tenant, entries, memoryMB: (.memoryBytes / 1048576 | floor), avgEntryKB: ((.memoryBytes / .entries) / 1024 | floor)}'
   ```
3. If avgEntryKB > 1MB: contact tenant to reduce value sizes or split into multiple keys.
4. **Short-term**: Evict that tenant's keys:
   ```bash
   curl -X DELETE "http://<cache-node-ip>:8082/internal/cache/tenant/<tenant-id>"
   ```

### Scenario D: TTL Misconfiguration (Keys Not Expiring)

**Symptom**: Low eviction rate but high memory usage + entry count.

**Fix**:
1. Check TTL distribution:
   ```bash
   curl -s http://<cache-node-ip>:8082/internal/cache/stats | jq '.ttlBuckets'
   ```
2. If many entries with TTL > 24h: review TTL policy with stakeholders.
3. Force eviction of old entries (emergency only):
   ```bash
   curl -X POST "http://<cache-node-ip>:8082/internal/cache/compact?maxAgeHours=24"
   ```

## Escalation

- **Memory > 90%**: Page on-call SRE immediately
- **OOM in logs**: Escalate to platform lead (may indicate JVM bug or config issue)
- **After 20 minutes unresolved**: Engage capacity planning team

## Post-Incident

1. Review memory limits: 10GB may be insufficient for current traffic
2. Implement per-tenant memory quotas if abuse is recurring
3. Add pre-emptive alert: memory > 70% for 10 minutes (earlier warning)
4. Consider: LRU eviction policy tuning or tiered storage (SSD + RAM)

---

**Last Updated**: 2026-05-12  
**Owner**: EdgeFabric SRE Team
