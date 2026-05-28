package com.edgefabric.agentops.observe;

import java.util.List;

/**
 * Load balancer fields within a {@link ClusterSnapshot}.
 *
 * @param status              LB health status from /api/v1/dashboard/export
 * @param activeNodeCount     number of active cache nodes in the ring
 * @param ringSize            total virtual nodes on the ring
 * @param virtualNodesPerNode virtual nodes per physical node
 * @param hashAlgorithm       hash algorithm used (e.g. "xxhash")
 * @param activeNodeIds       list of active node IDs currently in the ring
 */
public record LoadBalancerSnapshot(
        String status,
        int activeNodeCount,
        int ringSize,
        int virtualNodesPerNode,
        String hashAlgorithm,
        List<String> activeNodeIds
) {}
