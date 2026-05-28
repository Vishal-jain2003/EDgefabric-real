package com.edgefabric.caching.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * DTO returned by the membership endpoint ({@code GET /internal/cluster/members}).
 *
 * <p>Contract between cache nodes and the load balancer — the LB uses
 * these fields to build/sync its consistent hash ring.</p>
 */
@Getter
@AllArgsConstructor
public class ClusterMemberDTO {
    private final String nodeId;
    private final String host;

    private final int port;
}

