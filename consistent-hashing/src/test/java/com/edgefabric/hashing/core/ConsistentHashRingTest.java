package com.edgefabric.hashing.core;

import com.edgefabric.hashing.api.HashableNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ConsistentHashRing — Unit Tests")
class ConsistentHashRingTest {

    record TestNode(String nodeId) implements HashableNode {
        @Override
        public String getNodeId() {
            return nodeId;
        }
    }

    private ConsistentHashRing<TestNode> ring;

    @BeforeEach
    void setUp() {
        ring = new ConsistentHashRing<>(new MurmurHashProvider(), 150);
    }

    // ── Constructor ─────────────────────────────────────────

    @Test
    @DisplayName("null hashProvider → IllegalArgumentException")
    void nullHashProviderThrows() {

        MurmurHashProvider provider = null;

        assertThatThrownBy(() -> new ConsistentHashRing<>(provider, 150))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("virtualNodes = 0 → IllegalArgumentException mentioning the value")
    void zeroVirtualNodesThrows() {

        int virtualNodes = 0;

        MurmurHashProvider provider = new MurmurHashProvider();

        assertThatThrownBy(() ->
                new ConsistentHashRing<>(provider, virtualNodes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0");
    }

    @Test
    @DisplayName("hash greater than last node wraps to first node")
    void getNodeWrapsAroundRing() {

        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));

        // very large key likely beyond last hash
        TestNode node = ring.getNode("zzzzzzzzzzzzzzzz");

        assertThat(node).isNotNull();
    }

    @Test
    @DisplayName("size and nodeCount become zero after removing all nodes")
    void sizeAndNodeCountAfterRemovingAllNodes() {

        TestNode node = new TestNode("node-1");

        ring.addNode(node);
        ring.removeNode(node);

        assertThat(ring.size()).isZero();
        assertThat(ring.nodeCount()).isZero();
        assertThat(ring.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("getActiveNodeIds returns empty set when ring empty")
    void getActiveNodeIdsEmptyWhenRingEmpty() {

        assertThat(ring.getActiveNodeIds()).isEmpty();
    }


    @Test
    @DisplayName("virtualNodes negative → IllegalArgumentException")
    void negativeVirtualNodesThrows() {

        int virtualNodes = -50;

        MurmurHashProvider provider = new MurmurHashProvider();

        assertThatThrownBy(() ->
                new ConsistentHashRing<>(provider, virtualNodes))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("valid constructor → ring starts empty")
    void validConstructorCreatesEmptyRing() {

        assertThat(ring.isEmpty()).isTrue();
        assertThat(ring.size()).isZero();
        assertThat(ring.nodeCount()).isZero();
        assertThat(ring.getActiveNodeIds()).isEmpty();
    }

    // ── addNode ─────────────────────────────────────────────

    @Test
    @DisplayName("addNode(null) → IllegalArgumentException")
    void addNullNodeThrows() {

        assertThatThrownBy(() -> ring.addNode(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("addNode → ring grows by virtualNodes (150)")
    void addNodeIncreasesRingSizeByVirtualNodes() {

        ring.addNode(new TestNode("node-1"));

        assertThat(ring.size()).isEqualTo(150);
    }

    @Test
    @DisplayName("addNode twice → ring grows by 2 × virtualNodes")
    void twoAddNodeDoublesSizeByVirtualNodes() {

        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));

        assertThat(ring.size()).isEqualTo(300);
    }

    @Test
    @DisplayName("addNode → nodeCount increases by 1")
    void addNodeIncrementsNodeCount() {

        assertThat(ring.nodeCount()).isZero();

        ring.addNode(new TestNode("node-1"));
        assertThat(ring.nodeCount()).isEqualTo(1);

        ring.addNode(new TestNode("node-2"));
        assertThat(ring.nodeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("addNode → isEmpty() becomes false")
    void addNodeMakesRingNotEmpty() {

        ring.addNode(new TestNode("node-1"));

        assertThat(ring.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("addNode same node twice → size unchanged (idempotent)")
    void addSameNodeTwiceIdempotentOnSize() {

        TestNode node = new TestNode("node-1");

        ring.addNode(node);
        int sizeAfterFirst = ring.size();

        ring.addNode(node);

        assertThat(ring.size()).isEqualTo(sizeAfterFirst);
    }

    @Test
    @DisplayName("addNode same node twice → nodeCount unchanged (idempotent)")
    void addSameNodeTwiceIdempotentOnNodeCount() {

        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-1"));

        assertThat(ring.nodeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("addNode same node twice → routing unchanged (idempotent)")
    void addSameNodeTwiceDoesNotChangeRouting() {

        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));

        String nodeBeforeDuplicate = ring.getNode("user:12345").getNodeId();

        ring.addNode(new TestNode("node-1"));

        String nodeAfterDuplicate = ring.getNode("user:12345").getNodeId();

        assertThat(nodeAfterDuplicate).isEqualTo(nodeBeforeDuplicate);
    }

    @Test
    @DisplayName("addNode → activeNodeIds contains the added nodeId")
    void addNodeAppearsInActiveNodeIds() {

        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));

        assertThat(ring.getActiveNodeIds())
                .containsExactlyInAnyOrder("node-1", "node-2");
    }

    // ── removeNode ──────────────────────────────────────────

    @Test
    @DisplayName("removeNode(null) → IllegalArgumentException")
    void removeNullNodeThrows() {

        assertThatThrownBy(() -> ring.removeNode(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("removeNode → size decreases by virtualNodes")
    void removeNodeDecreasesSizeByVirtualNodes() {

        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));

        assertThat(ring.size()).isEqualTo(300);

        ring.removeNode(new TestNode("node-1"));

        assertThat(ring.size()).isEqualTo(150);
    }

    @Test
    @DisplayName("removeNode → nodeCount decreases by 1")
    void removeNodeDecrementsNodeCount() {

        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));

        ring.removeNode(new TestNode("node-1"));

        assertThat(ring.nodeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("removeNode node not in ring → nothing changes (idempotent)")
    void removeAbsentNodeDoesNothing() {

        ring.addNode(new TestNode("node-1"));

        ring.removeNode(new TestNode("node-99"));

        assertThat(ring.size()).isEqualTo(150);
        assertThat(ring.nodeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("removeNode same node twice → nothing breaks (idempotent)")
    void removeSameNodeTwiceIsSafe() {

        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));

        ring.removeNode(new TestNode("node-1"));
        int sizeAfterFirst = ring.size();

        ring.removeNode(new TestNode("node-1"));

        assertThat(ring.size()).isEqualTo(sizeAfterFirst);
    }

    @Test
    @DisplayName("removed node is never returned by getNode")
    void removedNodeIsNeverRouted() {

        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));
        ring.addNode(new TestNode("node-3"));

        ring.removeNode(new TestNode("node-2"));

        for (int i = 0; i < 500; i++) {

            TestNode result = ring.getNode("key:" + i);

            assertThat(result.getNodeId())
                    .as("key:%d should not route to removed node-2", i)
                    .isNotEqualTo("node-2");
        }
    }

    @Test
    @DisplayName("removeNode → removed nodeId not in activeNodeIds")
    void removeNodeDisappearsFromActiveNodeIds() {

        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));

        ring.removeNode(new TestNode("node-1"));

        assertThat(ring.getActiveNodeIds()).containsExactly("node-2");
        assertThat(ring.getActiveNodeIds()).doesNotContain("node-1");
    }

    // ── getNode ─────────────────────────────────────────────

    @Test
    @DisplayName("getNode on empty ring → IllegalStateException")
    void getNodeOnEmptyRingThrows() {

        assertThatThrownBy(() -> ring.getNode("any-key"))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("getNode invalid key → IllegalArgumentException")
    void invalidKeyThrows(String key) {

        ring.addNode(new TestNode("node-1"));

        assertThatThrownBy(() -> ring.getNode(key))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getNode returns non-null result")
    void getNodeReturnsNonNull() {

        ring.addNode(new TestNode("node-1"));

        assertThat(ring.getNode("user:123")).isNotNull();
    }

    @Test
    @DisplayName("same key → always same node (determinism)")
    void sameKeyAlwaysMapsToSameNode() {

        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));
        ring.addNode(new TestNode("node-3"));

        String firstResult = ring.getNode("user:12345").getNodeId();

        for (int i = 0; i < 20; i++) {

            assertThat(ring.getNode("user:12345").getNodeId())
                    .as("Call %d should return same node", i)
                    .isEqualTo(firstResult);
        }
    }

    @Test
    @DisplayName("single node ring → all keys route to that one node")
    void singleNodeGetsAllKeys() {

        ring.addNode(new TestNode("only-node"));

        for (int i = 0; i < 100; i++) {

            assertThat(ring.getNode("key:" + i).getNodeId())
                    .isEqualTo("only-node");
        }
    }

    @Test
    @DisplayName("routing still works after node removal")
    void routingWorksAfterRemoval() {

        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));
        ring.addNode(new TestNode("node-3"));

        ring.removeNode(new TestNode("node-2"));

        TestNode result = ring.getNode("user:999");

        assertThat(result).isNotNull();
        assertThat(result.getNodeId()).isIn("node-1", "node-3");
    }

    // ── Utility Methods ─────────────────────────────────────

    @Test
    @DisplayName("getActiveNodeIds returns unmodifiable set")
    void activeNodeIdsIsUnmodifiable() {

        ring.addNode(new TestNode("node-1"));

        Set<String> activeNodes = ring.getActiveNodeIds();

        assertThatThrownBy(() -> activeNodes.add("injected"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("getActiveNodeIds is a snapshot")
    void activeNodeIdsIsSnapshot() {

        ring.addNode(new TestNode("node-1"));

        var snapshot = ring.getActiveNodeIds();

        ring.addNode(new TestNode("node-2"));

        assertThat(snapshot)
                .containsExactly("node-1")
                .doesNotContain("node-2");
    }

    @Test
    @DisplayName("size() returns nodeCount × virtualNodes")
    void sizeEqualsnodeCountTimesVirtualNodes() {

        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));
        ring.addNode(new TestNode("node-3"));

        assertThat(ring.size()).isEqualTo(ring.nodeCount() * 150);
    }

    // ── getNodes (quorum replica lookup) ─────────────────────

    @Test
    @DisplayName("getNodes returns correct number of distinct physical nodes")
    void getNodesReturnsDistinctPhysicalNodes() {
        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));
        ring.addNode(new TestNode("node-3"));

        List<TestNode> nodes = ring.getNodes("some-key", 3);

        assertThat(nodes).hasSize(3);
        assertThat(nodes.stream().map(TestNode::getNodeId).distinct().count()).isEqualTo(3);
    }

    @Test
    @DisplayName("getNodes returns fewer nodes when cluster has fewer than requested")
    void getNodesReturnsFewerWhenNotEnough() {
        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));

        List<TestNode> nodes = ring.getNodes("some-key", 5);

        assertThat(nodes).hasSize(2);
    }

    @Test
    @DisplayName("getNodes with count=1 returns same node as getNode")
    void getNodesSingleMatchesGetNode() {
        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));
        ring.addNode(new TestNode("node-3"));

        TestNode single = ring.getNode("test-key");
        List<TestNode> list = ring.getNodes("test-key", 1);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getNodeId()).isEqualTo(single.getNodeId());
    }

    @Test
    @DisplayName("getNodes(null key) → IllegalArgumentException")
    void getNodesNullKeyThrows() {
        ring.addNode(new TestNode("node-1"));

        assertThatThrownBy(() -> ring.getNodes(null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getNodes(count=0) → IllegalArgumentException")
    void getNodesZeroCountThrows() {
        ring.addNode(new TestNode("node-1"));

        assertThatThrownBy(() -> ring.getNodes("key", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getNodes on empty ring → Returns empty list")
    void getNodesEmptyRingReturnsEmpty() {
        assertThat(ring.getNodes("key", 1)).isEmpty();
    }

    @Test
    @DisplayName("getNodes returns nodes with primary node first")
    void getNodesFirstIsPrimary() {
        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));
        ring.addNode(new TestNode("node-3"));

        List<TestNode> nodes = ring.getNodes("key123", 3);
        TestNode primary = ring.getNode("key123");

        assertThat(nodes.get(0).getNodeId()).isEqualTo(primary.getNodeId());
    }

    // ── Additional Coverage Tests ─────────────────────────────

    @Test
    @DisplayName("getNodes with blank key → IllegalArgumentException")
    void getNodesBlankKeyThrows() {
        ring.addNode(new TestNode("node-1"));

        assertThatThrownBy(() -> ring.getNodes("   ", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getNodes with negative count → IllegalArgumentException")
    void getNodesNegativeCountThrows() {
        ring.addNode(new TestNode("node-1"));

        assertThatThrownBy(() -> ring.getNodes("key", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getNodes walks entire ring when needed (wrap around)")
    void getNodesWrapsAroundRing() {
        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));
        ring.addNode(new TestNode("node-3"));

        // Request all 3 nodes - should walk the ring and wrap around if necessary
        List<TestNode> nodes = ring.getNodes("wrap-test-key", 3);

        assertThat(nodes).hasSize(3);
        Set<String> nodeIds = new java.util.HashSet<>();
        for (TestNode n : nodes) {
            nodeIds.add(n.getNodeId());
        }
        assertThat(nodeIds).containsExactlyInAnyOrder("node-1", "node-2", "node-3");
    }

    @Test
    @DisplayName("lookupNodes returns empty list on empty ring")
    void lookupNodesEmptyRing() {
        List<TestNode> result = ring.getNodes("any-key", 5);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getNodes with single node returns that node")
    void getNodesSingleNodeCluster() {
        ring.addNode(new TestNode("only-node"));

        List<TestNode> nodes = ring.getNodes("test-key", 3);

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getNodeId()).isEqualTo("only-node");
    }

    @Test
    @DisplayName("getNodes count exceeds physical nodes returns all available")
    void getNodesCountExceedsAvailable() {
        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));

        List<TestNode> nodes = ring.getNodes("key", 10);

        assertThat(nodes).hasSize(2);
    }

    @Test
    @DisplayName("size returns correct value after multiple operations")
    void sizeAfterMultipleOperations() {
        ring.addNode(new TestNode("node-1"));
        assertThat(ring.size()).isEqualTo(150);

        ring.addNode(new TestNode("node-2"));
        assertThat(ring.size()).isEqualTo(300);

        ring.removeNode(new TestNode("node-1"));
        assertThat(ring.size()).isEqualTo(150);

        ring.removeNode(new TestNode("node-2"));
        assertThat(ring.size()).isZero();
    }

    @Test
    @DisplayName("nodeCount returns correct value under various conditions")
    void nodeCountUnderVariousConditions() {
        assertThat(ring.nodeCount()).isZero();

        ring.addNode(new TestNode("node-1"));
        assertThat(ring.nodeCount()).isEqualTo(1);

        ring.addNode(new TestNode("node-2"));
        ring.addNode(new TestNode("node-3"));
        assertThat(ring.nodeCount()).isEqualTo(3);

        ring.removeNode(new TestNode("node-2"));
        assertThat(ring.nodeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("isEmpty returns correct value after operations")
    void isEmptyAfterOperations() {
        assertThat(ring.isEmpty()).isTrue();

        ring.addNode(new TestNode("node-1"));
        assertThat(ring.isEmpty()).isFalse();

        ring.addNode(new TestNode("node-2"));
        assertThat(ring.isEmpty()).isFalse();

        ring.removeNode(new TestNode("node-1"));
        assertThat(ring.isEmpty()).isFalse();

        ring.removeNode(new TestNode("node-2"));
        assertThat(ring.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("getActiveNodeIds returns correct set after operations")
    void getActiveNodeIdsAfterOperations() {
        assertThat(ring.getActiveNodeIds()).isEmpty();

        ring.addNode(new TestNode("node-1"));
        assertThat(ring.getActiveNodeIds()).containsExactly("node-1");

        ring.addNode(new TestNode("node-2"));
        ring.addNode(new TestNode("node-3"));
        assertThat(ring.getActiveNodeIds()).containsExactlyInAnyOrder("node-1", "node-2", "node-3");

        ring.removeNode(new TestNode("node-2"));
        assertThat(ring.getActiveNodeIds()).containsExactlyInAnyOrder("node-1", "node-3");
    }

    @Test
    @DisplayName("getNode with various key patterns returns consistent results")
    void getNodeConsistencyWithVariousKeys() {
        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));
        ring.addNode(new TestNode("node-3"));

        // Different key patterns
        String[] keys = {"user:123", "session:abc", "cache:xyz", "data:000", "key-with-special-chars!@#"};

        for (String key : keys) {
            TestNode first = ring.getNode(key);
            TestNode second = ring.getNode(key);
            assertThat(first.getNodeId()).isEqualTo(second.getNodeId());
        }
    }

    @Test
    @DisplayName("getNodes determinism - same key always returns same order")
    void getNodesDeterminism() {
        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));
        ring.addNode(new TestNode("node-3"));

        String testKey = "determinism-test";
        List<TestNode> first = ring.getNodes(testKey, 3);
        
        for (int i = 0; i < 10; i++) {
            List<TestNode> subsequent = ring.getNodes(testKey, 3);
            assertThat(subsequent).hasSize(first.size());
            for (int j = 0; j < first.size(); j++) {
                assertThat(subsequent.get(j).getNodeId()).isEqualTo(first.get(j).getNodeId());
            }
        }
    }

    @Test
    @DisplayName("concurrent reads during modifications")
    void concurrentReadsAndWrites() throws InterruptedException {
        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));

        // Simulate concurrent access
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                ring.size();
                ring.nodeCount();
                ring.isEmpty();
                ring.getActiveNodeIds();
                if (!ring.isEmpty()) {
                    ring.getNode("key-" + i);
                    ring.getNodes("key-" + i, 2);
                }
            }
        });

        Thread writer = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                ring.addNode(new TestNode("dynamic-node-" + i));
                ring.removeNode(new TestNode("dynamic-node-" + i));
            }
        });

        reader.start();
        writer.start();

        reader.join();
        writer.join();

        // Verify ring is in consistent state
        assertThat(ring.nodeCount()).isEqualTo(2);
        assertThat(ring.getActiveNodeIds()).containsExactlyInAnyOrder("node-1", "node-2");
    }

    @Test
    @DisplayName("getNodes skips virtual node duplicates correctly")
    void getNodesSkipsVirtualNodeDuplicates() {
        // With 150 virtual nodes per physical node, we have lots of duplicates
        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));

        // Request 2 nodes - should skip virtual duplicates
        List<TestNode> nodes = ring.getNodes("test-key", 2);

        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0).getNodeId()).isNotEqualTo(nodes.get(1).getNodeId());
    }

    @Test
    @DisplayName("ring handles keys that hash to exact node positions")
    void ringHandlesExactHashMatches() {
        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));

        // Test many keys to ensure some might hash near node positions
        for (int i = 0; i < 1000; i++) {
            TestNode node = ring.getNode("exact-hash-test-" + i);
            assertThat(node).isNotNull();
            assertThat(node.getNodeId()).isIn("node-1", "node-2");
        }
    }

    @Test
    @DisplayName("getNodes with count=2 returns exactly 2 distinct nodes")
    void getNodesCountTwoReturnsTwo() {
        ring.addNode(new TestNode("node-a"));
        ring.addNode(new TestNode("node-b"));
        ring.addNode(new TestNode("node-c"));

        List<TestNode> nodes = ring.getNodes("replica-test", 2);

        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0).getNodeId()).isNotEqualTo(nodes.get(1).getNodeId());
    }

    @Test
    @DisplayName("multiple getNodes calls with same params are consistent")
    void multipleGetNodesCallsConsistent() {
        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));
        ring.addNode(new TestNode("node-3"));

        String key = "consistency-key";
        int count = 2;

        List<TestNode> baseline = ring.getNodes(key, count);

        for (int i = 0; i < 100; i++) {
            List<TestNode> result = ring.getNodes(key, count);
            assertThat(result).isEqualTo(baseline);
        }
    }
}