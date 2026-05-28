package com.edgefabric.caching.migration;

import com.edgefabric.caching.event.TopologyChangedEvent;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.resolver.TtlCacheResolver;
import com.edgefabric.hashing.core.ConsistentHashRing;
import com.edgefabric.hashing.core.HashProviderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/**
 * Integration tests that wire a REAL ConsistentHashRing (XXHash, 150 virtual nodes)
 * to KeyMigrationService. A mock MigrationWorker captures migration plans so we can
 * assert ring-routing decisions without real HTTP infrastructure.
 *
 * These tests catch regressions in the RF-aware migration logic since they exercise
 * the actual hash-ring distribution rather than stubbing getNodes().
 */
@ExtendWith(MockitoExtension.class)
class MigrationIntegrationTest {

    @Mock private MigrationWorker  migrationWorker;
    @Mock private MembershipList   membershipList;

    private ConsistentHashRing<NodeInfoHashAdapter> ring;
    private Map<String, CacheItem>                  store;
    private MigrationProperties                     properties;
    private KeyMigrationService                     service;

    private NodeInfo createNode(String id, String host) {
        return new NodeInfo(id, host, 8082, 7946);
    }

    private CacheItem createItem(String data) {
        return new CacheItem(data.getBytes(), 60_000L, "text/plain", 1L);
    }

    @BeforeEach
    void setUp() {
        ring       = new ConsistentHashRing<>(HashProviderFactory.create("xxhash"), 150);
        store      = new ConcurrentHashMap<>();
        properties = new MigrationProperties();
        properties.setDebounceMs(50);
        service = new KeyMigrationService(store, ring, migrationWorker, membershipList, properties,
                new TtlCacheResolver());
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario A: RF=3 on a 3-node cluster → every node is a valid replica
    //             for every key → ZERO migrations regardless of topology event.
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class ThreeNodeClusterRF3 {

        @Test
        void nothingShouldMigrateOnAFullyReplicatedCluster() {
            NodeInfo self  = createNode("node-1", "10.0.0.1");
            NodeInfo node2 = createNode("node-2", "10.0.0.2");
            NodeInfo node3 = createNode("node-3", "10.0.0.3");
            when(membershipList.getSelf()).thenReturn(self);
            properties.setReplicationFactor(3);

            ring.addNode(new NodeInfoHashAdapter(self));
            ring.addNode(new NodeInfoHashAdapter(node2));
            ring.addNode(new NodeInfoHashAdapter(node3));

            for (int i = 0; i < 100; i++) {
                store.put("key-" + i, createItem("v" + i));
            }

            // Simulate node3 briefly disconnecting then rejoining
            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Set.of(node3), Collections.emptySet(),
                    List.of(self, node2, node3));

            service.onTopologyChanged(event);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    verify(migrationWorker, atLeastOnce()).cancelCurrentMigration());

            // RF=3 on 3 nodes: every key's replica set = {node-1, node-2, node-3}.
            // Self is always present → the plan is always empty → startMigration never called.
            await().during(300, TimeUnit.MILLISECONDS).atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(migrationWorker, never()).startMigration(any()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario B: RF=2 on a 2-node cluster → both nodes own every key → ZERO migrations.
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class TwoNodeClusterRF2 {

        @Test
        void nothingShouldMigrateWhenEveryKeyIsFullyReplicated() {
            NodeInfo self  = createNode("node-1", "10.0.0.1");
            NodeInfo node2 = createNode("node-2", "10.0.0.2");
            when(membershipList.getSelf()).thenReturn(self);
            properties.setReplicationFactor(2);

            ring.addNode(new NodeInfoHashAdapter(self));
            ring.addNode(new NodeInfoHashAdapter(node2));

            for (int i = 0; i < 50; i++) {
                store.put("k" + i, createItem("v" + i));
            }

            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Set.of(node2), Collections.emptySet(), List.of(self, node2));

            service.onTopologyChanged(event);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    verify(migrationWorker, atLeastOnce()).cancelCurrentMigration());

            await().during(300, TimeUnit.MILLISECONDS).atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(migrationWorker, never()).startMigration(any()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario C: RF=1 on a single-node cluster → self is the sole owner → ZERO migrations.
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class SingleNodeClusterRF1 {

        @Test
        void nothingShouldMigrateWhenSelfIsTheOnlyNode() {
            NodeInfo self = createNode("node-1", "10.0.0.1");
            when(membershipList.getSelf()).thenReturn(self);
            properties.setReplicationFactor(1);

            ring.addNode(new NodeInfoHashAdapter(self));

            for (int i = 0; i < 50; i++) {
                store.put("k" + i, createItem("v" + i));
            }

            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Set.of(self), Collections.emptySet(), List.of(self));

            service.onTopologyChanged(event);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    verify(migrationWorker, atLeastOnce()).cancelCurrentMigration());

            await().during(300, TimeUnit.MILLISECONDS).atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(migrationWorker, never()).startMigration(any()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario D: Empty store → no migration plan is ever submitted.
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class EmptyStore {

        @Test
        void noMigrationSubmittedWhenStoreIsEmpty() {
            NodeInfo self  = createNode("node-1", "10.0.0.1");
            NodeInfo node2 = createNode("node-2", "10.0.0.2");
            when(membershipList.getSelf()).thenReturn(self);
            properties.setReplicationFactor(1);

            ring.addNode(new NodeInfoHashAdapter(self));

            // Store intentionally empty

            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Set.of(node2), Collections.emptySet(), List.of(self, node2));

            service.onTopologyChanged(event);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    verify(migrationWorker, atLeastOnce()).cancelCurrentMigration());

            await().during(300, TimeUnit.MILLISECONDS).atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(migrationWorker, never()).startMigration(any()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario E: Empty ring after rebuild → migration is skipped gracefully.
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class EmptyRingAfterRebuild {

        @Test
        void migrationSkippedWhenAliveNodesListIsEmpty() {
            properties.setReplicationFactor(1);

            // No nodes pre-added to the ring — it stays empty after rebuild
            store.put("key1", createItem("v1"));

            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Collections.emptySet(), Collections.emptySet(),
                    Collections.emptyList()); // allAliveNodes is empty

            service.onTopologyChanged(event);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    verify(migrationWorker, atLeastOnce()).cancelCurrentMigration());

            await().during(300, TimeUnit.MILLISECONDS).atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(migrationWorker, never()).startMigration(any()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario F: RF=1, 2-node → 3-node scale-out.
    //             Correctness property: every key in the migration plan must NOT
    //             have self as the primary owner in the updated (post-join) ring.
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class ScaleOutCorrectness {

        @Test
        void everyMigratedKeyShouldNotBeOwnedBySelfInUpdatedRing() {
            NodeInfo self  = createNode("node-1", "10.0.0.1");
            NodeInfo node2 = createNode("node-2", "10.0.0.2");
            NodeInfo node3 = createNode("node-3", "10.0.0.3");
            when(membershipList.getSelf()).thenReturn(self);
            properties.setReplicationFactor(1);

            ring.addNode(new NodeInfoHashAdapter(self));
            ring.addNode(new NodeInfoHashAdapter(node2));

            // Seed only the keys that belong to self in the 2-node ring
            for (int i = 0; i < 300; i++) {
                String key = "item-" + i;
                if (ring.getNode(key).getNodeId().equals("node-1")) {
                    store.put(key, createItem("v" + i));
                }
            }

            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Set.of(node3), Collections.emptySet(),
                    List.of(self, node2, node3));

            service.onTopologyChanged(event);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    verify(migrationWorker, atLeastOnce()).cancelCurrentMigration());

            // If any keys migrated, verify the correctness invariant:
            // each migrated key's primary owner in the expanded ring must NOT be self.
            mockingDetails(migrationWorker).getInvocations().stream()
                    .filter(inv -> inv.getMethod().getName().equals("startMigration"))
                    .findFirst()
                    .ifPresent(inv -> {
                        @SuppressWarnings("unchecked")
                        Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = inv.getArgument(0);

                        for (List<MigrationEntry> entries : plan.values()) {
                            for (MigrationEntry entry : entries) {
                                // After node3 joined, the ring has 3 nodes.
                                // The primary for this key must NOT be self.
                                NodeInfoHashAdapter newOwner = ring.getNode(entry.key());
                                assertThat(newOwner.getNodeId())
                                        .as("Migrated key '%s' should not still be owned by self " +
                                            "in the expanded ring", entry.key())
                                        .isNotEqualTo("node-1");
                            }
                        }
                    });
        }
    }
}
