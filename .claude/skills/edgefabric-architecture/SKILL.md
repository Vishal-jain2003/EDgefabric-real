---
name: edgefabric-architecture
description: How EdgeFabric is structured — modules, ports, data flow, deployment topology. Load this whenever the task involves understanding "where does X live", "how do load balancer and cache talk", "what runs on which port", or any architectural reasoning across modules.
---

# Skill: EdgeFabric Architecture

## Modules and ports

| Module | Port(s) | Role |
|--------|---------|------|
| `loadbalancer` | 8080 (HTTP) | Routes client requests to cache nodes via consistent hashing |
| `caching` | 8082 (HTTP), 7946/udp (gossip), 7000/udp (failure detection) | Cache node, holds keys, gossips membership |
| `registry` | (separate EC2) | Service registry, deployed independently |
| `consistent-hashing` | — | Shared library — hash ring + virtual nodes |
| `testing_edgefabric` | — | Integration / E2E tests |
| `ui` | — | Frontend dashboard |

## Request flow

```
Client → LB:8080 → (consistent hash) → CacheNode:8082 → response
                        │
                        └── ring built from Cloud Map srv-6lnd44knosnojplq
```

- LB resolves cache node IPs via Cloud Map DNS (`cache-nodes.cache-cluster.internal`).
- Each cache node self-registers its private IP into Cloud Map at startup.
- LB rebuilds its hash ring whenever Cloud Map membership changes.

## Cluster membership (cache nodes only)

```
node A ──gossip:7946/udp── node B ──gossip:7946/udp── node C
   │                          │                          │
   └─── failure-detector:7000/udp ── all-pairs ──────────┘
```

- Gossip = SWIM-style. Every `GossipProperties.intervalMs`, each node picks
  `PeerSelector` peers and sends a digest of `InMemoryMembershipList`.
- Failure detection = phi-accrual via `FailureDetector` + `SuspectTracker`.
  Suspect → confirmed-dead → `TopologyChangedEvent` published.

## Where to look in the index

Always start here:

1. `.codemie/codebase/OVERVIEW.md` — high-level map
2. `.codemie/codebase/modules/<name>.md` — controllers, services, configs
3. `.codemie/codebase/symbols.json` — class → file:line (O(1))

Or call MCP: `codebase_overview`, `codebase_module(name)`,
`find_symbol(name)`, `list_endpoints(module?)`.

## Deployment topology

- Images on **Docker Hub** (`anubhavpratap/edgefabric-*:v1`) — never ECR.
- Jenkins → AWS SSM RunCommand → EC2 (no SSH).
- EC2 instances discovered by tag `Role=hermes-loadbalancer` /
  `Role=hermes-cache-node`.
- Local dev: `docker-compose.yml` (LB + 3 cache nodes).
- Migration tests: `docker-compose.migration-test.yml`.

## Common architectural rules

- **Controllers never touch repositories.** Always go through a Service.
- **No `.block()`** in reactive chains — use `Mono`/`Flux` end-to-end.
- **No hardcoded IPs/ports** — everything via `@ConfigurationProperties`.
- LB never owns state — it's stateless and ring-derived.
- A new cache node must register in Cloud Map *before* serving traffic.
