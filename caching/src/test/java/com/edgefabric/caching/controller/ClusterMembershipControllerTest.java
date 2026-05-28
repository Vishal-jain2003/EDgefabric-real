package com.edgefabric.caching.controller;

import com.edgefabric.caching.dto.ClusterMemberDTO;
import com.edgefabric.caching.dto.GossipTableDTO;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import com.edgefabric.caching.service.ClusterMembershipService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClusterMembershipControllerTest {

    @Mock
    private ClusterMembershipService clusterMembershipService;

    @Mock
    private MembershipList membershipList;

    @InjectMocks
    private ClusterMembershipController controller;

    // ── /members ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns 200 OK with list of alive members")
    void returnsAliveMembers() {
        List<ClusterMemberDTO> members = List.of(
                new ClusterMemberDTO("node-1", "10.0.1.1", 8080),
                new ClusterMemberDTO("node-2", "10.0.1.2", 8080)
        );
        when(clusterMembershipService.getAliveMembers()).thenReturn(members);

        ResponseEntity<List<ClusterMemberDTO>> response = controller.getClusterMembers();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        assertEquals("node-1", response.getBody().get(0).getNodeId());
        assertEquals("10.0.1.1", response.getBody().get(0).getHost());
        assertEquals(8080, response.getBody().get(0).getPort());
    }

    @Test
    @DisplayName("returns 200 OK with empty list when no alive nodes")
    void returnsEmptyListWhenNoAliveNodes() {
        when(clusterMembershipService.getAliveMembers()).thenReturn(List.of());

        ResponseEntity<List<ClusterMemberDTO>> response = controller.getClusterMembers();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isEmpty());
    }

    @Test
    @DisplayName("delegates to ClusterMembershipService")
    void delegatesToService() {
        when(clusterMembershipService.getAliveMembers()).thenReturn(List.of());

        controller.getClusterMembers();

        verify(clusterMembershipService, times(1)).getAliveMembers();
    }

    @Test
    @DisplayName("returns single member when only one alive node")
    void returnsSingleMember() {
        List<ClusterMemberDTO> members = List.of(
                new ClusterMemberDTO("solo", "192.168.0.1", 9090)
        );
        when(clusterMembershipService.getAliveMembers()).thenReturn(members);

        ResponseEntity<List<ClusterMemberDTO>> response = controller.getClusterMembers();

        assertEquals(1, response.getBody().size());
        assertEquals("solo", response.getBody().get(0).getNodeId());
        assertEquals(9090, response.getBody().get(0).getPort());
    }

    // ── /gossip ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("gossip table returns 200 with all nodes including non-alive")
    void gossipTableReturnsAllNodes() {
        NodeInfo self    = NodeInfo.getInstance("self",    "127.0.0.1", 8082, 7946, Status.ALIVE,   10, 0);
        NodeInfo suspect = NodeInfo.getInstance("node-2",  "10.0.0.2",  8082, 7946, Status.SUSPECT,  5, 1);
        NodeInfo dead    = NodeInfo.getInstance("node-3",  "10.0.0.3",  8082, 7946, Status.DEAD,      3, 0);

        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getDigest()).thenReturn(List.of(self, suspect, dead));

        ResponseEntity<GossipTableDTO> response = controller.getGossipTable();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        GossipTableDTO body = response.getBody();
        assertNotNull(body);
        assertEquals(3, body.getTotalNodes());
        assertEquals(1, body.getAliveCount());
        assertEquals(1, body.getSuspectCount());
        assertEquals(1, body.getDeadCount());
    }

    @Test
    @DisplayName("gossip table marks self correctly")
    void gossipTableMarksSelf() {
        NodeInfo self  = NodeInfo.getInstance("self",   "127.0.0.1", 8082, 7946, Status.ALIVE, 5, 0);
        NodeInfo peer  = NodeInfo.getInstance("peer-1", "10.0.0.2",  8082, 7946, Status.ALIVE, 3, 0);

        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getDigest()).thenReturn(List.of(self, peer));

        ResponseEntity<GossipTableDTO> response = controller.getGossipTable();

        GossipTableDTO body = response.getBody();
        assertNotNull(body);

        boolean selfFound = body.getMembers().stream()
                .filter(m -> "self".equals(m.getNodeId()))
                .findFirst()
                .map(m -> m.isSelf())
                .orElse(false);
        assertTrue(selfFound, "Self node should be marked as self=true");

        boolean peerNotSelf = body.getMembers().stream()
                .filter(m -> "peer-1".equals(m.getNodeId()))
                .findFirst()
                .map(m -> !m.isSelf())
                .orElse(false);
        assertTrue(peerNotSelf, "Peer node should be marked as self=false");
    }

    @Test
    @DisplayName("gossip table exposes heartbeat and incarnation")
    void gossipTableExposesHeartbeatAndIncarnation() {
        NodeInfo self = NodeInfo.getInstance("self", "127.0.0.1", 8082, 7946, Status.ALIVE, 42L, 3L);

        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getDigest()).thenReturn(List.of(self));

        GossipTableDTO body = controller.getGossipTable().getBody();
        assertNotNull(body);

        assertEquals(42L, body.getMembers().get(0).getHeartbeat());
        assertEquals(3L,  body.getMembers().get(0).getIncarnation());
    }

    @Test
    @DisplayName("gossip table is empty when only self is present")
    void gossipTableWithOnlySelf() {
        NodeInfo self = NodeInfo.getInstance("self", "127.0.0.1", 8082, 7946, Status.ALIVE, 1, 0);

        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getDigest()).thenReturn(List.of(self));

        GossipTableDTO body = controller.getGossipTable().getBody();
        assertNotNull(body);
        assertEquals(1, body.getTotalNodes());
        assertEquals(1, body.getAliveCount());
        assertEquals(0, body.getSuspectCount());
        assertEquals(0, body.getDeadCount());
    }

    @Test
    @DisplayName("gossip table includes drainingCount")
    void gossipTableIncludesDrainingCount() {
        NodeInfo self     = NodeInfo.getInstance("self",   "127.0.0.1", 8082, 7946, Status.ALIVE,    10, 0);
        NodeInfo draining = NodeInfo.getInstance("node-2", "10.0.0.2",  8082, 7946, Status.DRAINING,   5, 0);

        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getDigest()).thenReturn(List.of(self, draining));

        ResponseEntity<GossipTableDTO> response = controller.getGossipTable();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        GossipTableDTO body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.getTotalNodes());
        assertEquals(1, body.getAliveCount());
        assertEquals(1, body.getDrainingCount());
        assertEquals(0, body.getSuspectCount());
        assertEquals(0, body.getDeadCount());
    }
}

