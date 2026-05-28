package com.edgefabric.agentops.observe;

/**
 * Per-node detail within a {@link ClusterSnapshot}.
 *
 * <p>Fields sourced from multiple backends — null means the corresponding
 * data source was unavailable when the snapshot was taken.</p>
 *
 * <h3>Sources</h3>
 * <ul>
 *   <li>Identity / Health: LB dashboard export</li>
 *   <li>Cache stats: {@code GET /api/v1/cache/stats} on the node</li>
 *   <li>Gossip: {@code GET /internal/cluster/gossip} on the node</li>
 *   <li>Prometheus enrichment: per-instance PromQL queries</li>
 * </ul>
 */
public record NodeSnapshot(

        // ── Identity ────────────────────────────────────────────────────────
        String nodeId,
        String host,
        int port,

        // ── Health ──────────────────────────────────────────────────────────
        /** Derived status: HEALTHY | SUSPECT | DEAD | UNREACHABLE */
        String status,
        /** LB's own reachability check for this node */
        Boolean lbReachable,

        // ── Cache stats (/api/v1/cache/stats) ───────────────────────────────
        Double hitRate,
        Double missRate,
        Long totalHits,
        Long totalMisses,
        Long cacheSize,
        Long memorySizeBytes,

        // ── Prometheus enrichment ────────────────────────────────────────────
        Double memoryUsedRatio,
        Double evictionsPerMin,
        /** P50 HTTP response latency in milliseconds */
        Double p50LatencyMs,
        /** P99 HTTP response latency in milliseconds */
        Double p99LatencyMs,
        Boolean drainActive,
        Long selfHealingAttempts,
        Long antiEntropyRepairs,
        Long migrationQueueSize,

        // ── Gossip (/internal/cluster/gossip) ───────────────────────────────
        String gossipStatus,
        Long gossipHeartbeat,
        Long secondsSinceUpdate,
        /** Gossip incarnation number — increments on each node restart */
        Integer incarnation,
        Integer clusterAliveCount,
        Integer clusterSuspectCount,
        Integer clusterDeadCount
) {}
