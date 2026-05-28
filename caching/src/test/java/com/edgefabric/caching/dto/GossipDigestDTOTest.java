package com.edgefabric.caching.dto;

import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GossipDigestDTOTest {

    @Test
    void shouldConvertNodeInfoToGossipNodeEntry() {
        NodeInfo nodeInfo = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.SUSPECT, 12, 3);

        GossipDigestDTO.GossipNodeEntry entry = GossipDigestDTO.GossipNodeEntry.fromNodeInfo(nodeInfo);

        assertEquals("node-1", entry.getCacheNodeId());
        assertEquals("127.0.0.1", entry.getHost());
        assertEquals(8082, entry.getServicePort());
        assertEquals(7946, entry.getGossipPort());
        assertEquals("SUSPECT", entry.getStatus());
        assertEquals(12, entry.getHeartbeat());
        assertEquals(3, entry.getIncarnation());
    }

    @Test
    void shouldConvertGossipNodeEntryToNodeInfo() {
        GossipDigestDTO.GossipNodeEntry entry = GossipDigestDTO.GossipNodeEntry.builder()
                .cacheNodeId("node-2")
                .host("127.0.0.2")
                .servicePort(8083)
                .gossipPort(7947)
                .status("DEAD")
                .heartbeat(15)
                .incarnation(4)
                .build();

        NodeInfo nodeInfo = entry.toNodeInfo();

        assertEquals("node-2", nodeInfo.getCacheNodeId());
        assertEquals("127.0.0.2", nodeInfo.getHost());
        assertEquals(8083, nodeInfo.getServicePort());
        assertEquals(7947, nodeInfo.getGossipPort());
        assertEquals(Status.DEAD, nodeInfo.getStatus());
        assertEquals(15, nodeInfo.getHeartbeat());
        assertEquals(4, nodeInfo.getIncarnation());
    }

    @Test
    void pingAckResponseStoresAcknowledgedFlag() {
        PingAckResponse ack = new PingAckResponse(true);
        assertTrue(ack.acknowledged());

        PingAckResponse nack = new PingAckResponse(false);
        assertFalse(nack.acknowledged());
    }

    @Test
    void pingReqRequestStoresTargetAndTimeout() {
        NodeInfo target = new NodeInfo("target-node", "10.0.0.1", 8082, 7946);
        PingReqRequest req = new PingReqRequest(target, 500L);

        assertEquals(target, req.targetNode());
        assertEquals(500L, req.timeoutMs());
    }

    @Test
    void shouldThrowForInvalidStatusValue() {
        GossipDigestDTO.GossipNodeEntry entry = GossipDigestDTO.GossipNodeEntry.builder()
                .cacheNodeId("node-3")
                .host("127.0.0.3")
                .servicePort(8084)
                .gossipPort(7948)
                .status("UNKNOWN")
                .heartbeat(1)
                .incarnation(1)
                .build();

        assertThrows(IllegalArgumentException.class, entry::toNodeInfo);
    }
}
