package com.edgefabric.caching.gossip;

import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PeerSelectorTest {

    @Mock
    private MembershipList membershipList;

    private PeerSelector peerSelector;
    private NodeInfo self;
    private NodeInfo nodeA;
    private NodeInfo nodeB;

    @BeforeEach
    void setUp() {
        peerSelector = new PeerSelector(membershipList);
        self = new NodeInfo("self", "127.0.0.1", 8082, 7946);
        nodeA = new NodeInfo("node-a", "10.0.0.2", 8082, 7946);
        nodeB = new NodeInfo("node-b", "10.0.0.3", 8082, 7946);
    }

    @Test
    void nextTargetReturnsEmptyWhenNoAlivePeers() {
        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getNodesForSuspectCheck()).thenReturn(List.of(self));

        Optional<NodeInfo> target = peerSelector.nextTarget();

        assertTrue(target.isEmpty());
    }

    @Test
    void nextTargetReturnsAlivePeer() {
        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getNodesForSuspectCheck()).thenReturn(List.of(self, nodeA));
        when(membershipList.getNode("node-a")).thenReturn(nodeA);

        Optional<NodeInfo> target = peerSelector.nextTarget();

        assertTrue(target.isPresent());
        assertEquals("node-a", target.get().getCacheNodeId());
    }

    @Test
    void nextTargetReturnsEmptyWhenChosenPeerIsNotAlive() {
        nodeA.setStatus(Status.SUSPECT);
        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getNodesForSuspectCheck()).thenReturn(List.of(self, nodeA));
        when(membershipList.getNode("node-a")).thenReturn(nodeA);

        Optional<NodeInfo> target = peerSelector.nextTarget();

        assertTrue(target.isEmpty());
    }

    @Test
    void nextTargetUsesRoundRobinAcrossRing() {
        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getNodesForSuspectCheck()).thenReturn(List.of(self, nodeA, nodeB));
        when(membershipList.getNode("node-a")).thenReturn(nodeA);
        when(membershipList.getNode("node-b")).thenReturn(nodeB);

        Optional<NodeInfo> first = peerSelector.nextTarget();
        Optional<NodeInfo> second = peerSelector.nextTarget();

        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        assertNotEquals(first.get().getCacheNodeId(), second.get().getCacheNodeId());
    }

    @Test
    void selectHelpersExcludesSelfAndTargetAndHonorsLimit() {
        NodeInfo nodeC = new NodeInfo("node-c", "10.0.0.4", 8082, 7946);
        // getRandomPeers already excludes self; returns shuffled peers
        when(membershipList.getRandomPeers(3)).thenReturn(List.of(nodeA, nodeB, nodeC));

        List<NodeInfo> helpers = peerSelector.selectHelpers("node-b", 2);

        assertEquals(2, helpers.size());
        assertTrue(helpers.stream().noneMatch(n -> "self".equals(n.getCacheNodeId())));
        assertTrue(helpers.stream().noneMatch(n -> "node-b".equals(n.getCacheNodeId())));
    }

    @Test
    void selectHelpersReturnsEmptyWhenLimitNonPositive() {
        List<NodeInfo> helpers = peerSelector.selectHelpers("node-b", 0);
        assertTrue(helpers.isEmpty());
    }

    @Test
    void nextTargetIncludesDrainingPeers() {
        NodeInfo drainingNode = NodeInfo.getInstance("node-drain", "10.0.0.5", 8082, 7946,
                Status.DRAINING, 0, 0);
        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getNodesForSuspectCheck()).thenReturn(List.of(drainingNode));
        when(membershipList.getNode("node-drain")).thenReturn(drainingNode);

        Optional<NodeInfo> target = peerSelector.nextTarget();

        assertTrue(target.isPresent());
        assertEquals("node-drain", target.get().getCacheNodeId());
    }
}

