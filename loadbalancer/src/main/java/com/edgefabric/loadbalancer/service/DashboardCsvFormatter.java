package com.edgefabric.loadbalancer.service;

import com.edgefabric.loadbalancer.dto.export.DashboardExportResponse;
import com.edgefabric.loadbalancer.dto.export.GossipMemberSnapshot;
import com.edgefabric.loadbalancer.dto.export.NodeGossipSnapshot;
import com.edgefabric.loadbalancer.dto.export.RingSnapshot;
import org.springframework.stereotype.Component;

@Component
public class DashboardCsvFormatter {

    private static final String HEADER = String.join(",",
            "exportTimestamp",
            "loadBalancerStatus",
            "loadBalancerStatusCode",
            "ringNodeCount",
            "ringSize",
            "virtualNodesPerNode",
            "hashAlgorithm",
            "sourceNodeId",
            "sourceHost",
            "sourcePort",
            "reachable",
            "snapshotTime",
            "totalNodes",
            "aliveCount",
            "suspectCount",
            "deadCount",
            "memberNodeId",
            "memberHost",
            "memberServicePort",
            "memberGossipPort",
            "memberStatus",
            "memberHeartbeat",
            "memberIncarnation",
            "memberLastUpdatedMs",
            "memberSecondsSinceUpdate",
            "memberSelf"
    );

    public String format(DashboardExportResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append("\n");

        RingSnapshot ring = response.getRing();

        if (response.getNodes() == null || response.getNodes().isEmpty()) {
            return sb.toString();
        }

        for (NodeGossipSnapshot node : response.getNodes()) {
            if (!node.reachable() || node.members() == null || node.members().isEmpty()) {
                sb.append(csvValue(response.getExportTimestamp())).append(",");
                sb.append(csvValue(response.getLoadBalancerStatus())).append(",");
                sb.append(response.getLoadBalancerStatusCode()).append(",");
                sb.append(ring.nodeCount()).append(",");
                sb.append(ring.ringSize()).append(",");
                sb.append(ring.virtualNodesPerNode()).append(",");
                sb.append(csvValue(ring.hashAlgorithm())).append(",");
                sb.append(csvValue(node.sourceNodeId())).append(",");
                sb.append(csvValue(node.sourceHost())).append(",");
                sb.append(node.sourcePort()).append(",");
                sb.append(node.reachable()).append(",");
                sb.append(csvValue(node.snapshotTime())).append(",");
                sb.append(node.totalNodes()).append(",");
                sb.append(node.aliveCount()).append(",");
                sb.append(node.suspectCount()).append(",");
                sb.append(node.deadCount()).append(",");
                // empty member columns
                sb.append(",,,,,,,,,");
                sb.append("\n");
                continue;
            }

            for (GossipMemberSnapshot member : node.members()) {
                sb.append(csvValue(response.getExportTimestamp())).append(",");
                sb.append(csvValue(response.getLoadBalancerStatus())).append(",");
                sb.append(response.getLoadBalancerStatusCode()).append(",");
                sb.append(ring.nodeCount()).append(",");
                sb.append(ring.ringSize()).append(",");
                sb.append(ring.virtualNodesPerNode()).append(",");
                sb.append(csvValue(ring.hashAlgorithm())).append(",");
                sb.append(csvValue(node.sourceNodeId())).append(",");
                sb.append(csvValue(node.sourceHost())).append(",");
                sb.append(node.sourcePort()).append(",");
                sb.append(node.reachable()).append(",");
                sb.append(csvValue(node.snapshotTime())).append(",");
                sb.append(node.totalNodes()).append(",");
                sb.append(node.aliveCount()).append(",");
                sb.append(node.suspectCount()).append(",");
                sb.append(node.deadCount()).append(",");
                sb.append(csvValue(member.nodeId())).append(",");
                sb.append(csvValue(member.host())).append(",");
                sb.append(member.servicePort()).append(",");
                sb.append(member.gossipPort()).append(",");
                sb.append(csvValue(member.status())).append(",");
                sb.append(member.heartbeat()).append(",");
                sb.append(member.incarnation()).append(",");
                sb.append(member.lastUpdatedMs()).append(",");
                sb.append(member.secondsSinceUpdate()).append(",");
                sb.append(member.self());
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private String csvValue(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
