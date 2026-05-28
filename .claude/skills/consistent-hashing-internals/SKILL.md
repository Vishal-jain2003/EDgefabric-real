---
name: consistent-hashing-internals
description: How EdgeFabric's consistent hash ring works — virtual nodes, key migration, ring rebuild on topology change. Load this whenever the task mentions hash ring, key distribution, rebalancing, virtual nodes, or "what node owns key X".
---

# Skill: Consistent Hashing Internals

## Where it lives

- Library: `consistent-hashing/` (shared across LB and cache)
- LB consumer: builds the ring from Cloud Map members
- Cache consumer: `NodeInfoHashAdapter` — maps `NodeInfo` → ring key

## The ring

- **Hash function:** stable 32/64-bit hash of `node-id + virtual-node-index`
  (see `symbols.json` → look up the ring class).
- **Virtual nodes:** N replicas per physical node (default in config). More
  vnodes = smoother distribution but more rebuild cost.
- **Lookup:** for key K, find the first vnode token ≥ hash(K) on the ring
  (wrap around if needed).

## Topology changes

The ring is **rebuilt** — never patched in place — when any of these fire:

1. Cloud Map membership changes (LB only) — Cloud Map watcher event.
2. `TopologyChangedEvent` (cache only) — gossip detected join/leave/dead.
3. Manual reload via `/api/v1/cluster/reload` (admin endpoint).

Rebuild is O(V × N log V × N) where V=vnodes, N=nodes. Keep V × N under ~5000
total tokens for sub-millisecond lookups.

## Key migration (rebalancing)

When a node joins or leaves, a fraction of keys must move to/from the new
owner. Live test script: `test-key-migration.ps1`.

```
1. New node joins → gossip → TopologyChangedEvent
2. Each existing node:
     for k in localKeys:
        newOwner = ring.lookup(k)
        if newOwner != self:
            transfer k to newOwner   (DrainController endpoint)
3. New node accepts incoming keys via /api/v1/internal/cache/{key}
```

The migration is **idempotent** — replays are safe. WAL ensures no key is
lost mid-transfer. See `caching/.../wal/` for the write-ahead log.

## Drain (graceful shutdown)

`DrainController` exposes:

```
POST /internal/drain/start   → stop accepting new writes, transfer all keys
GET  /internal/drain/status  → 0..100% complete
POST /internal/drain/finish  → mark node for SSM stop
```

Always drain before terminating a cache node in production. The
`aws-ssm-deployer` agent does this automatically on rolling restarts.

## Testing the ring

| Script | Purpose |
|--------|---------|
| `test-rebalancing.ps1` | Spin LB + 3 caches, measure key movement on +1 node |
| `test-key-migration.ps1` | Verify zero key loss during migration |
| `docker-compose.migration-test.yml` | Reproducible local migration test bed |

Evidence captured under `migration-test-evidence/`.

## Common pitfalls

- **Don't** mutate vnode count at runtime — requires a coordinated cluster restart.
- **Don't** trust `ring.lookup(k)` immediately after a `TopologyChangedEvent`
  — wait for the rebuild future to complete (it's exposed as a `Mono<Void>`).
- LB and cache **must** use the same hash function and vnode count, otherwise
  they disagree on ownership and you get cache misses on every request.
  Both consume `consistent-hashing` as a Maven dependency — pin the version.
