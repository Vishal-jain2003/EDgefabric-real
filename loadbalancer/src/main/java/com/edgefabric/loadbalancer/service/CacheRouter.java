package com.edgefabric.loadbalancer.service;

import com.edgefabric.hashing.core.ConsistentHashRing;   // ← from JAR
import com.edgefabric.loadbalancer.metrics.RingMetricsBinder;
import com.edgefabric.loadbalancer.model.CacheNode;

import java.util.List;
import java.util.Set;

public class CacheRouter {

    private final ConsistentHashRing<CacheNode> ring;
    private RingMetricsBinder metricsBinder;

    public CacheRouter(ConsistentHashRing<CacheNode> ring) {
        this.ring = ring;
    }

    /** Called by {@link RingMetricsBinder} during Spring context wiring. */
    public void setMetricsBinder(RingMetricsBinder metricsBinder) {
        this.metricsBinder = metricsBinder;
    }

    public CacheNode route(String key) {
        CacheNode node = ring.getNode(key);
        if (metricsBinder != null) metricsBinder.incrementRouteSingle(node.getNodeId());
        return node;
    }

    /**
     * Returns up to {@code count} distinct replica nodes for the given key.
     * Each selected replica node increments the "replicas" routing counter.
     */
    public List<CacheNode> routeToReplicas(String key, int count) {
        List<CacheNode> nodes = ring.getNodes(key, count);
        if (metricsBinder != null) {
            nodes.forEach(n -> metricsBinder.incrementRouteReplicas(n.getNodeId()));
        }
        return nodes;
    }

    public void addNode(CacheNode node) {
        ring.addNode(node);
        if (metricsBinder != null) metricsBinder.incrementNodeAdded();
    }

    public void removeNode(CacheNode node) {
        ring.removeNode(node);
        if (metricsBinder != null) metricsBinder.incrementNodeRemoved();
    }

    public int nodeCount() {
        return ring.nodeCount();
    }

    public int ringSize() {
        return ring.size();
    }

    public Set<String> activeNodeIds() {
        return ring.getActiveNodeIds();
    }

    /**
     * Finds a CacheNode by its node ID.
     * Used by WAL-based anti-entropy to resolve failed node IDs to CacheNode instances.
     *
     * @param nodeId the node ID (e.g., "cache-node-10.0.2.133-8082")
     * @return the CacheNode if present in the ring, or null if not found
     */
    public CacheNode getNodeById(String nodeId) {
        // Get all nodes by requesting max replicas from an arbitrary key
        // This is a workaround since ConsistentHashRing doesn't expose getAllNodes()
        List<CacheNode> allNodes = ring.getNodes("_lookup_" + nodeId, Integer.MAX_VALUE);
        return allNodes.stream()
                .filter(node -> node.getNodeId().equals(nodeId))
                .findFirst()
                .orElse(null);
    }
}