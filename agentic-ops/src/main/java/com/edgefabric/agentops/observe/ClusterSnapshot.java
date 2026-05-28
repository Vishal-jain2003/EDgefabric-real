package com.edgefabric.agentops.observe;

import java.time.Instant;
import java.util.List;

/**
 * Immutable in-memory snapshot of the EdgeFabric cluster at a single point in time.
 * Never persisted — assembled fresh on every observe request.
 *
 * <p>This record is the central contract between Phase 2 (Observe) and downstream
 * phases: Explain (EPMICMPHE-88), Act (EPMICMPHE-87), Approval (EPMICMPHE-86).</p>
 */
public record ClusterSnapshot(

        // ── Cluster summary ─────────────────────────────────────────────────
        int totalNodes,
        int healthyNodes,
        int suspectNodes,
        int deadNodes,
        Instant snapshotTakenAt,

        // ── Load balancer ────────────────────────────────────────────────────
        LoadBalancerSnapshot loadBalancer,

        // ── Per-node detail ─────────────────────────────────────────────────
        List<NodeSnapshot> nodes,

        // ── Cluster-wide aggregates ──────────────────────────────────────────
        /** Sum of totalHits across all nodes */
        Long totalClusterHits,
        /** Sum of totalMisses across all nodes */
        Long totalClusterMisses,
        /** Average hit rate across all nodes with data */
        Double avgHitRate,

        // ── SLO metrics (Prometheus recording rules) ─────────────────────────
        /** job:edgefabric_availability:ratio_1m — 1.0 = 100% available */
        Double sloAvailabilityRatio,
        /** job:edgefabric_burn_rate:1h — values > 1 mean budget is burning too fast */
        Double sloBurnRate1h,
        /** job:edgefabric_error_rate:ratio_5m — fraction of requests that errored */
        Double errorRate5m
) {

    /**
     * Convenience factory for an empty / fallback snapshot when the LB is unreachable.
     */
    public static ClusterSnapshot empty() {
        return new ClusterSnapshot(0, 0, 0, 0, Instant.now(), null, List.of(),
                0L, 0L, 0.0, null, null, null);
    }
}
