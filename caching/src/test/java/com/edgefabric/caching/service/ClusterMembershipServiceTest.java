package com.edgefabric.caching.service;

import com.edgefabric.caching.dto.ClusterMemberDTO;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClusterMembershipServiceTest {

    @Mock
    private MembershipList membershipList;

    @InjectMocks
    private ClusterMembershipService clusterMembershipService;

    @Test
    @DisplayName("returns all alive members mapped to DTOs")
    void returnsAliveMembersAsDTOs() {
        NodeInfo node1 = NodeInfo.getInstance("node-1", "10.0.1.1", 8080, 7946,
                Status.ALIVE, 5, 1);
        NodeInfo node2 = NodeInfo.getInstance("node-2", "10.0.1.2", 8080, 7946,
                Status.ALIVE, 3, 1);

        when(membershipList.getAliveNodes()).thenReturn(List.of(node1, node2));

        List<ClusterMemberDTO> result = clusterMembershipService.getAliveMembers();

        assertEquals(2, result.size());

        ClusterMemberDTO dto1 = result.get(0);
        assertEquals("node-1", dto1.getNodeId());
        assertEquals("10.0.1.1", dto1.getHost());
        assertEquals(8080, dto1.getPort());

        ClusterMemberDTO dto2 = result.get(1);
        assertEquals("node-2", dto2.getNodeId());
        assertEquals("10.0.1.2", dto2.getHost());
        assertEquals(8080, dto2.getPort());
    }

    @Test
    @DisplayName("returns empty list when no alive nodes exist")
    void returnsEmptyWhenNoAliveNodes() {
        when(membershipList.getAliveNodes()).thenReturn(List.of());

        List<ClusterMemberDTO> result = clusterMembershipService.getAliveMembers();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("maps servicePort to port field correctly")
    void mapsServicePortCorrectly() {
        NodeInfo node = NodeInfo.getInstance("node-x", "192.168.1.10", 9090, 7946,
                Status.ALIVE, 1, 0);

        when(membershipList.getAliveNodes()).thenReturn(List.of(node));

        List<ClusterMemberDTO> result = clusterMembershipService.getAliveMembers();

        assertEquals(1, result.size());
        assertEquals(9090, result.get(0).getPort());
    }

    @Test
    @DisplayName("returns single member when only one alive node")
    void returnsSingleMember() {
        NodeInfo node = NodeInfo.getInstance("solo-node", "10.0.0.1", 8080, 7946,
                Status.ALIVE, 10, 2);

        when(membershipList.getAliveNodes()).thenReturn(List.of(node));

        List<ClusterMemberDTO> result = clusterMembershipService.getAliveMembers();

        assertEquals(1, result.size());
        assertEquals("solo-node", result.get(0).getNodeId());
    }

    @Test
    @DisplayName("delegates to membershipList.getAliveNodes()")
    void delegatesToMembershipList() {
        when(membershipList.getAliveNodes()).thenReturn(List.of());

        clusterMembershipService.getAliveMembers();

        verify(membershipList, times(1)).getAliveNodes();
    }
}

