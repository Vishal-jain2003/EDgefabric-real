# EPMICMPHE-102: Key Migration on Topology Change — Planning Spec

## Status: AWAITING HUMAN APPROVAL

---

## 1. Executive Summary

When a cache node joins or leaves the cluster, keys that no longer belong to a node must be **actively migrated** to their new owner so cache hit rate remains high. This implements a **push model** where the old owner initiates the transfer.

---

## 2. Current Architecture (As-Is)

| Concern | Where it lives |
|---------|---------------|
| Cluster membership (gossip/SWIM) | `caching` module — `MembershipList`, `GossipSender/Receiver`, `FailureDetector` |
| Consistent hash ring | `loadbalancer` module only — `CacheRouter` wraps `ConsistentHashRing<CacheNode>` |
| Quorum coordination | `loadbalancer` module — `QuorumService` |
| Key storage | `caching` module — `ConcurrentHashMap<String, CacheItem>` |
| Internal PUT API | `caching` module — `PUT /api/v1/internal/cache/{key}` with version-based conflict resolution |

**Key gap:** Cache nodes have **no hash ring** and don't know which keys they "own." The LB decides routing. For push-model migration, cache nodes need their own ring.

---

## 3. User Stories

### Story 1: Topology Change Detection
**As** a cache node,  
**When** the gossip protocol detects a node joining or leaving (ALIVE ↔ DEAD transition),  
**I want** a `TopologyChangedEvent` to be published,  
**So that** the migration subsystem can react.

**Acceptance Criteria:**
- AC1: When `MembershipList.merge()` results in a new node becoming ALIVE or an existing node becoming DEAD, a `TopologyChangedEvent` is published via Spring's `ApplicationEventPublisher`
- AC2: The event contains: `Set<NodeInfo> addedNodes`, `Set<NodeInfo> removedNodes`, `List<NodeInfo> currentAliveNodes`
- AC3: Rapid successive membership changes are **debounced** (e.g., 2-second window) to avoid thrashing during rolling restarts
- AC4: Event is NOT fired during initial bootstrap (first join)

**Story Points:** 3

---

### Story 2: Cache Node Hash Ring
**As** a cache node,  
**When** topology changes,  
**I want** to maintain my own consistent hash ring,  
**So that** I can determine key ownership locally.

**Acceptance Criteria:**
- AC1: The `caching` module depends on the `consistent-hashing` library
- AC2: A local `ConsistentHashRing<NodeInfo>` (or adapter) is maintained, updated on each `TopologyChangedEvent`
- AC3: `NodeInfo` implements `HashableNode` (or an adapter is used), using the same node ID scheme as the LB's `CacheNode`
- AC4: The ring uses the **same configuration** (virtual nodes, hash algorithm) as the loadbalancer to ensure identical routing decisions
- AC5: Ring is initialized during bootstrap from the initial membership list

**Story Points:** 3

---

### Story 3: Key Migration Service
**As** a cache node,  
**When** a `TopologyChangedEvent` is received,  
**I want** to scan my local store and identify keys I no longer own,  
**So that** they can be queued for migration.

**Acceptance Criteria:**
- AC1: `KeyMigrationService` listens for `TopologyChangedEvent`
- AC2: Iterates `store.entrySet()` and checks `ring.getNode(key)` for each key
- AC3: Keys where `newOwner != self` are added to a migration queue
- AC4: Migration scan runs asynchronously (does not block the event thread)
- AC5: If a migration is already in progress when a new topology change arrives, the current migration completes and a new scan is triggered
- AC6: Logs the number of keys identified for migration

**Story Points:** 5

---

### Story 4: Migration Worker (Async, Rate-Limited)
**As** a cache node,  
**When** keys are queued for migration,  
**I want** them to be pushed to the new owner at a controlled rate,  
**So that** migration doesn't overwhelm the cluster.

**Acceptance Criteria:**
- AC1: `MigrationWorker` processes the migration queue asynchronously
- AC2: Rate-limited to 500 keys/sec by default (configurable via `migration.rate-limit`)
- AC3: For each key: calls `PUT /api/v1/internal/cache/{key}` on the new owner with the correct headers (`X-Quorum-Version`, `X-Expires-At`, `Content-Type`)
- AC4: On **success**: delete the key from local store (and eviction services)
- AC5: On **failure**: add to retry queue with exponential backoff (base 1s, max 30s, max 3 retries)
- AC6: After max retries exhausted, log an error and skip the key (don't block other migrations)
- AC7: Migration is cancellable — if topology changes again mid-migration, remaining queue is discarded and a fresh scan is triggered

**Story Points:** 8

---

### Story 5: Migration Metrics & Observability
**As** an operator,  
**When** key migration is happening,  
**I want** to see metrics and logs,  
**So that** I can monitor migration progress and health.

**Acceptance Criteria:**
- AC1: Counter: `cache_migration_keys_total` (labels: `result=success|failed|skipped`)
- AC2: Gauge: `cache_migration_queue_size` — current pending keys
- AC3: Timer: `cache_migration_duration_seconds` — end-to-end migration time per topology change
- AC4: Counter: `cache_migration_retries_total`
- AC5: INFO log when migration starts/completes with key counts
- AC6: WARN log on individual key migration failure
- AC7: Metrics use the existing Micrometer/Prometheus setup

**Story Points:** 3

---

### Story 6: Migration Configuration Properties
**As** an operator,  
**When** deploying the cache cluster,  
**I want** to configure migration behavior,  
**So that** I can tune it for my workload.

**Acceptance Criteria:**
- AC1: `migration.enabled` (default: `true`) — feature flag to disable migration
- AC2: `migration.rate-limit` (default: `500`) — keys per second
- AC3: `migration.debounce-ms` (default: `2000`) — topology change debounce window
- AC4: `migration.max-retries` (default: `3`) — max retries per key
- AC5: `migration.backoff-base-ms` (default: `1000`) — exponential backoff base
- AC6: `migration.backoff-max-ms` (default: `30000`) — max backoff delay
- AC7: Properties are bound via `@ConfigurationProperties(prefix = "migration")`

**Story Points:** 2

---

## 4. Total Estimation

| Story | Points |
|-------|--------|
| 1. Topology Change Detection | 3 |
| 2. Cache Node Hash Ring | 3 |
| 3. Key Migration Service | 5 |
| 4. Migration Worker | 8 |
| 5. Metrics & Observability | 3 |
| 6. Configuration Properties | 2 |
| **Total** | **24** |

---

## 5. Key Architecture Decisions

### Decision 1: Cache nodes get their own hash ring
**Why:** The push model requires each node to determine `ring.getNode(key)` locally. The `consistent-hashing` module is already a standalone library — just add the dependency to `caching/pom.xml`.

**Risk:** Ring configuration (virtual nodes, hash algorithm) MUST match the loadbalancer's configuration, otherwise the cache node's ownership calculation will diverge from the LB's routing. Mitigation: share configuration or use the same defaults.

### Decision 2: Topology change source = gossip merge, not LB notification
**Why:** Cache nodes already detect membership changes via the gossip protocol (SWIM). Using the gossip layer as the trigger avoids adding a new LB→node communication channel. The `MembershipList.merge()` method already knows when a node transitions ALIVE↔DEAD.

### Decision 3: Debounce topology changes
**Why:** During a rolling restart, multiple nodes go DEAD/ALIVE in quick succession. Without debouncing, each change triggers a full store scan + migration. A 2-second debounce window collapses these into a single migration pass.

### Decision 4: Push model (old owner initiates)
**Why:** Already decided in the ticket. The old owner has the data and can iterate its local store. No "list keys" API is needed. The internal PUT endpoint already supports version-based conflict resolution, so stale migration writes are safely rejected.

### Decision 5: Delete-after-success only
**Why:** If the push fails, the key stays on the old owner. Clients may still hit the old owner (via stale LB routing or during ring convergence). Keeping the key until successful transfer ensures no data loss.

---

## 6. Proposed Component Diagram

```
                    MembershipList.merge()
                           |
                           | (detects ALIVE/DEAD transition)
                           v
                  TopologyChangedEvent
                    (debounced 2s)
                           |
                           v
                  KeyMigrationService
                    @EventListener
                           |
                    iterates store
                    checks ring.getNode(key)
                           |
                    queues keys where
                    newOwner != self
                           |
                           v
                    MigrationWorker
                    (async, rate-limited)
                           |
                    PUT /api/v1/internal/cache/{key}
                    → new owner node
                           |
                    success → delete local
                    failure → retry with backoff
```

---

## 7. New Files to Create

| File | Module | Purpose |
|------|--------|---------|
| `TopologyChangedEvent.java` | caching | Spring ApplicationEvent with added/removed/current nodes |
| `KeyMigrationService.java` | caching | Event listener, store scanner, queues keys for migration |
| `MigrationWorker.java` | caching | Async worker, rate-limited, pushes keys to new owners |
| `MigrationProperties.java` | caching | `@ConfigurationProperties(prefix = "migration")` |
| `MigrationConfig.java` | caching | Bean wiring: hash ring, WebClient, thread pool |
| `NodeInfoHashAdapter.java` | caching | Adapts `NodeInfo` to implement `HashableNode` interface |

---

## 8. Files to Modify

| File | Change |
|------|--------|
| `caching/pom.xml` | Add dependency on `consistent-hashing` module |
| `InMemoryMembershipList.java` | Inject `ApplicationEventPublisher`, publish `TopologyChangedEvent` on ALIVE/DEAD transitions in `merge()` |
| `InternalCacheService.java` | Add method to delete a key cleanly (remove from store + eviction services) — may already exist |
| `application.yml` (caching) | Add `migration.*` and `ring.*` defaults |

---

## 9. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Hash ring divergence between LB and cache node | Keys migrated to wrong node | Use identical ring config; add integration test |
| Migration storm on large topology change | Network saturation | Rate limiting (500 keys/sec default) + debounce |
| Data loss if key deleted before transfer completes | Cache miss | Delete-after-success only; version-based conflict resolution prevents stale overwrites |
| Concurrent migration + client write to same key | Version conflict | Internal PUT uses last-writer-wins with version; higher version always wins |
| Node dies mid-migration | Partial migration | Surviving nodes will pick up remaining keys on next topology change |

---

## 10. Assumptions

1. The `consistent-hashing` module's `ConsistentHashRing` uses deterministic hashing — same input (nodeId + virtualNodes + algorithm) produces the same ring on every node
2. The LB and cache nodes will use the same hash ring configuration (this should be enforced via shared config or documented)
3. The internal PUT endpoint is reachable between cache nodes (not just from LB to cache node)
4. Migration of a single key is idempotent (safe to retry)
5. The gossip protocol converges within seconds — all nodes see the same membership view shortly after a change
