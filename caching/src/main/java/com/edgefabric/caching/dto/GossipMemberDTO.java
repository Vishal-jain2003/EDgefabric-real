package com.edgefabric.caching.dto;

import com.edgefabric.caching.model.NodeInfo;
import lombok.Getter;

/**
 * A single row in the gossip membership table.
 *
 * <p>Use {@code GET /internal/cluster/gossip} to retrieve the full table.
 * Watch {@code heartbeat} increment every gossip round to confirm the protocol
 * is running, and observe {@code status} change to verify failure detection.</p>
 */
@Getter
public class GossipMemberDTO {

    /** Unique node identifier. */
    private final String nodeId;

    private final String host;
    private final int servicePort;
    private final int gossipPort;

    /** ALIVE | DRAINING | SUSPECT | DEAD */
    private final String status;

    /**
     * Incremented by {@code GossipSender} every gossip round.
     * A rising value confirms gossip rounds are firing for this node.
     */
    private final long heartbeat;

    /**
     * Bumped whenever the node refutes a SUSPECT/DEAD accusation.
     * A rising value means the node has been accused and defended itself.
     */
    private final long incarnation;

    /** Unix-epoch ms of the last accepted gossip update for this entry. */
    private final long lastUpdatedMs;

    /** Seconds elapsed since the last accepted gossip update. */
    private final long secondsSinceUpdate;

    /** True if this entry represents the local node itself. */
    private final boolean self;

    private GossipMemberDTO(NodeInfo node, boolean self) {
        this.nodeId           = node.getCacheNodeId();
        this.host             = node.getHost();
        this.servicePort      = node.getServicePort();
        this.gossipPort       = node.getGossipPort();
        this.status           = node.getStatus().name();
        this.heartbeat        = node.getHeartbeat();
        this.incarnation      = node.getIncarnation();
        this.lastUpdatedMs    = node.getLastUpdatedTime();
        this.secondsSinceUpdate = (System.currentTimeMillis() - node.getLastUpdatedTime()) / 1000;
        this.self             = self;
    }

    public static GossipMemberDTO from(NodeInfo node, String selfId) {
        return new GossipMemberDTO(node, selfId.equals(node.getCacheNodeId()));
    }
}

