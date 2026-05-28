package com.edgefabric.hashing.core;

import com.edgefabric.hashing.api.HashableNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConsistentHashRing — Behavior Tests")
class ConsistentHashingBehaviorTest {

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

    // ── Distribution Tests ────────────────────────────────────────────────────

    @Test
    @DisplayName("3 nodes, 10,000 keys — each node gets 25% to 45% of keys")
    void keysDistributeEvenlyAcrossThreeNodes() {
        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));
        ring.addNode(new TestNode("node-3"));

        Map<String, Integer> distribution = routeKeys(ring, 10_000);

        assertThat(distribution).hasSize(3);

        distribution.values().forEach(count ->
                assertThat(count)
                        .as("Each node should get between 25%% and 45%% of 10,000 keys")
                        .isBetween(2_500, 4_500)
        );
    }

    @Test
    @DisplayName("more virtual nodes → more even distribution (lower variance)")
    void moreVirtualNodesMeansLowerVariance() {

        ConsistentHashRing<TestNode> ringLow =
                buildRingWithNodes(10, "node-1", "node-2", "node-3");

        ConsistentHashRing<TestNode> ringHigh =
                buildRingWithNodes(500, "node-1", "node-2", "node-3");

        double varianceLow = variance(routeKeys(ringLow, 10_000));
        double varianceHigh = variance(routeKeys(ringHigh, 10_000));

        assertThat(varianceHigh)
                .as("500 virtual nodes should distribute more evenly than 10")
                .isLessThan(varianceLow);
    }

    // ── Minimal Remapping Tests ───────────────────────────────────────────────

    @Test
    @DisplayName("adding 1 node to 2 → at least 60% of keys stay on same node")
    void addingNodeCausesMinimalKeyRemapping() {

        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));

        String[] keys = generateKeys(1_000);

        Map<String, String> before = routeToNodeIds(ring, keys);

        ring.addNode(new TestNode("node-3"));

        Map<String, String> after = routeToNodeIds(ring, keys);

        long unchanged = countUnchanged(before, after, keys);

        double stabilityPct = (unchanged * 100.0) / keys.length;

        assertThat(stabilityPct)
                .as("At least 60%% of keys should stay when adding a node, got %.1f%%", stabilityPct)
                .isGreaterThanOrEqualTo(60.0);
    }

    @Test
    @DisplayName("removing 1 of 3 nodes → keys from other nodes NEVER move")
    void removingNodeOnlyMovesItsOwnKeys() {

        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));
        ring.addNode(new TestNode("node-3"));

        String[] keys = generateKeys(1_000);

        Map<String, String> before = routeToNodeIds(ring, keys);

        ring.removeNode(new TestNode("node-2"));

        Map<String, String> after = routeToNodeIds(ring, keys);

        int wronglyMoved = 0;

        for (String key : keys) {

            String nodeBefore = before.get(key);
            String nodeAfter = after.get(key);

            if (!nodeBefore.equals("node-2") && !nodeBefore.equals(nodeAfter)) {
                wronglyMoved++;
            }
        }

        assertThat(wronglyMoved)
                .as("Keys NOT on node-2 must never move when node-2 is removed")
                .isZero();
    }

    @Test
    @DisplayName("removing node → its keys go to remaining nodes (not lost)")
    void removedNodeKeysAreRedistributed() {

        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));
        ring.addNode(new TestNode("node-3"));

        String[] keys = generateKeys(1_000);

        Map<String, String> before = routeToNodeIds(ring, keys);

        long keysOnNode2 = before.values().stream()
                .filter("node-2"::equals)
                .count();

        ring.removeNode(new TestNode("node-2"));

        Map<String, String> after = routeToNodeIds(ring, keys);

        assertThat(after.values()).doesNotContain("node-2");

        long keysMovedFromNode2 = 0;

        for (String key : keys) {

            if ("node-2".equals(before.get(key))) {

                String newNode = after.get(key);

                assertThat(newNode).isIn("node-1", "node-3");

                keysMovedFromNode2++;
            }
        }

        assertThat(keysMovedFromNode2).isEqualTo(keysOnNode2);
    }

    // ── Concurrency Tests ─────────────────────────────────────────────────────

    @Test
    @DisplayName("100 threads read same key simultaneously → all get same node")
    void concurrentReadsSameKeyAreConsistent() throws InterruptedException {

        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));
        ring.addNode(new TestNode("node-3"));

        String testKey = "concurrent-test-key";
        String expectedId = ring.getNode(testKey).getNodeId();

        int threadCount = 100;
        String[] results = new String[threadCount];

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {

            final int index = i;

            executor.submit(() -> {
                try {
                    startGate.await();
                    results[index] = ring.getNode(testKey).getNodeId();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        startGate.countDown();

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        for (int i = 0; i < threadCount; i++) {

            assertThat(results[i])
                    .as("Thread %d got wrong result", i)
                    .isEqualTo(expectedId);
        }
    }

    @Test
    @DisplayName("concurrent reads and writes — no exception, ring stays consistent")
    void concurrentReadsAndWritesDoNotCorrupt() throws InterruptedException {

        ring.addNode(new TestNode("node-1"));
        ring.addNode(new TestNode("node-2"));

        int readers = 50;
        int writers = 5;

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(readers + writers);
        CountDownLatch nodeAdded = new CountDownLatch(writers);

        AtomicInteger errors = new AtomicInteger(0);

        ExecutorService executor =
                Executors.newFixedThreadPool(readers + writers);

        // Readers
        for (int i = 0; i < readers; i++) {

            final int readerId = i;

            executor.submit(() -> {
                try {
                    startGate.await();

                    for (int j = 0; j < 100; j++) {

                        TestNode result =
                                ring.getNode("key:" + readerId + ":" + j);

                        if (result == null) {
                            errors.incrementAndGet();
                        }
                    }

                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    allDone.countDown();
                }
            });
        }

        // Writers
        for (int i = 0; i < writers; i++) {

            final int writerId = i;

            executor.submit(() -> {

                try {
                    startGate.await();

                    TestNode dynamicNode =
                            new TestNode("dynamic-" + writerId);

                    ring.addNode(dynamicNode);

                    nodeAdded.countDown();

                    nodeAdded.await();

                    ring.removeNode(dynamicNode);

                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    allDone.countDown();
                }
            });
        }

        startGate.countDown();

        boolean finished = allDone.await(10, TimeUnit.SECONDS);

        executor.shutdown();

        assertThat(finished)
                .as("All threads should complete within 10 seconds")
                .isTrue();

        assertThat(errors.get())
                .as("No errors should occur during concurrent read/write")
                .isZero();
    }

    // ── Helper Methods ────────────────────────────────────────────────────────

    private ConsistentHashRing<TestNode> buildRingWithNodes(
            int virtualNodes,
            String... nodeIds
    ) {

        ConsistentHashRing<TestNode> r =
                new ConsistentHashRing<>(new MurmurHashProvider(), virtualNodes);

        for (String id : nodeIds) {
            r.addNode(new TestNode(id));
        }

        return r;
    }

    private String[] generateKeys(int count) {

        String[] keys = new String[count];

        for (int i = 0; i < count; i++) {
            keys[i] = "key:" + i;
        }

        return keys;
    }

    private Map<String, Integer> routeKeys(
            ConsistentHashRing<TestNode> r,
            int count
    ) {

        Map<String, Integer> dist = new HashMap<>();

        for (int i = 0; i < count; i++) {

            String nodeId = r.getNode("key:" + i).getNodeId();

            dist.merge(nodeId, 1, Integer::sum);
        }

        return dist;
    }

    private Map<String, String> routeToNodeIds(
            ConsistentHashRing<TestNode> r,
            String[] keys
    ) {

        Map<String, String> mapping = new HashMap<>();

        for (String key : keys) {

            mapping.put(key, r.getNode(key).getNodeId());
        }

        return mapping;
    }

    private long countUnchanged(
            Map<String, String> before,
            Map<String, String> after,
            String[] keys
    ) {

        long count = 0;

        for (String key : keys) {

            if (before.get(key).equals(after.get(key))) {
                count++;
            }
        }

        return count;
    }

    private double variance(Map<String, Integer> distribution) {

        double mean =
                distribution.values().stream()
                        .mapToInt(i -> i)
                        .average()
                        .orElse(0);

        return distribution.values().stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0);
    }
}