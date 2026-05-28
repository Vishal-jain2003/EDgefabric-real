---
name: gossip-debugging
description: How to diagnose EdgeFabric cluster membership issues — split brain, false positives, gossip silence, quorum loss, port 7946/7000 problems. Load this whenever the task mentions gossip, cluster, membership, suspect, dead node, split brain, or failure detection.
---

# Skill: Gossip & Failure-Detection Debugging

## The components

| Class | Role |
|-------|------|
| `GossipSender` / `GossipReceiver` | UDP 7946 — exchange membership digests |
| `PeerSelector` | Picks fan-out peers each tick |
| `InMemoryMembershipList` | Authoritative local view of the cluster |
| `FailureDetector` | Phi-accrual heartbeat tracking on UDP 7000 |
| `SuspectTracker` | Suspect → dead state machine |
| `TopologyChangedEvent` | Emitted on join / leave / dead — triggers ring rebuild |

## Properties to inspect

```yaml
gossip:
  port: 7946
  intervalMs: 1000        # GossipProperties.intervalMs
  fanout: 3
failure-detector:
  port: 7000
  phiThreshold: 8.0       # FailureDetectorProperties.phiThreshold
  suspectTimeoutMs: 5000
```

Bound by `GossipProperties` and `FailureDetectorProperties` — never hardcoded.

## Diagnostic endpoints

| URL (port 8082) | Returns |
|-----------------|---------|
| `/internal/cluster/members` | Full membership list with state, incarnation, lastSeen |
| `/api/v1/cluster/health` | Quorum + suspect counts |
| `/api/v1/cluster/digest` | Current gossip digest this node would send |

Use the **aws** MCP server's `aws-check_service_health` against each cache
node to compare views.

## Common failure modes

### 1. Split brain (two halves disagree)
- **Symptom:** node A sees {A,B}, node C sees {C,D}, ring routes mismatch.
- **Check:** Cloud Map registration (`get_cloudmap_instances`) — is one half
  unregistered? Security-group blocking 7946/udp between subnets?
- **Fix:** ensure SG allows UDP 7946 + 7000 within the cluster's VPC.

### 2. False positives (node marked dead but healthy)
- **Symptom:** `SuspectTracker` flapping; `phi` exceeds threshold during GC pauses.
- **Check:** JVM GC logs (CloudWatch), increase `phiThreshold` to 10.0–12.0.
- **Don't:** lower `intervalMs` — that increases UDP storm risk.

### 3. Gossip silence (no convergence)
- **Symptom:** new node never appears in others' digests.
- **Check:** is the new node bound to 0.0.0.0:7946 or only loopback? Check
  `GossipConfig` and the SSM bootstrap script.

### 4. Quorum loss after rolling restart
- **Symptom:** all nodes briefly mark each other dead.
- **Fix:** SSM deploy script must restart **one node at a time** with
  `--health-check-grace=intervalMs * fanout * 3` between steps.

## Log search patterns (CloudWatch)

```
fields @timestamp, @message
| filter @logStream like /caching/
| filter @message like /SUSPECT|DEAD|JOIN|LEAVE|TopologyChangedEvent/
| sort @timestamp desc
| limit 200
```

Use `aws-get_cloudwatch_logs` MCP with `log_group=/aws/ec2/hermes-cache-node`.

## When to escalate

If after the four checks above the cluster still won't converge, hand off
to the `aws-ssm-deployer` agent with `mode=gossip-investigation` — it can
run `tcpdump -i any port 7946 -c 100` via SSM on each node and aggregate.
