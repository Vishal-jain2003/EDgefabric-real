package com.edgefabric.loadbalancer.dto.export;

import java.util.List;

public record NodeGossipSnapshot(
        String sourceNodeId,
        String sourceHost,
        int sourcePort,
        boolean reachable,
        String snapshotTime,
        int totalNodes,
        long aliveCount,
        long suspectCount,
        long deadCount,
        List<GossipMemberSnapshot> members
) {}
