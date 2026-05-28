package com.edgefabric.loadbalancer.dto.export;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class DashboardExportResponse {
    private final String exportTimestamp;
    private final String loadBalancerStatus;
    private final int loadBalancerStatusCode;
    private final RingSnapshot ring;
    private final List<NodeGossipSnapshot> nodes;
}
