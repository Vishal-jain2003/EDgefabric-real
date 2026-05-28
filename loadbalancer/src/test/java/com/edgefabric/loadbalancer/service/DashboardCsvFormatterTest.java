package com.edgefabric.loadbalancer.service;

import com.edgefabric.loadbalancer.dto.export.DashboardExportResponse;
import com.edgefabric.loadbalancer.dto.export.GossipMemberSnapshot;
import com.edgefabric.loadbalancer.dto.export.NodeGossipSnapshot;
import com.edgefabric.loadbalancer.dto.export.RingSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DashboardCsvFormatterTest {

    private final DashboardCsvFormatter formatter = new DashboardCsvFormatter();

    private GossipMemberSnapshot member(String nodeId, String host, String status) {
        return new GossipMemberSnapshot(nodeId, host, 8082, 7946, status, 100, 1, 1700000000000L, 5, false);
    }

    @Test
    void format_multiNodeMultiMember_correctRowCount() {
        NodeGossipSnapshot node1 = new NodeGossipSnapshot(
                "node-1", "10.0.0.1", 8082, true, "2026-04-20T10:00:00Z", 2, 2, 0, 0,
                List.of(member("node-1", "10.0.0.1", "ALIVE"), member("node-2", "10.0.0.2", "ALIVE"))
        );
        NodeGossipSnapshot node2 = new NodeGossipSnapshot(
                "node-2", "10.0.0.2", 8082, true, "2026-04-20T10:00:00Z", 2, 2, 0, 0,
                List.of(member("node-1", "10.0.0.1", "ALIVE"), member("node-2", "10.0.0.2", "ALIVE"))
        );

        DashboardExportResponse response = DashboardExportResponse.builder()
                .exportTimestamp("2026-04-20T10:00:00Z")
                .loadBalancerStatus("UP")
                .loadBalancerStatusCode(200)
                .ring(new RingSnapshot(2, 300, 150, "murmur", List.of("node-1", "node-2")))
                .nodes(List.of(node1, node2))
                .build();

        String csv = formatter.format(response);
        String[] lines = csv.split("\n");

        // 1 header + 2 members from node1 + 2 members from node2 = 5 lines
        assertEquals(5, lines.length);
        assertTrue(lines[0].startsWith("exportTimestamp"));
    }

    @Test
    void format_unreachableNode_singleRowWithEmptyMemberColumns() {
        NodeGossipSnapshot unreachable = new NodeGossipSnapshot(
                "node-1", "10.0.0.1", 8082, false, null, 0, 0, 0, 0, List.of()
        );

        DashboardExportResponse response = DashboardExportResponse.builder()
                .exportTimestamp("2026-04-20T10:00:00Z")
                .loadBalancerStatus("UP")
                .loadBalancerStatusCode(200)
                .ring(new RingSnapshot(1, 150, 150, "murmur", List.of("node-1")))
                .nodes(List.of(unreachable))
                .build();

        String csv = formatter.format(response);
        String[] lines = csv.split("\n");

        // 1 header + 1 row for unreachable node
        assertEquals(2, lines.length);
        assertTrue(lines[1].contains("false"));
    }

    @Test
    void format_emptyNodes_headerOnly() {
        DashboardExportResponse response = DashboardExportResponse.builder()
                .exportTimestamp("2026-04-20T10:00:00Z")
                .loadBalancerStatus("UP")
                .loadBalancerStatusCode(200)
                .ring(new RingSnapshot(0, 0, 150, "murmur", List.of()))
                .nodes(List.of())
                .build();

        String csv = formatter.format(response);
        String[] lines = csv.split("\n");

        // header only
        assertEquals(1, lines.length);
        assertTrue(lines[0].startsWith("exportTimestamp"));
    }

    @Test
    void format_fieldWithComma_isProperlyQuoted() {
        GossipMemberSnapshot memberWithComma = new GossipMemberSnapshot(
                "node,1", "10.0.0.1", 8082, 7946, "ALIVE", 100, 1, 1700000000000L, 5, false
        );

        NodeGossipSnapshot node = new NodeGossipSnapshot(
                "node,1", "10.0.0.1", 8082, true, "2026-04-20T10:00:00Z", 1, 1, 0, 0,
                List.of(memberWithComma)
        );

        DashboardExportResponse response = DashboardExportResponse.builder()
                .exportTimestamp("2026-04-20T10:00:00Z")
                .loadBalancerStatus("UP")
                .loadBalancerStatusCode(200)
                .ring(new RingSnapshot(1, 150, 150, "murmur", List.of("node,1")))
                .nodes(List.of(node))
                .build();

        String csv = formatter.format(response);
        // The value "node,1" should be quoted
        assertTrue(csv.contains("\"node,1\""));
    }
}
