package com.edgefabric.caching.migration;

import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.hashing.api.HashableNode;

import java.util.Objects;

/**
 * Adapts {@link NodeInfo} to the {@link HashableNode} interface required by
 * {@link com.edgefabric.hashing.core.ConsistentHashRing}.
 *
 * <p>Uses {@code NodeInfo.getCacheNodeId()} as the node ID, which matches the
 * load balancer's {@code CacheNode.getNodeId()} (populated from
 * {@code ClusterMemberDTO.getNodeId()} during gossip sync). This ensures
 * both rings produce identical routing decisions.</p>
 */
public class NodeInfoHashAdapter implements HashableNode {

    private final NodeInfo nodeInfo;

    public NodeInfoHashAdapter(NodeInfo nodeInfo) {
        Objects.requireNonNull(nodeInfo, "nodeInfo must not be null");
        this.nodeInfo = nodeInfo;
    }

    @Override
    public String getNodeId() {
        return nodeInfo.getCacheNodeId();
    }

    public String getHost() {
        return nodeInfo.getHost();
    }

    public int getServicePort() {
        return nodeInfo.getServicePort();
    }

    public NodeInfo getNodeInfo() {
        return nodeInfo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NodeInfoHashAdapter that)) return false;
        return Objects.equals(getNodeId(), that.getNodeId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getNodeId());
    }

    @Override
    public String toString() {
        return getNodeId() + " (" + getHost() + ":" + getServicePort() + ")";
    }
}
