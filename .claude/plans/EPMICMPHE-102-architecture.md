# EPMICMPHE-102: Key Migration on Topology Change — Architecture Spec

## Status: AWAITING HUMAN APPROVAL

---

## 1. System Design Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                         CACHE NODE                              │
│                                                                 │
│  ┌──────────────────┐     ┌──────────────────────────┐          │
│  │ InMemoryMembership│     │ MigrationConfig          │          │
│  │ List              │     │  - ConsistentHashRing    │          │
│  │                   │     │  - WebClient             │          │
│  │ merge() detects   │     │  - ScheduledExecutor     │          │
│  │ ALIVE/DEAD change │     └──────────────────────────┘          │
│  └────────┬──────────┘                                          │
│           │ publishes                                            │
│           v                                                     │
│  ┌──────────────────────┐                                       │
│  │ TopologyChangedEvent │                                       │
│  │  - addedNodes        │  (debounced via ScheduledExecutor)    │
│  │  - removedNodes      │                                       │
│  │  - allAliveNodes     │                                       │
│  └────────┬─────────────┘                                       │
│           │ @EventListener                                      │
│           v                                                     │
│  ┌──────────────────────┐                                       │
│  │ KeyMigrationService  │                                       │
│  │                      │                                       │
│  │ 1. Update local ring │                                       │
│  │ 2. Scan store keys   │                                       │
│  │ 3. Queue misplaced   │                                       │
│  │    keys              │                                       │
│  └────────┬─────────────┘                                       │
│           │ submits to                                           │
│           v                                                     │
│  ┌──────────────────────┐     ┌───────────────────────┐         │
│  │ MigrationWorker      │────>│ PUT /api/v1/internal/ │         │
│  │  - Rate limiter      │     │ cache/{key}           │         │
│  │  - Retry w/ backoff  │     │ → target cache node   │         │
│  │  - Metrics emission  │     └───────────────────────┘         │
│  │  - Delete on success │                                       │
│  └──────────────────────┘                                       │
│                                                                 │
│  ┌──────────────────────┐                                       │
│  │ MigrationProperties  │  (prefix: "migration")                │
│  │  - enabled           │                                       │
│  │  - rateLimit         │                                       │
│  │  - debounceMs        │                                       │
│  │  - maxRetries        │                                       │
│  │  - backoffBaseMs     │                                       │
│  │  - backoffMaxMs      │                                       │
│  └──────────────────────┘                                       │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. Detailed Class Design

### 2.1 TopologyChangedEvent

**Package:** `com.edgefabric.caching.event`  
**File:** `caching/src/main/java/com/edgefabric/caching/event/TopologyChangedEvent.java`

```java
public class TopologyChangedEvent extends ApplicationEvent {
    private final Set<NodeInfo> addedNodes;
    private final Set<NodeInfo> removedNodes;
    private final List<NodeInfo> allAliveNodes;
    
    // Constructor: source = InMemoryMembershipList instance
    // Getters for all fields
}
```

**Design notes:**
- Extends `ApplicationEvent` (standard Spring eventing)
- Immutable — all fields are unmodifiable copies
- `allAliveNodes` is the full alive membership at event time (used to rebuild the ring)

---

### 2.2 NodeInfoHashAdapter

**Package:** `com.edgefabric.caching.migration`  
**File:** `caching/src/main/java/com/edgefabric/caching/migration/NodeInfoHashAdapter.java`

```java
public class NodeInfoHashAdapter implements HashableNode {
    private final NodeInfo nodeInfo;
    
    @Override
    public String getNodeId() {
        return nodeInfo.getCacheNodeId();  // matches LB's CacheNode.getNodeId()
    }
    
    // Delegate getHost(), getServicePort() for building HTTP URLs
}
```

**Critical alignment:** The LB's `CacheNode` is constructed via `toCacheNode(member)` which sets `nodeId = member.getNodeId()` = `NodeInfo.getCacheNodeId()`. Our adapter MUST return the same value from `getNodeId()` to produce identical hash ring placement.

---

### 2.3 MigrationProperties

**Package:** `com.edgefabric.caching.migration`  
**File:** `caching/src/main/java/com/edgefabric/caching/migration/MigrationProperties.java`

```java
@ConfigurationProperties(prefix = "migration")
public class MigrationProperties {
    private boolean enabled = true;
    private int rateLimit = 500;           // keys per second
    private long debounceMs = 2000;        // topology change debounce window
    private int maxRetries = 3;            // per-key retry limit
    private long backoffBaseMs = 1000;     // exponential backoff base
    private long backoffMaxMs = 30000;     // max backoff delay
}
```

---

### 2.4 MigrationConfig

**Package:** `com.edgefabric.caching.migration`  
**File:** `caching/src/main/java/com/edgefabric/caching/migration/MigrationConfig.java`

```java
@Configuration
@EnableConfigurationProperties(MigrationProperties.class)
@ConditionalOnProperty(name = "migration.enabled", havingValue = "true", matchIfMissing = true)
public class MigrationConfig {

    @Bean
    @ConfigurationProperties(prefix = "ring")
    public HashRingProperties migrationHashRingProperties() {
        return new HashRingProperties();   // same defaults as LB: 150 vnodes, xxhash
    }

    @Bean
    public HashProvider migrationHashProvider(HashRingProperties migrationHashRingProperties) {
        return HashProviderFactory.create(migrationHashRingProperties.getHashAlgorithm());
    }

    @Bean
    public ConsistentHashRing<NodeInfoHashAdapter> migrationHashRing(
            HashProvider migrationHashProvider,
            HashRingProperties migrationHashRingProperties) {
        return new ConsistentHashRing<>(migrationHashProvider,
                migrationHashRingProperties.getVirtualNodes());
    }

    @Bean
    public WebClient migrationWebClient() {
        return WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }
}
```

**Ring consistency guarantee:** Uses `@ConfigurationProperties(prefix = "ring")` — same prefix as the LB (`LoadBalancerConfig`). In production, both modules read from their own `application.properties`, but the defaults are identical (150 virtual nodes, xxhash). For extra safety, these values should be set explicitly in the caching module's config.

---

### 2.5 KeyMigrationService

**Package:** `com.edgefabric.caching.migration`  
**File:** `caching/src/main/java/com/edgefabric/caching/migration/KeyMigrationService.java`

```java
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "migration.enabled", havingValue = "true", matchIfMissing = true)
public class KeyMigrationService {

    private final Map<String, CacheItem> store;
    private final ConsistentHashRing<NodeInfoHashAdapter> migrationHashRing;
    private final MigrationWorker migrationWorker;
    private final MembershipList membershipList;
    private final MigrationProperties properties;
    
    private final ScheduledExecutorService debounceExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "migration-debounce");
                t.setDaemon(true);
                return t;
            });
    
    private volatile ScheduledFuture<?> pendingMigration;
    
    @EventListener
    public void onTopologyChanged(TopologyChangedEvent event) {
        // Cancel any pending debounced migration
        ScheduledFuture<?> pending = this.pendingMigration;
        if (pending != null) {
            pending.cancel(false);
        }
        
        // Schedule debounced migration
        this.pendingMigration = debounceExecutor.schedule(
                () -> executeMigration(event),
                properties.getDebounceMs(),
                TimeUnit.MILLISECONDS
        );
    }
    
    private void executeMigration(TopologyChangedEvent event) {
        // 1. Cancel any in-progress migration
        migrationWorker.cancelCurrentMigration();
        
        // 2. Rebuild the ring from current alive nodes
        rebuildRing(event.getAllAliveNodes());
        
        // 3. Scan store for misplaced keys
        String selfId = membershipList.getSelf().getCacheNodeId();
        Map<NodeInfoHashAdapter, List<MigrationEntry>> migrationPlan = new HashMap<>();
        
        for (Map.Entry<String, CacheItem> entry : store.entrySet()) {
            NodeInfoHashAdapter owner = migrationHashRing.getNode(entry.getKey());
            if (owner != null && !owner.getNodeId().equals(selfId)) {
                migrationPlan
                    .computeIfAbsent(owner, k -> new ArrayList<>())
                    .add(new MigrationEntry(entry.getKey(), entry.getValue()));
            }
        }
        
        int totalKeys = migrationPlan.values().stream().mapToInt(List::size).sum();
        log.info("Migration scan complete: {} keys to migrate to {} nodes",
                totalKeys, migrationPlan.size());
        
        // 4. Submit to migration worker
        if (totalKeys > 0) {
            migrationWorker.startMigration(migrationPlan);
        }
    }
    
    private void rebuildRing(List<NodeInfo> aliveNodes) {
        // Clear and rebuild — simpler than computing diff
        // ConsistentHashRing doesn't have a clear() method,
        // so we remove all existing nodes then add new ones
        for (String nodeId : migrationHashRing.getActiveNodeIds()) {
            // Need to remove by creating adapter with matching nodeId
            // Alternative: track adapters in a local map
        }
        for (NodeInfo node : aliveNodes) {
            migrationHashRing.addNode(new NodeInfoHashAdapter(node));
        }
    }
}
```

**Threading model:**
- `onTopologyChanged()` is called on the Spring event thread — it only schedules, never blocks
- `executeMigration()` runs on the single-threaded `debounceExecutor` — serializes all migration decisions
- Actual key pushes run on the `MigrationWorker`'s own thread pool

---

### 2.6 MigrationWorker

**Package:** `com.edgefabric.caching.migration`  
**File:** `caching/src/main/java/com/edgefabric/caching/migration/MigrationWorker.java`

```java
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "migration.enabled", havingValue = "true", matchIfMissing = true)
public class MigrationWorker {

    private final WebClient migrationWebClient;
    private final Map<String, CacheItem> store;
    private final TimeWheelEvictionService timeWheelEvictionService;
    private final LruEvictionService lruEvictionService;
    private final AtomicLong currentMemoryUsage;
    private final MigrationProperties properties;
    private final MeterRegistry meterRegistry;   // Micrometer
    
    private volatile boolean cancelled = false;
    
    // Rate limiter: Semaphore-based or Guava RateLimiter
    // Using a simple token bucket via scheduled refill
    
    public void startMigration(Map<NodeInfoHashAdapter, List<MigrationEntry>> plan) {
        cancelled = false;
        long startTime = System.nanoTime();
        
        int success = 0, failed = 0, skipped = 0;
        
        for (var entry : plan.entrySet()) {
            NodeInfoHashAdapter targetNode = entry.getKey();
            
            for (MigrationEntry migrationEntry : entry.getValue()) {
                if (cancelled) {
                    skipped += entry.getValue().size(); // approximate
                    break;
                }
                
                // Rate limiting — sleep to maintain keys/sec rate
                rateLimitWait();
                
                boolean migrated = migrateKey(targetNode, migrationEntry, 0);
                if (migrated) {
                    success++;
                    deleteLocalKey(migrationEntry.key());
                } else {
                    failed++;
                }
            }
            if (cancelled) break;
        }
        
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        log.info("Migration complete: {} success, {} failed, {} skipped in {}ms",
                success, failed, skipped, durationMs);
        
        // Emit metrics
        recordMetrics(success, failed, skipped, durationMs);
    }
    
    private boolean migrateKey(NodeInfoHashAdapter target, MigrationEntry entry, int attempt) {
        if (attempt >= properties.getMaxRetries()) {
            log.warn("Max retries exhausted for key={}, skipping", entry.key());
            return false;
        }
        
        try {
            String url = String.format("http://%s:%d/api/v1/internal/cache/%s",
                    target.getHost(), target.getServicePort(), entry.key());
            
            CacheItem item = entry.item();
            
            migrationWebClient.put()
                    .uri(url)
                    .header("X-Quorum-Version", String.valueOf(item.getVersion()))
                    .header("X-Expires-At", String.valueOf(item.getExpiryTime()))
                    .header("Content-Type", item.getContentType())
                    .bodyValue(item.getData())
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(5));
            
            return true;
        } catch (Exception e) {
            log.warn("Migration failed for key={} to {} (attempt {}): {}",
                    entry.key(), target.getNodeId(), attempt + 1, e.getMessage());
            
            // Exponential backoff
            long delay = Math.min(
                    properties.getBackoffBaseMs() * (1L << attempt),
                    properties.getBackoffMaxMs()
            );
            sleepMs(delay);
            
            return migrateKey(target, entry, attempt + 1);  // retry
        }
    }
    
    private void deleteLocalKey(String key) {
        CacheItem removed = store.remove(key);
        if (removed != null) {
            timeWheelEvictionService.removeKey(key);
            lruEvictionService.removeEntry(key);
            currentMemoryUsage.addAndGet(-removed.getMemorySize());
        }
    }
    
    public void cancelCurrentMigration() {
        cancelled = true;
    }
}
```

---

### 2.7 MigrationEntry (Record)

**Package:** `com.edgefabric.caching.migration`

```java
public record MigrationEntry(String key, CacheItem item) {}
```

---

## 3. Sequence Diagram — Full Migration Flow

```
GossipReceiver        InMemoryMembershipList     Spring Events      KeyMigrationService      MigrationWorker      Target Node
     │                         │                      │                     │                      │                   │
     │  merge(incoming)        │                      │                     │                      │                   │
     │────────────────────────>│                      │                     │                      │                   │
     │                         │                      │                     │                      │                   │
     │                         │ (detects ALIVE→DEAD  │                     │                      │                   │
     │                         │  or new ALIVE node)  │                     │                      │                   │
     │                         │                      │                     │                      │                   │
     │                         │  publish(event)      │                     │                      │                   │
     │                         │─────────────────────>│                     │                      │                   │
     │                         │                      │                     │                      │                   │
     │                         │                      │  onTopologyChanged()│                      │                   │
     │                         │                      │────────────────────>│                      │                   │
     │                         │                      │                     │                      │                   │
     │                         │                      │                     │ [debounce 2s]         │                   │
     │                         │                      │                     │─────┐                 │                   │
     │                         │                      │                     │<────┘                 │                   │
     │                         │                      │                     │                      │                   │
     │                         │                      │                     │ rebuildRing()         │                   │
     │                         │                      │                     │─────┐                 │                   │
     │                         │                      │                     │<────┘                 │                   │
     │                         │                      │                     │                      │                   │
     │                         │                      │                     │ scanStore()           │                   │
     │                         │                      │                     │─────┐                 │                   │
     │                         │                      │                     │<────┘                 │                   │
     │                         │                      │                     │                      │                   │
     │                         │                      │                     │ startMigration(plan)  │                   │
     │                         │                      │                     │─────────────────────>│                   │
     │                         │                      │                     │                      │                   │
     │                         │                      │                     │                      │ PUT /internal/    │
     │                         │                      │                     │                      │ cache/{key}       │
     │                         │                      │                     │                      │──────────────────>│
     │                         │                      │                     │                      │                   │
     │                         │                      │                     │                      │      200 OK      │
     │                         │                      │                     │                      │<──────────────────│
     │                         │                      │                     │                      │                   │
     │                         │                      │                     │                      │ deleteLocalKey()  │
     │                         │                      │                     │                      │─────┐             │
     │                         │                      │                     │                      │<────┘             │
```

---

## 4. Where to Publish TopologyChangedEvent

**Location:** `InMemoryMembershipList.merge()` — lines 63-89

The `merge()` method is the single entry point for all gossip-driven state changes. We need to detect:
1. **New node discovered** (line 77-81): `existing == null` → node added
2. **State change accepted** (line 85-88): `shouldAccept()` returns true → check if status changed to ALIVE or DEAD

**Modification approach:**
- `InMemoryMembershipList` needs `ApplicationEventPublisher` injected
- After merge, if the node went ALIVE or DEAD (and it's not self), publish `TopologyChangedEvent`
- Also handle `markDead()` (line 120-126) — called by `FailureDetector` when suspect timeout expires

**Important:** Do NOT publish during `markSuspect()` — SUSPECT is transient and may be refuted. Only ALIVE and DEAD transitions are stable enough to trigger migration.

---

## 5. Hash Ring Consistency Guarantee

| Parameter | LB (LoadBalancerConfig) | Cache Node (MigrationConfig) |
|-----------|-------------------------|------------------------------|
| Virtual nodes | `ring.virtual-nodes` (default 150) | `ring.virtual-nodes` (default 150) |
| Hash algorithm | `ring.hash-algorithm` (default "xxhash") | `ring.hash-algorithm` (default "xxhash") |
| Node ID format | `NodeInfo.getCacheNodeId()` via gossip sync | `NodeInfo.getCacheNodeId()` directly |

Both use the same `ConsistentHashRing` class and `HashProviderFactory`. As long as the `ring.*` config values are identical and the node IDs match, the rings will produce identical routing decisions.

**Explicit config in `application.properties`:**
```properties
# Hash ring config — MUST match loadbalancer ring.* values
ring.virtual-nodes=150
ring.hash-algorithm=xxhash
```

---

## 6. Threading Model

| Thread | Purpose | Count |
|--------|---------|-------|
| Spring event thread | Delivers `TopologyChangedEvent` to `KeyMigrationService.onTopologyChanged()` | 1 (Spring managed) |
| `migration-debounce` | Debounce timer + migration scan + ring rebuild | 1 (daemon) |
| `migration-worker` | Actual HTTP key pushes (rate-limited) | 1 (daemon) |

**Why single-threaded migration worker?**
- Rate limiting is simpler with a single thread (no coordination between threads)
- Key migration is a background, best-effort operation — throughput is intentionally capped
- The bottleneck is network I/O (HTTP calls), not CPU — parallelism adds complexity without proportional benefit at 500 keys/sec

---

## 7. Error Handling & Edge Cases

| Scenario | Handling |
|----------|---------|
| Target node is down | Retry with backoff; after maxRetries, skip key (it stays on this node) |
| Key expired during migration | `store.entrySet()` may include expired keys — let the target's TTL logic handle expiry naturally |
| Stale version on target | Target's `storeData()` rejects writes where `existing.getVersion() >= version` — safe, migration just "fails" harmlessly |
| Node rejoins mid-migration | New `TopologyChangedEvent` → cancels current migration → fresh scan |
| Ring is empty (single node cluster) | `ring.getNode(key)` returns self → no keys to migrate |
| Bootstrap (first join) | Event NOT fired during initial bootstrap — no migration on first startup |
| Concurrent writes to same key | `store.remove(key)` is atomic; if a write sneaks in between migrate and delete, the new write wins (compute atomicity) |

---

## 8. Metrics (Micrometer/Prometheus)

```java
// Counters
cache_migration_keys_total{result="success"}
cache_migration_keys_total{result="failed"} 
cache_migration_keys_total{result="skipped"}
cache_migration_retries_total

// Gauges
cache_migration_queue_size

// Timers
cache_migration_duration_seconds
```

**Note:** The caching module currently does NOT have `micrometer-registry-prometheus` as a dependency. We need to add it, OR if metrics are deferred to Story EPMICMPHE-100 (Expose Cache Metrics via Prometheus), we can use simple `AtomicLong` counters + a metrics endpoint for now. 

**Decision:** Add Micrometer dependency — it's the standard Spring Boot approach and aligns with EPMICMPHE-100.

---

## 9. Configuration Defaults (application.properties additions)

```properties
# ── Key Migration ──
migration.enabled=true
migration.rate-limit=500
migration.debounce-ms=2000
migration.max-retries=3
migration.backoff-base-ms=1000
migration.backoff-max-ms=30000

# ── Hash Ring (must match LB config) ──
ring.virtual-nodes=150
ring.hash-algorithm=xxhash
```

---

## 10. Dependency Changes

### caching/pom.xml — Add:

```xml
<!-- Consistent hashing for local key-ownership checks -->
<dependency>
    <groupId>com.edgefabric</groupId>
    <artifactId>consistent-hashing</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- Micrometer for migration metrics -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

---

## 11. Trade-offs Considered

### Single-threaded vs Multi-threaded Migration Worker
- **Chose: Single-threaded** — simpler rate limiting, sufficient for 500 keys/sec, avoids race conditions on cancel/restart
- Trade-off: Large stores (millions of keys) will take longer to migrate. Acceptable because migration is best-effort background work.

### Rebuild Ring vs Incremental Update
- **Chose: Rebuild on each topology change** — simpler, the ring has at most dozens of nodes. `addNode()`/`removeNode()` is O(virtualNodes) per node.
- Trade-off: Slightly more work than incremental, but negligible at cluster scale.

### Push Model (chosen) vs Pull Model
- **Push (old owner sends)** — iterates local store, no "list keys" API needed
- Pull (new owner requests) — would need a way to discover which keys it should own on each node
- Push is simpler and already specified in the ticket.

### Debounce vs Immediate
- **Chose: 2-second debounce** — during rolling restarts, prevents N separate full-store scans
- Trade-off: Adds 2s latency before migration starts. Acceptable because migration is not latency-critical.

### Delete After Success vs Async Delete
- **Chose: Delete immediately after successful PUT** — simple, deterministic, no orphaned keys
- Trade-off: During migration, both old and new owners may serve the key (stale reads possible). Acceptable because quorum reads through the LB will pick the highest version.

---

## 12. Files Summary

### New Files (all in `caching/src/main/java/com/edgefabric/caching/`)

| File | Package | Purpose |
|------|---------|---------|
| `event/TopologyChangedEvent.java` | event | Spring ApplicationEvent |
| `migration/NodeInfoHashAdapter.java` | migration | Adapts NodeInfo → HashableNode |
| `migration/MigrationProperties.java` | migration | @ConfigurationProperties |
| `migration/MigrationConfig.java` | migration | Bean wiring |
| `migration/KeyMigrationService.java` | migration | Event listener, store scanner |
| `migration/MigrationWorker.java` | migration | Async rate-limited key pusher |
| `migration/MigrationEntry.java` | migration | Record: key + CacheItem |

### Modified Files

| File | Change |
|------|--------|
| `caching/pom.xml` | Add `consistent-hashing` + `micrometer-registry-prometheus` deps |
| `caching/.../membership/InMemoryMembershipList.java` | Inject `ApplicationEventPublisher`, publish event on ALIVE/DEAD transitions |
| `caching/src/main/resources/application.properties` | Add `migration.*` and `ring.*` properties |
