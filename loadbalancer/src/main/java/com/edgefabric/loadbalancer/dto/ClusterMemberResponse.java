package com.edgefabric.loadbalancer.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO returned by the gossip membership endpoint of each cache node.
 *
 * <p>Contract: {@code GET /internal/cluster/members} returns
 * {@code List<ClusterMemberResponse>}.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClusterMemberResponse {

    /** Unique node identifier (e.g. UUID or hostname-based). */
    private String nodeId;

    /** IP or hostname of the cache node. */
    private String host;

    /** HTTP port the cache node listens on. */
    private int port;
}

