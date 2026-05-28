package com.edgefabric.loadbalancer.dto;

import java.util.List;

/**
 * Snapshot of the current consistent hash ring state.
 * Returned by GET /api/v1/internal/ring/info
 */
public record RingInfoResponse(
        int nodeCount,
        int ringSize,
        int virtualNodesPerNode,
        String hashAlgorithm,
        List<String> activeNodes
) {}
