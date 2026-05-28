package com.edgefabric.agentops.explain;

import java.util.List;
import java.util.Map;

/**
 * Data-driven failure mode configuration.
 * Adding a new failure mode requires only a new entry in {@link #ALL_MODES} — no code change.
 */
public record FailureMode(
        String name,
        String description,
        String severity,
        List<String> recommendations
) {
    public static final Map<String, FailureMode> ALL_MODES = Map.of(
            "node_isolation", new FailureMode(
                    "node_isolation",
                    "One or more nodes are unreachable/dead and isolated from the cluster",
                    "CRITICAL",
                    List.of(
                            "Check network connectivity and firewall rules for isolated nodes",
                            "Verify gossip protocol on port 7946 is reachable",
                            "Check if failure detector (port 7000) has marked nodes dead",
                            "Consider manual node restart if gossip heartbeat stale > 60s"
                    )
            ),
            "ring_rebalancing_lag", new FailureMode(
                    "ring_rebalancing_lag",
                    "Hash ring is rebalancing but migration queue is backed up",
                    "WARNING",
                    List.of(
                            "Monitor migration queue size — should decrease over time",
                            "Check if target nodes have sufficient memory for incoming keys",
                            "Verify consistent-hashing ring has stabilized (no flapping nodes)",
                            "Consider throttling client writes during rebalance"
                    )
            ),
            "cache_memory_pressure", new FailureMode(
                    "cache_memory_pressure",
                    "Cache node memory usage is high causing excessive evictions",
                    "WARNING",
                    List.of(
                            "Consider scaling out (adding cache nodes) to distribute load",
                            "Review TTL policies — shorter TTLs reduce memory pressure",
                            "Check for hot keys causing uneven distribution",
                            "Monitor hit rate — high evictions may degrade hit rate"
                    )
            ),
            "quorum_failure", new FailureMode(
                    "quorum_failure",
                    "Insufficient healthy nodes to meet quorum requirements",
                    "CRITICAL",
                    List.of(
                            "URGENT: Quorum lost — writes and consistent reads will fail",
                            "Immediately investigate and recover dead/suspect nodes",
                            "Check if split-brain has occurred (multiple partitions)",
                            "Consider emergency read-from-any mode if availability > consistency needed"
                    )
            )
    );
}
