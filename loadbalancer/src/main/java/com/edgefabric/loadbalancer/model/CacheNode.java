package com.edgefabric.loadbalancer.model;

import com.edgefabric.hashing.api.HashableNode;   // ← from JAR
import lombok.Getter;

import java.util.Objects;

public class CacheNode implements HashableNode {

    private final String nodeId;
    @Getter
    private final String host;
    @Getter
    private final int port;

    public CacheNode(String nodeId, String host, int port) {
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
    }

    @Override
    public String getNodeId() { return nodeId; }
    public String getHost() { return host; }
    public int getPort() { return port; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CacheNode)) return false;
        return Objects.equals(nodeId, ((CacheNode) o).nodeId);
    }

    @Override
    public int hashCode() { return Objects.hash(nodeId); }

    @Override
    public String toString() { return nodeId + " (" + host + ":" + port + ")"; }
}