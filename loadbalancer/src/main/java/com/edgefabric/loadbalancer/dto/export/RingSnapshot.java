package com.edgefabric.loadbalancer.dto.export;

import java.util.List;

public record RingSnapshot(
        int nodeCount,
        int ringSize,
        int virtualNodesPerNode,
        String hashAlgorithm,
        List<String> activeNodeIds
) {}
