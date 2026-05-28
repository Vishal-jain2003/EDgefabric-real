package com.edgefabric.caching.gossip;

import com.edgefabric.caching.event.TopologyChangedEvent;
import com.edgefabric.caching.membership.InMemoryMembershipList;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryMembershipListTest {

    private InMemoryMembershipList membershipList;

    @BeforeEach
    void setUp() {
        NodeInfo self = new NodeInfo("self-node", "127.0.0.1", 8080, 7946);
        membershipList = new InMemoryMembershipList(self);
    }

    // ── Constructor ──

    @Test
    void constructorShouldAddSelfToMembers() {
        assertEquals(1, membershipList.size());
        assertNotNull(membershipList.getSelf());
        assertEquals("self-node", membershipList.getSelf().getCacheNodeId());
    }

    @Test
    void constructorShouldRejectNull() {
        assertThrows(NullPointerException.class,
                () -> new InMemoryMembershipList(null));
    }

    // ── Self Node ──

    @Nested
    class SelfNodeTests {

        @Test
        void getSelfShouldReturnSelfNodeInfo() {
            NodeInfo result = membershipList.getSelf();

            assertNotNull(result);
            assertEquals("self-node", result.getCacheNodeId());
            assertEquals("127.0.0.1", result.getHost());
            assertEquals(Status.ALIVE, result.getStatus());
        }

        @Test
        void bumpSelfHeartbeatShouldIncrementHeartbeat() {
            long before = membershipList.getSelf().getHeartbeat();

            membershipList.bumpSelfHeartbeat();

            assertEquals(before + 1, membershipList.getSelf().getHeartbeat());
        }

        @Test
        void bumpSelfHeartbeatShouldUpdateLastUpdatedTime() {
            long timeBefore = System.currentTimeMillis();

            membershipList.bumpSelfHeartbeat();

            long timeAfter = System.currentTimeMillis();
            long updated = membershipList.getSelf().getLastUpdatedTime();

            assertTrue(updated >= timeBefore);
            assertTrue(updated <= timeAfter);
        }

        @Test
        void bumpSelfHeartbeatMultipleTimesShouldIncrementEachTime() {
            membershipList.bumpSelfHeartbeat();
            membershipList.bumpSelfHeartbeat();
            membershipList.bumpSelfHeartbeat();

            assertEquals(3, membershipList.getSelf().getHeartbeat());
        }

        @Test
        void refuteSuspicionShouldBumpIncarnation() {
            long before = membershipList.getSelf().getIncarnation();

            membershipList.refuteSuspicion();

            assertEquals(before + 1, membershipList.getSelf().getIncarnation());
        }

        @Test
        void refuteSuspicionShouldSetStatusAlive() {
            membershipList.getSelf().setStatus(Status.SUSPECT);

            membershipList.refuteSuspicion();

            assertEquals(Status.ALIVE, membershipList.getSelf().getStatus());
        }

        @Test
        void refuteSuspicionShouldUpdateLastUpdatedTime() {
            long timeBefore = System.currentTimeMillis();

            membershipList.refuteSuspicion();

            long timeAfter = System.currentTimeMillis();
            long updated = membershipList.getSelf().getLastUpdatedTime();

            assertTrue(updated >= timeBefore);
            assertTrue(updated <= timeAfter);
        }
    }

    @Test
    void shouldNotRefuteForStaleSelfSuspicion() {
        NodeInfo self = membershipList.getSelf();
        self.setIncarnation(5);

        NodeInfo incoming = NodeInfo.getInstance(
                self.getCacheNodeId(),
                self.getHost(),
                self.getServicePort(),
                self.getGossipPort(),
                Status.SUSPECT,
                self.getHeartbeat(),
                4 // stale
        );

        membershipList.merge(incoming);

        assertEquals(5, self.getIncarnation()); // no change
    }

    @Test
    void shouldRefuteForEqualIncarnationSuspicion() {
        NodeInfo self = membershipList.getSelf();
        self.setIncarnation(5);

        NodeInfo incoming = NodeInfo.getInstance(
                self.getCacheNodeId(),
                self.getHost(),
                self.getServicePort(),
                self.getGossipPort(),
                Status.SUSPECT,
                self.getHeartbeat(),
                5 // equal
        );

        membershipList.merge(incoming);

        assertEquals(6, self.getIncarnation());
        assertEquals(Status.ALIVE, self.getStatus());
    }

    // ── Merge: Self Gossip ──

    @Nested
    class MergeSelfGossipTests {

        @Test
        void mergeShouldRejectNullIncoming() {
            assertThrows(NullPointerException.class,
                    () -> membershipList.merge(null));
        }

        @Test
        void mergeShouldRefuteWhenSelfIsSuspected() {
            NodeInfo incomingSelf = NodeInfo.getInstance(
                    "self-node", "127.0.0.1", 8080, 7946,
                    Status.SUSPECT, 0, 0);

            membershipList.merge(incomingSelf);

            assertEquals(Status.ALIVE, membershipList.getSelf().getStatus());
            assertEquals(1, membershipList.getSelf().getIncarnation());
        }

        @Test
        void mergeShouldRefuteWhenSelfIsDead() {
            NodeInfo incomingSelf = NodeInfo.getInstance(
                    "self-node", "127.0.0.1", 8080, 7946,
                    Status.DEAD, 0, 0);

            membershipList.merge(incomingSelf);

            assertEquals(Status.ALIVE, membershipList.getSelf().getStatus());
            assertEquals(1, membershipList.getSelf().getIncarnation());
        }

        @Test
        void mergeShouldIgnoreAliveGossipAboutSelf() {
            NodeInfo incomingSelf = NodeInfo.getInstance(
                    "self-node", "127.0.0.1", 8080, 7946,
                    Status.ALIVE, 5, 0);

            long incarnationBefore = membershipList.getSelf().getIncarnation();

            membershipList.merge(incomingSelf);

            assertEquals(incarnationBefore, membershipList.getSelf().getIncarnation());
        }
    }

    // ── Merge: Unknown Node ──

    @Nested
    class MergeUnknownNodeTests {

        @Test
        void mergeShouldAddUnknownNode() {
            NodeInfo newNode = new NodeInfo("node-2", "10.0.0.2", 8080, 7946);

            membershipList.merge(newNode);

            assertEquals(2, membershipList.size());
            assertNotNull(membershipList.getNode("node-2"));
        }

        @Test
        void mergeShouldSetLastUpdatedTimeForNewNode() {
            NodeInfo newNode = new NodeInfo("node-2", "10.0.0.2", 8080, 7946);

            long timeBefore = System.currentTimeMillis();
            membershipList.merge(newNode);
            long timeAfter = System.currentTimeMillis();

            long updated = membershipList.getNode("node-2").getLastUpdatedTime();
            assertTrue(updated >= timeBefore);
            assertTrue(updated <= timeAfter);
        }
    }

    // ── Merge: Incarnation Comparison ──

    @Nested
    class MergeIncarnationTests {

        @Test
        void mergeShouldAcceptHigherIncarnation() {
            NodeInfo node = new NodeInfo("node-2", "10.0.0.2", 8080, 7946);
            node.setIncarnation(1);
            membershipList.merge(node);

            NodeInfo updated = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.SUSPECT, 0, 5);

            membershipList.merge(updated);

            assertEquals(5, membershipList.getNode("node-2").getIncarnation());
            assertEquals(Status.SUSPECT, membershipList.getNode("node-2").getStatus());
        }

        @Test
        void mergeShouldRejectLowerIncarnation() {
            NodeInfo node = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.ALIVE, 10, 5);
            membershipList.merge(node);

            NodeInfo stale = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.DEAD, 20, 3);

            membershipList.merge(stale);

            // Should keep the existing (incarnation=5), not the stale (incarnation=3)
            assertEquals(5, membershipList.getNode("node-2").getIncarnation());
            assertEquals(Status.ALIVE, membershipList.getNode("node-2").getStatus());
        }

        @Test
        void mergeShouldAcceptHigherIncarnationEvenIfStatusIsLower() {
            NodeInfo suspect = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.SUSPECT, 10, 2);
            membershipList.merge(suspect);

            // Node refuted suspicion — now ALIVE with higher incarnation
            NodeInfo refuted = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.ALIVE, 10, 3);

            membershipList.merge(refuted);

            assertEquals(Status.ALIVE, membershipList.getNode("node-2").getStatus());
            assertEquals(3, membershipList.getNode("node-2").getIncarnation());
        }
    }

    // ── Merge: Same Incarnation ──

    @Nested
    class MergeSameIncarnationTests {

        @Test
        void mergeShouldAcceptWhenStatusOutranks() {
            NodeInfo alive = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.ALIVE, 5, 1);
            membershipList.merge(alive);

            NodeInfo suspect = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.SUSPECT, 5, 1);

            membershipList.merge(suspect);

            assertEquals(Status.SUSPECT, membershipList.getNode("node-2").getStatus());
        }

        @Test
        void mergeShouldAcceptDeadOverSuspect() {
            NodeInfo suspect = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.SUSPECT, 5, 1);
            membershipList.merge(suspect);

            NodeInfo dead = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.DEAD, 5, 1);

            membershipList.merge(dead);

            assertEquals(Status.DEAD, membershipList.getNode("node-2").getStatus());
        }

        @Test
        void mergeShouldAcceptDeadOverAlive() {
            NodeInfo alive = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.ALIVE, 5, 1);
            membershipList.merge(alive);

            NodeInfo dead = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.DEAD, 5, 1);

            membershipList.merge(dead);

            assertEquals(Status.DEAD, membershipList.getNode("node-2").getStatus());
        }

        @Test
        void mergeShouldRejectAliveOverSuspect() {
            NodeInfo suspect = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.SUSPECT, 5, 1);
            membershipList.merge(suspect);

            NodeInfo alive = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.ALIVE, 10, 1);

            membershipList.merge(alive);

            // SUSPECT should NOT be overridden by ALIVE at same incarnation
            assertEquals(Status.SUSPECT, membershipList.getNode("node-2").getStatus());
        }

        @Test
        void mergeShouldRejectAliveOverDead() {
            NodeInfo dead = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.DEAD, 5, 1);
            membershipList.merge(dead);

            NodeInfo alive = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.ALIVE, 10, 1);

            membershipList.merge(alive);

            assertEquals(Status.DEAD, membershipList.getNode("node-2").getStatus());
        }

        @Test
        void mergeShouldAcceptHigherHeartbeatAtSameStatus() {
            NodeInfo node = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.ALIVE, 5, 1);
            membershipList.merge(node);

            NodeInfo fresher = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.ALIVE, 10, 1);

            membershipList.merge(fresher);

            assertEquals(10, membershipList.getNode("node-2").getHeartbeat());
        }

        @Test
        void mergeShouldRejectLowerHeartbeatAtSameStatus() {
            NodeInfo node = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.ALIVE, 10, 1);
            membershipList.merge(node);

            NodeInfo stale = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.ALIVE, 5, 1);

            membershipList.merge(stale);

            assertEquals(10, membershipList.getNode("node-2").getHeartbeat());
        }

        @Test
        void mergeShouldRejectEqualHeartbeatAtSameStatus() {
            NodeInfo node = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.ALIVE, 5, 1);
            membershipList.merge(node);

            NodeInfo same = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.ALIVE, 5, 1);

            long timeBefore = membershipList.getNode("node-2").getLastUpdatedTime();

            membershipList.merge(same);

            // lastUpdatedTime should NOT change since merge was rejected
            assertEquals(timeBefore, membershipList.getNode("node-2").getLastUpdatedTime());
        }
    }

    // ── Gossip Sender Helpers ──

    @Nested
    class GossipSenderTests {

        @Test
        void getRandomPeersShouldExcludeSelf() {
            NodeInfo node2 = new NodeInfo("node-2", "10.0.0.2", 8080, 7946);
            NodeInfo node3 = new NodeInfo("node-3", "10.0.0.3", 8080, 7946);
            membershipList.merge(node2);
            membershipList.merge(node3);

            List<NodeInfo> peers = membershipList.getRandomPeers(5);
            List<String> peerIds = peers.stream().map(NodeInfo::getCacheNodeId).toList();

            assertFalse(peerIds.contains("self-node"), "Random peers should not include self node");
        }

        @Test
        void getRandomPeersShouldExcludeDeadNodes() {
            NodeInfo alive = new NodeInfo("node-2", "10.0.0.2", 8080, 7946);
            NodeInfo dead = NodeInfo.getInstance(
                    "node-3", "10.0.0.3", 8080, 7946,
                    Status.DEAD, 0, 0);
            membershipList.merge(alive);
            membershipList.merge(dead);

            List<NodeInfo> peers = membershipList.getRandomPeers(5);
            List<String> peerIds = peers.stream().map(NodeInfo::getCacheNodeId).toList();

            assertFalse(peerIds.contains("node-3"), "Random peers should not include dead nodes");
            assertEquals(1, peers.size());
        }

        @Test
        void getRandomPeersShouldIncludeSuspectNodes() {
            NodeInfo suspect = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.SUSPECT, 0, 0);
            membershipList.merge(suspect);

            List<NodeInfo> peers = membershipList.getRandomPeers(5);

            assertEquals(1, peers.size());
            assertEquals("node-2", peers.getFirst().getCacheNodeId());
        }

        @Test
        void getRandomPeersShouldLimitToK() {
            for (int i = 0; i < 10; i++) {
                membershipList.merge(new NodeInfo("node-" + i, "10.0.0." + i, 8080, 7946));
            }

            List<NodeInfo> peers = membershipList.getRandomPeers(3);

            assertEquals(3, peers.size());
        }

        @Test
        void getRandomPeersShouldReturnFewerThanKIfNotEnough() {
            membershipList.merge(new NodeInfo("node-2", "10.0.0.2", 8080, 7946));

            List<NodeInfo> peers = membershipList.getRandomPeers(5);

            assertEquals(1, peers.size());
        }

        @Test
        void getRandomPeersShouldReturnEmptyWhenOnlySelf() {
            List<NodeInfo> peers = membershipList.getRandomPeers(3);

            assertTrue(peers.isEmpty());
        }

        @Test
        void getRandomPeersShouldReturnUnmodifiableList() {
            membershipList.merge(new NodeInfo("node-2", "10.0.0.2", 8080, 7946));

            List<NodeInfo> peers = membershipList.getRandomPeers(3);
            NodeInfo extraNode = new NodeInfo("hacker", "evil", 666, 666);

            assertThrows(UnsupportedOperationException.class,
                    () -> peers.add(extraNode));
        }

        @Test
        void getDigestShouldContainAllNodes() {
            membershipList.merge(new NodeInfo("node-2", "10.0.0.2", 8080, 7946));
            membershipList.merge(new NodeInfo("node-3", "10.0.0.3", 8080, 7946));

            List<NodeInfo> digest = membershipList.getDigest();

            assertEquals(3, digest.size()); // self + 2 nodes
        }

        @Test
        void getDigestShouldIncludeDeadNodes() {
            NodeInfo dead = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.DEAD, 0, 0);
            membershipList.merge(dead);

            List<NodeInfo> digest = membershipList.getDigest();
            List<String> digestIds = digest.stream().map(NodeInfo::getCacheNodeId).toList();

            assertTrue(digestIds.contains("node-2"), "Digest should include dead nodes");
        }

        @Test
        void getDigestShouldReturnUnmodifiableList() {
            List<NodeInfo> digest = membershipList.getDigest();
            NodeInfo extraNode = new NodeInfo("hacker", "evil", 666, 666);

            assertThrows(UnsupportedOperationException.class,
                    () -> digest.add(extraNode));
        }
    }

    // ── Failure Detection ──

    @Nested
    class FailureDetectionTests {

        @Test
        void markSuspectShouldChangeAliveToSuspect() {
            membershipList.merge(new NodeInfo("node-2", "10.0.0.2", 8080, 7946));

            membershipList.markSuspect("node-2");

            assertEquals(Status.SUSPECT, membershipList.getNode("node-2").getStatus());
        }

        @Test
        void markSuspectShouldUpdateLastUpdatedTime() {
            membershipList.merge(new NodeInfo("node-2", "10.0.0.2", 8080, 7946));

            long timeBefore = System.currentTimeMillis();
            membershipList.markSuspect("node-2");
            long timeAfter = System.currentTimeMillis();

            long updated = membershipList.getNode("node-2").getLastUpdatedTime();
            assertTrue(updated >= timeBefore);
            assertTrue(updated <= timeAfter);
        }

        @Test
        void markSuspectShouldNotChangeSuspectNode() {
            NodeInfo suspect = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.SUSPECT, 0, 0);
            membershipList.merge(suspect);

            long timeBefore = membershipList.getNode("node-2").getLastUpdatedTime();

            membershipList.markSuspect("node-2");

            assertEquals(Status.SUSPECT, membershipList.getNode("node-2").getStatus());
            assertEquals(timeBefore, membershipList.getNode("node-2").getLastUpdatedTime());
        }

        @Test
        void markSuspectShouldNotChangeDeadNode() {
            NodeInfo dead = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.DEAD, 0, 0);
            membershipList.merge(dead);

            membershipList.markSuspect("node-2");

            assertEquals(Status.DEAD, membershipList.getNode("node-2").getStatus());
        }

        @Test
        void markSuspectShouldDoNothingForUnknownNode() {
            assertDoesNotThrow(() -> membershipList.markSuspect("unknown-node"));
        }

        @Test
        void markDeadShouldChangeAliveOrSuspectToDead() {
            membershipList.merge(new NodeInfo("node-2", "10.0.0.2", 8080, 7946));

            membershipList.markDead("node-2");

            assertEquals(Status.DEAD, membershipList.getNode("node-2").getStatus());
        }

        @Test
        void markDeadShouldChangeSuspectToDead() {
            NodeInfo suspect = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.SUSPECT, 0, 0);
            membershipList.merge(suspect);

            membershipList.markDead("node-2");

            assertEquals(Status.DEAD, membershipList.getNode("node-2").getStatus());
        }

        @Test
        void markDeadShouldNotChangeAlreadyDeadNode() {
            NodeInfo dead = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.DEAD, 0, 0);
            membershipList.merge(dead);

            long timeBefore = membershipList.getNode("node-2").getLastUpdatedTime();

            membershipList.markDead("node-2");

            assertEquals(timeBefore, membershipList.getNode("node-2").getLastUpdatedTime());
        }

        @Test
        void markDeadShouldDoNothingForUnknownNode() {
            assertDoesNotThrow(() -> membershipList.markDead("unknown-node"));
        }

        @Test
        void getNodesForSuspectCheckShouldReturnAliveAndSuspect() {
            membershipList.merge(new NodeInfo("node-2", "10.0.0.2", 8080, 7946)); // ALIVE
            NodeInfo suspect = NodeInfo.getInstance(
                    "node-3", "10.0.0.3", 8080, 7946,
                    Status.SUSPECT, 0, 0);
            membershipList.merge(suspect);
            NodeInfo dead = NodeInfo.getInstance(
                    "node-4", "10.0.0.4", 8080, 7946,
                    Status.DEAD, 0, 0);
            membershipList.merge(dead);

            List<NodeInfo> result = membershipList.getNodesForSuspectCheck();
            List<String> resultIds = result.stream().map(NodeInfo::getCacheNodeId).toList();

            assertEquals(2, result.size());
            assertTrue(resultIds.contains("node-2"), "Should contain ALIVE node");
            assertTrue(resultIds.contains("node-3"), "Should contain SUSPECT node");
        }

        @Test
        void getNodesForSuspectCheckShouldExcludeSelf() {
            List<NodeInfo> result = membershipList.getNodesForSuspectCheck();
            List<String> resultIds = result.stream().map(NodeInfo::getCacheNodeId).toList();

            assertFalse(resultIds.contains("self-node"), "Should exclude self node");
        }

        @Test
        void getNodesForSuspectCheckShouldExcludeDeadNodes() {
            NodeInfo dead = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.DEAD, 0, 0);
            membershipList.merge(dead);

            List<NodeInfo> result = membershipList.getNodesForSuspectCheck();

            assertTrue(result.isEmpty());
        }
    }

    // ── Queries ──

    @Nested
    class QueryTests {

        @Test
        void getNodeShouldReturnExistingNode() {
            membershipList.merge(new NodeInfo("node-2", "10.0.0.2", 8080, 7946));

            assertNotNull(membershipList.getNode("node-2"));
            assertEquals("node-2", membershipList.getNode("node-2").getCacheNodeId());
        }

        @Test
        void getNodeShouldReturnNullForUnknown() {
            assertNull(membershipList.getNode("unknown-node"));
        }

        @Test
        void getAliveNodesShouldReturnOnlyAliveNodes() {
            membershipList.merge(new NodeInfo("node-2", "10.0.0.2", 8080, 7946)); // ALIVE
            NodeInfo suspect = NodeInfo.getInstance(
                    "node-3", "10.0.0.3", 8080, 7946,
                    Status.SUSPECT, 0, 0);
            membershipList.merge(suspect);
            NodeInfo dead = NodeInfo.getInstance(
                    "node-4", "10.0.0.4", 8080, 7946,
                    Status.DEAD, 0, 0);
            membershipList.merge(dead);

            List<NodeInfo> aliveNodes = membershipList.getAliveNodes();
            List<Status> statuses = aliveNodes.stream().map(NodeInfo::getStatus).toList();

            assertEquals(2, aliveNodes.size()); // self + node-2
            assertFalse(statuses.contains(Status.SUSPECT), "Should not contain SUSPECT nodes");
            assertFalse(statuses.contains(Status.DEAD), "Should not contain DEAD nodes");
        }

        @Test
        void getAliveNodesShouldIncludeSelf() {
            List<NodeInfo> aliveNodes = membershipList.getAliveNodes();

            assertEquals(1, aliveNodes.size());
            assertEquals("self-node", aliveNodes.getFirst().getCacheNodeId());
        }

        @Test
        void sizeShouldReturnTotalNodeCount() {
            assertEquals(1, membershipList.size()); // self only

            membershipList.merge(new NodeInfo("node-2", "10.0.0.2", 8080, 7946));
            assertEquals(2, membershipList.size());

            membershipList.merge(new NodeInfo("node-3", "10.0.0.3", 8080, 7946));
            assertEquals(3, membershipList.size());
        }

        @Test
        void sizeShouldCountDeadNodes() {
            NodeInfo dead = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.DEAD, 0, 0);
            membershipList.merge(dead);

            assertEquals(2, membershipList.size()); // self + dead node
        }

        @Test
        void getAliveNodesShouldExcludeDrainingNodes() {
            NodeInfo draining = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.DRAINING, 0, 0);
            membershipList.merge(draining);

            List<NodeInfo> aliveNodes = membershipList.getAliveNodes();
            List<String> aliveIds = aliveNodes.stream().map(NodeInfo::getCacheNodeId).toList();

            assertEquals(1, aliveNodes.size()); // only self
            assertFalse(aliveIds.contains("node-2"), "DRAINING nodes should be excluded from alive list");
        }
    }

    // ── Drain Operations ──

    @Nested
    class DrainTests {

        @Test
        void markDrainingShouldChangeAliveNodeToDraining() {
            membershipList.merge(new NodeInfo("node-2", "10.0.0.2", 8080, 7946));

            membershipList.markDraining("node-2");

            assertEquals(Status.DRAINING, membershipList.getNode("node-2").getStatus());
        }

        @Test
        void cancelDrainingShouldChangeDrainingNodeToAlive() {
            membershipList.merge(new NodeInfo("node-2", "10.0.0.2", 8080, 7946));
            membershipList.markDraining("node-2");

            membershipList.cancelDraining("node-2");

            assertEquals(Status.ALIVE, membershipList.getNode("node-2").getStatus());
        }

        @Test
        void markDrainingShouldDoNothingForUnknownNode() {
            assertDoesNotThrow(() -> membershipList.markDraining("unknown-node"));
        }

        @Test
        void cancelDrainingShouldDoNothingForUnknownNode() {
            assertDoesNotThrow(() -> membershipList.cancelDraining("unknown-node"));
        }

        @Test
        void getNodesForSuspectCheckShouldIncludeDrainingNodes() {
            NodeInfo draining = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.DRAINING, 0, 0);
            membershipList.merge(draining);

            List<NodeInfo> result = membershipList.getNodesForSuspectCheck();
            List<String> resultIds = result.stream().map(NodeInfo::getCacheNodeId).toList();

            assertTrue(resultIds.contains("node-2"), "Should contain DRAINING node");
        }

        @Test
        void getRandomPeersShouldIncludeDrainingNodes() {
            NodeInfo draining = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.DRAINING, 0, 0);
            membershipList.merge(draining);

            List<NodeInfo> peers = membershipList.getRandomPeers(5);

            assertEquals(1, peers.size());
            assertEquals("node-2", peers.getFirst().getCacheNodeId());
        }
    }

    // ── Topology Event Publishing ──

    @Nested
    class TopologyEventTests {

        private List<TopologyChangedEvent> publishedEvents;

        @BeforeEach
        void setUpPublisher() {
            publishedEvents = new ArrayList<>();
            ApplicationEventPublisher publisher = event -> {
                if (event instanceof TopologyChangedEvent tce) {
                    publishedEvents.add(tce);
                }
            };
            membershipList.setEventPublisher(publisher);
        }

        @Test
        void shouldPublishEventWithAddedNodeWhenNewAliveNodeMerged() {
            NodeInfo newNode = new NodeInfo("node-2", "10.0.0.2", 8080, 7946);

            membershipList.merge(newNode);

            assertEquals(1, publishedEvents.size());
            TopologyChangedEvent event = publishedEvents.getFirst();
            assertTrue(event.getAddedNodes().contains(newNode));
            assertTrue(event.getRemovedNodes().isEmpty());
        }

        @Test
        void shouldPublishEventWithRemovedNodeWhenExistingNodeTransitionsToDead() {
            NodeInfo node = new NodeInfo("node-2", "10.0.0.2", 8080, 7946);
            membershipList.merge(node);
            publishedEvents.clear();

            // Merge a DEAD version of the same node with higher incarnation
            NodeInfo deadNode = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.DEAD, 0, 1);
            membershipList.merge(deadNode);

            assertEquals(1, publishedEvents.size());
            TopologyChangedEvent event = publishedEvents.getFirst();
            assertTrue(event.getRemovedNodes().stream()
                    .anyMatch(n -> n.getCacheNodeId().equals("node-2")));
            assertTrue(event.getAddedNodes().isEmpty());
        }

        @Test
        void shouldPublishEventWhenMarkDeadCalled() {
            NodeInfo node = new NodeInfo("node-2", "10.0.0.2", 8080, 7946);
            membershipList.merge(node);
            publishedEvents.clear();

            membershipList.markDead("node-2");

            assertEquals(1, publishedEvents.size());
            TopologyChangedEvent event = publishedEvents.getFirst();
            assertTrue(event.getRemovedNodes().stream()
                    .anyMatch(n -> n.getCacheNodeId().equals("node-2")));
        }

        @Test
        void shouldPublishEventWhenMarkAliveCalled() {
            // First, make node SUSPECT so markAlive has a transition to perform
            NodeInfo suspect = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.SUSPECT, 0, 0);
            membershipList.merge(suspect);
            publishedEvents.clear();

            membershipList.markAlive("node-2");

            assertEquals(1, publishedEvents.size());
            TopologyChangedEvent event = publishedEvents.getFirst();
            assertTrue(event.getAddedNodes().stream()
                    .anyMatch(n -> n.getCacheNodeId().equals("node-2")));
            assertTrue(event.getRemovedNodes().isEmpty());
        }

        @Test
        void shouldNotPublishEventWhenEventPublisherIsNull() {
            membershipList.setEventPublisher(null);

            NodeInfo newNode = new NodeInfo("node-2", "10.0.0.2", 8080, 7946);

            assertDoesNotThrow(() -> membershipList.merge(newNode));
            // No NPE should occur, and publishedEvents should remain empty
            assertTrue(publishedEvents.isEmpty());
        }

        @Test
        void shouldNotPublishTopologyEventForSelfGossip() {
            NodeInfo incomingSelf = NodeInfo.getInstance(
                    "self-node", "127.0.0.1", 8080, 7946,
                    Status.SUSPECT, 0, 0);

            membershipList.merge(incomingSelf);

            assertTrue(publishedEvents.isEmpty(),
                    "Self gossip should not trigger topology events");
        }

        @Test
        void shouldNotPublishTopologyEventForSuspectTransition() {
            NodeInfo node = new NodeInfo("node-2", "10.0.0.2", 8080, 7946);
            membershipList.merge(node);
            publishedEvents.clear();

            membershipList.markSuspect("node-2");

            assertTrue(publishedEvents.isEmpty(),
                    "SUSPECT transitions should not trigger topology events");
        }

        @Test
        void shouldNotPublishEventWhenMergingSuspectOverAliveAtSameIncarnation() {
            NodeInfo alive = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.ALIVE, 5, 1);
            membershipList.merge(alive);
            publishedEvents.clear();

            // SUSPECT at same incarnation outranks ALIVE (accepted by merger)
            // but SUSPECT is not a topology event trigger
            NodeInfo suspect = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.SUSPECT, 5, 1);
            membershipList.merge(suspect);

            assertTrue(publishedEvents.isEmpty(),
                    "ALIVE->SUSPECT transition should not trigger topology event");
        }

        @Test
        void shouldIncludeAllAliveNodesInEvent() {
            NodeInfo node2 = new NodeInfo("node-2", "10.0.0.2", 8080, 7946);
            NodeInfo node3 = new NodeInfo("node-3", "10.0.0.3", 8080, 7946);
            membershipList.merge(node2);
            publishedEvents.clear();

            membershipList.merge(node3);

            assertEquals(1, publishedEvents.size());
            TopologyChangedEvent event = publishedEvents.getFirst();
            // allAliveNodes should include self-node, node-2, and node-3
            List<String> aliveIds = event.getAllAliveNodes().stream()
                    .map(NodeInfo::getCacheNodeId).toList();
            assertTrue(aliveIds.contains("self-node"));
            assertTrue(aliveIds.contains("node-2"));
            assertTrue(aliveIds.contains("node-3"));
        }

        @Test
        void shouldPublishEventWhenDeadNodeRejoinsAsAlive() {
            NodeInfo node = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.DEAD, 0, 1);
            membershipList.merge(node);
            publishedEvents.clear();

            // Node rejoins with higher incarnation as ALIVE
            NodeInfo rejoin = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.ALIVE, 5, 2);
            membershipList.merge(rejoin);

            assertEquals(1, publishedEvents.size());
            TopologyChangedEvent event = publishedEvents.getFirst();
            assertTrue(event.getAddedNodes().stream()
                    .anyMatch(n -> n.getCacheNodeId().equals("node-2")));
        }

        @Test
        void shouldNotPublishEventForMarkDeadOnSelf() {
            membershipList.markDead("self-node");

            assertTrue(publishedEvents.isEmpty(),
                    "markDead on self should not publish topology event");
        }

        @Test
        void shouldNotPublishEventForMarkAliveOnSelf() {
            // First make self suspect so markAlive would transition
            membershipList.getSelf().setStatus(Status.SUSPECT);
            publishedEvents.clear();

            membershipList.markAlive("self-node");

            assertTrue(publishedEvents.isEmpty(),
                    "markAlive on self should not publish topology event");
        }

        @Test
        void shouldNotPublishEventWhenMergingNewDeadNode() {
            // A newly discovered node that's already DEAD should not trigger addedNodes event
            NodeInfo deadNode = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.DEAD, 0, 0);

            membershipList.merge(deadNode);

            assertTrue(publishedEvents.isEmpty(),
                    "Merging a new DEAD node should not trigger topology event");
        }

        @Test
        void shouldNotPublishEventWhenMergingExistingNodeWithSameStatus() {
            // Merge accepted (higher heartbeat) but status did not change — no topology event
            NodeInfo alive = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.ALIVE, 5, 1);
            membershipList.merge(alive);
            publishedEvents.clear();

            // Higher heartbeat, same status — merger accepts but no status change
            NodeInfo fresher = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.ALIVE, 10, 1);
            membershipList.merge(fresher);

            assertTrue(publishedEvents.isEmpty(),
                    "Heartbeat-only update must not publish a topology event");
        }

        @Test
        void shouldNotPublishEventWhenMergeIsRejected() {
            // Merge rejected (stale incarnation) — absolutely no topology event
            NodeInfo node = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.ALIVE, 5, 3);
            membershipList.merge(node);
            publishedEvents.clear();

            NodeInfo stale = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.DEAD, 10, 2); // lower incarnation — rejected
            membershipList.merge(stale);

            assertTrue(publishedEvents.isEmpty(),
                    "Rejected merge must not publish a topology event");
        }
    }

    // ── markDirty ──

    @Nested
    class MarkDirtyTests {

        @Test
        void markDirtyShouldMarkExistingNodeDirty() {
            // Drain dirty list first, then mark dirty and confirm it shows up
            membershipList.getDirtyDigestAndClear();

            membershipList.markDirty("self-node");

            List<NodeInfo> dirty = membershipList.getDirtyDigestAndClear();
            assertTrue(dirty.stream().anyMatch(n -> n.getCacheNodeId().equals("self-node")),
                    "self-node should appear in the dirty digest after markDirty");
        }

        @Test
        void markDirtyShouldDoNothingForUnknownNode() {
            // Drain first so list is clean
            membershipList.getDirtyDigestAndClear();

            // markDirty on a node that is NOT in the store should be silently ignored
            assertDoesNotThrow(() -> membershipList.markDirty("unknown-node"));

            List<NodeInfo> dirty = membershipList.getDirtyDigestAndClear();
            assertTrue(dirty.isEmpty(),
                    "Dirty list must remain empty when markDirty targets an unknown node");
        }

        @Test
        void getDirtyDigestAndClearShouldReturnAndClearDirtyNodes() {
            membershipList.bumpSelfHeartbeat(); // marks self dirty

            List<NodeInfo> firstCall = membershipList.getDirtyDigestAndClear();
            assertFalse(firstCall.isEmpty(), "First call should return dirty nodes");

            List<NodeInfo> secondCall = membershipList.getDirtyDigestAndClear();
            assertTrue(secondCall.isEmpty(), "Second call should return empty after clear");
        }
    }

    // ── Additional merge topology-event branches ──

    @Nested
    class MergeTopologyEdgeCases {

        @Test
        void mergeShouldPublishTopologyEventWhenDeadNodeComesAlive() {
            // We wire an event publisher to capture events
            List<TopologyChangedEvent> events = new ArrayList<>();
            membershipList.setEventPublisher(event -> {
                if (event instanceof TopologyChangedEvent tce) events.add(tce);
            });

            // Add node as DEAD
            NodeInfo deadNode = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.DEAD, 0, 1);
            membershipList.merge(deadNode);
            events.clear(); // ignore the non-topology event for initial merge

            // Rejoin as ALIVE with higher incarnation → should publish event
            NodeInfo rejoined = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.ALIVE, 1, 2);
            membershipList.merge(rejoined);

            assertEquals(1, events.size(), "Rejoining node should publish exactly one topology event");
            assertTrue(events.getFirst().getAddedNodes().stream()
                    .anyMatch(n -> n.getCacheNodeId().equals("node-2")));
        }

        @Test
        void mergeShouldPublishRemovedEventWhenAliveTransitionsToDead() {
            List<TopologyChangedEvent> events = new ArrayList<>();
            membershipList.setEventPublisher(event -> {
                if (event instanceof TopologyChangedEvent tce) events.add(tce);
            });

            // Start with ALIVE node
            NodeInfo aliveNode = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.ALIVE, 0, 1);
            membershipList.merge(aliveNode);
            events.clear();

            // Merge DEAD version with higher incarnation
            NodeInfo deadNode = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.DEAD, 0, 2);
            membershipList.merge(deadNode);

            assertEquals(1, events.size());
            assertTrue(events.getFirst().getRemovedNodes().stream()
                    .anyMatch(n -> n.getCacheNodeId().equals("node-2")));
            assertTrue(events.getFirst().getAddedNodes().isEmpty());
        }

        @Test
        void mergeShouldNotPublishEventWhenSuspectTransitionsFromAlive() {
            List<TopologyChangedEvent> events = new ArrayList<>();
            membershipList.setEventPublisher(event -> {
                if (event instanceof TopologyChangedEvent tce) events.add(tce);
            });

            NodeInfo aliveNode = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.ALIVE, 5, 1);
            membershipList.merge(aliveNode);
            events.clear();

            // SUSPECT at same incarnation (higher severity) — accepted but not a topology event
            NodeInfo suspect = NodeInfo.getInstance(
                    "node-2", "10.0.0.2", 8080, 7946,
                    Status.SUSPECT, 5, 1);
            membershipList.merge(suspect);

            assertTrue(events.isEmpty(),
                    "ALIVE->SUSPECT transition via merge must not publish topology event");
        }
    }

    // ── cancelDraining: incarnation bumped (transitionFromDrainingToAlive) ──

    @Nested
    class CancelDrainingIncarnationTest {

        @Test
        void cancelDrainingShouldBumpIncarnation() {
            membershipList.merge(new NodeInfo("node-2", "10.0.0.2", 8080, 7946));
            membershipList.markDraining("node-2");
            long incBefore = membershipList.getNode("node-2").getIncarnation();

            membershipList.cancelDraining("node-2");

            assertEquals(incBefore + 1, membershipList.getNode("node-2").getIncarnation(),
                    "cancelDraining must bump incarnation so peers accept ALIVE over DRAINING");
        }

        @Test
        void cancelDrainingOnAliveNodeShouldNotTransition() {
            // node is ALIVE — cancelDraining should be a no-op (stateHandler returns false)
            membershipList.merge(new NodeInfo("node-2", "10.0.0.2", 8080, 7946));

            membershipList.cancelDraining("node-2");

            assertEquals(Status.ALIVE, membershipList.getNode("node-2").getStatus());
        }
    }
}

