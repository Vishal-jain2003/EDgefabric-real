package com.edgefabric.loadbalancer.dto.export;

public record GossipMemberSnapshot(
        String nodeId,
        String host,
        int servicePort,
        int gossipPort,
        String status,
        long heartbeat,
        long incarnation,
        long lastUpdatedMs,
        long secondsSinceUpdate,
        boolean self
) {}
