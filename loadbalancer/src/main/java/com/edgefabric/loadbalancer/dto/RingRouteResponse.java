package com.edgefabric.loadbalancer.dto;

import java.util.List;

/**
 * Shows which node(s) the ring would route a key to.
 * Returned by GET /api/v1/internal/ring/route?key={key}
 */
public record RingRouteResponse(
        String key,
        NodeInfo primaryNode,
        List<NodeInfo> replicas
) {
    public record NodeInfo(String nodeId, String host, int port) {}
}
