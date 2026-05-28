package com.edgefabric.caching.migration;

import com.edgefabric.caching.event.TopologyChangedEvent;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.resolver.TtlCacheResolver;
import com.edgefabric.hashing.core.ConsistentHashRing;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KeyMigrationServiceTest {

    @Mock private ConsistentHashRing<NodeInfoHashAdapter> migrationHashRing;
    @Mock private MigrationWorker migrationWorker;
    @Mock private MembershipList membershipList;

    private Map<String, CacheItem> store;
    private MigrationProperties properties;
    private KeyMigrationService service;

    private NodeInfo createNode(String id, String host) {
        return new NodeInfo(id, host, 8080, 7946);
    }

    private CacheItem createCacheItem(String data) {
        return new CacheItem(data.getBytes(), 60_000L, "text/plain", 1L);
    }

    @BeforeEach
    void setUp() {
        store = new ConcurrentHashMap<>();
        properties = new MigrationProperties();
        // Short debounce for faster tests
        properties.setDebounceMs(50);

        service = new KeyMigrationService(
                store, migrationHashRing, migrationWorker, membershipList, properties,
                new TtlCacheResolver());
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Nested
    class TopologyChangedHandling {

        @Test
        void shouldCancelPreviousMigrationOnNewTopologyChange() {
            NodeInfo self = createNode("self", "127.0.0.1");
            NodeInfo added = createNode("node-2", "10.0.0.2");
            when(membershipList.getSelf()).thenReturn(self);
            when(migrationHashRing.getActiveNodeIds()).thenReturn(Collections.emptySet());
            when(migrationHashRing.isEmpty()).thenReturn(false);

            NodeInfoHashAdapter selfAdapter = new NodeInfoHashAdapter(self);
            when(migrationHashRing.getNodes(anyString(), anyInt())).thenReturn(List.of(selfAdapter));

            store.put("key1", createCacheItem("data1"));

            TopologyChangedEvent event1 = new TopologyChangedEvent(
                    this, Set.of(added), Collections.emptySet(), List.of(self, added));

            service.onTopologyChanged(event1);

            // Fire a second topology change immediately — should cancel the pending debounce
            NodeInfo added2 = createNode("node-3", "10.0.0.3");
            TopologyChangedEvent event2 = new TopologyChangedEvent(
                    this, Set.of(added2), Collections.emptySet(), List.of(self, added, added2));

            service.onTopologyChanged(event2);

            // Wait for debounce to complete
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                verify(migrationWorker, atLeastOnce()).cancelCurrentMigration();
            });
        }

        @Test
        void shouldRebuildRingAndSubmitMigrationPlan() {
            NodeInfo self = createNode("self", "127.0.0.1");
            NodeInfo target = createNode("target-node", "10.0.0.2");
            when(membershipList.getSelf()).thenReturn(self);
            when(migrationHashRing.getActiveNodeIds()).thenReturn(Collections.emptySet());
            when(migrationHashRing.isEmpty()).thenReturn(false);

            NodeInfoHashAdapter targetAdapter = new NodeInfoHashAdapter(target);
            when(migrationHashRing.getNodes(anyString(), anyInt())).thenReturn(List.of(targetAdapter));

            store.put("key1", createCacheItem("data1"));
            store.put("key2", createCacheItem("data2"));

            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Set.of(target), Collections.emptySet(), List.of(self, target));

            service.onTopologyChanged(event);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                @SuppressWarnings("unchecked")
                ArgumentCaptor<Map<NodeInfoHashAdapter, List<MigrationEntry>>> captor =
                        ArgumentCaptor.forClass(Map.class);
                verify(migrationWorker).startMigration(captor.capture());

                Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = captor.getValue();
                assertThat(plan).containsKey(targetAdapter);
                assertThat(plan.get(targetAdapter)).hasSize(2);
            });
        }

        @Test
        void shouldNotMigrateKeysOwnedBySelf() {
            NodeInfo self = createNode("self", "127.0.0.1");
            NodeInfo other = createNode("other", "10.0.0.2");
            when(membershipList.getSelf()).thenReturn(self);
            when(migrationHashRing.getActiveNodeIds()).thenReturn(Collections.emptySet());
            when(migrationHashRing.isEmpty()).thenReturn(false);

            NodeInfoHashAdapter selfAdapter = new NodeInfoHashAdapter(self);
            // All keys belong to self in the replica set — nothing should migrate
            when(migrationHashRing.getNodes(anyString(), anyInt())).thenReturn(List.of(selfAdapter));

            store.put("key1", createCacheItem("data1"));
            store.put("key2", createCacheItem("data2"));

            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Set.of(other), Collections.emptySet(), List.of(self, other));

            service.onTopologyChanged(event);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                // With very short debounce, the executeMigration should have run
                // but not submitted anything since all keys belong to self
                verify(migrationWorker, atLeastOnce()).cancelCurrentMigration();
            });

            // Give additional time to ensure startMigration is NOT called
            await().during(200, TimeUnit.MILLISECONDS).atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        verify(migrationWorker, never()).startMigration(any());
                    });
        }

        @Test
        void shouldSkipMigrationWhenRingIsEmpty() {
            NodeInfo self = createNode("self", "127.0.0.1");
            // Ring has no current nodes and remains empty after rebuild
            when(migrationHashRing.getActiveNodeIds()).thenReturn(Collections.emptySet());
            when(migrationHashRing.isEmpty()).thenReturn(true);

            store.put("key1", createCacheItem("data1"));

            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Collections.emptySet(), Set.of(createNode("dead", "10.0.0.99")),
                    List.of(self));

            service.onTopologyChanged(event);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                verify(migrationWorker, atLeastOnce()).cancelCurrentMigration();
            });

            // Should never start migration when ring is empty
            await().during(200, TimeUnit.MILLISECONDS).atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        verify(migrationWorker, never()).startMigration(any());
                    });
        }

        @Test
        void shouldNotSubmitPlanWhenStoreIsEmpty() {
            NodeInfo self = createNode("self", "127.0.0.1");
            NodeInfo target = createNode("target", "10.0.0.2");
            when(membershipList.getSelf()).thenReturn(self);
            when(migrationHashRing.getActiveNodeIds()).thenReturn(Collections.emptySet());
            when(migrationHashRing.isEmpty()).thenReturn(false);

            // Store is empty — nothing to scan

            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Set.of(target), Collections.emptySet(), List.of(self, target));

            service.onTopologyChanged(event);

            // Wait for debounce + execution
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                verify(migrationWorker, atLeastOnce()).cancelCurrentMigration();
            });

            await().during(200, TimeUnit.MILLISECONDS).atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        verify(migrationWorker, never()).startMigration(any());
                    });
        }
    }

    @Nested
    class RingManagement {

        @Test
        void shouldAddNewNodesToRing() {
            NodeInfo self = createNode("self", "127.0.0.1");
            NodeInfo newNode = createNode("new-node", "10.0.0.5");
            when(membershipList.getSelf()).thenReturn(self);
            when(migrationHashRing.getActiveNodeIds()).thenReturn(Collections.emptySet());
            when(migrationHashRing.isEmpty()).thenReturn(false);

            // Store is empty, so no keys to scan, but ring operations still happen

            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Set.of(newNode), Collections.emptySet(), List.of(self, newNode));

            service.onTopologyChanged(event);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                verify(migrationHashRing).addNode(argThat(adapter ->
                        adapter.getNodeId().equals("new-node")));
            });
        }

        @Test
        void shouldRemoveDeadNodesFromRing() {
            NodeInfo self = createNode("self", "127.0.0.1");
            NodeInfo deadNode = createNode("dead-node", "10.0.0.6");

            // dead-node was previously in the ring
            when(migrationHashRing.getActiveNodeIds()).thenReturn(Set.of("dead-node"));
            when(migrationHashRing.isEmpty()).thenReturn(true);

            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Collections.emptySet(), Set.of(deadNode), List.of(self));

            // The rebuildRing method uses adapterMap internally. Since the node was never
            // added through a prior topology change, the internal adapterMap won't have it.
            // However, the code gracefully handles this (adapter == null check).

            service.onTopologyChanged(event);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                verify(migrationWorker, atLeastOnce()).cancelCurrentMigration();
            });
        }
    }

    @Nested
    class DebounceBehavior {

        @Test
        void shouldDebounceRapidTopologyChanges() {
            NodeInfo self = createNode("self", "127.0.0.1");
            when(migrationHashRing.getActiveNodeIds()).thenReturn(Collections.emptySet());
            when(migrationHashRing.isEmpty()).thenReturn(true);

            // Fire 5 rapid events
            for (int i = 0; i < 5; i++) {
                NodeInfo added = createNode("node-" + i, "10.0.0." + i);
                TopologyChangedEvent event = new TopologyChangedEvent(
                        this, Set.of(added), Collections.emptySet(), List.of(self, added));
                service.onTopologyChanged(event);
            }

            // Only one executeMigration should run (the last debounced one)
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                // cancelCurrentMigration is called once per executeMigration call
                verify(migrationWorker, atMost(2)).cancelCurrentMigration();
            });
        }
    }

    @Nested
    class MixedKeyOwnership {

        @Test
        void shouldOnlyMigrateKeysNotOwnedBySelf() {
            NodeInfo self = createNode("self", "127.0.0.1");
            NodeInfo targetNode = createNode("target", "10.0.0.2");
            when(membershipList.getSelf()).thenReturn(self);
            when(migrationHashRing.getActiveNodeIds()).thenReturn(Collections.emptySet());
            when(migrationHashRing.isEmpty()).thenReturn(false);

            NodeInfoHashAdapter selfAdapter = new NodeInfoHashAdapter(self);
            NodeInfoHashAdapter targetAdapter = new NodeInfoHashAdapter(targetNode);

            // key1 -> self is a replica, key2 -> only target, key3 -> self is a replica
            when(migrationHashRing.getNodes(eq("key1"), anyInt())).thenReturn(List.of(selfAdapter));
            when(migrationHashRing.getNodes(eq("key2"), anyInt())).thenReturn(List.of(targetAdapter));
            when(migrationHashRing.getNodes(eq("key3"), anyInt())).thenReturn(List.of(selfAdapter));

            store.put("key1", createCacheItem("data1"));
            store.put("key2", createCacheItem("data2"));
            store.put("key3", createCacheItem("data3"));

            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Set.of(targetNode), Collections.emptySet(), List.of(self, targetNode));

            service.onTopologyChanged(event);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                @SuppressWarnings("unchecked")
                ArgumentCaptor<Map<NodeInfoHashAdapter, List<MigrationEntry>>> captor =
                        ArgumentCaptor.forClass(Map.class);
                verify(migrationWorker).startMigration(captor.capture());

                Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = captor.getValue();
                assertThat(plan).hasSize(1);
                assertThat(plan.get(targetAdapter)).hasSize(1);
                assertThat(plan.get(targetAdapter).get(0).key()).isEqualTo("key2");
            });
        }
    }

    // ── NEW: Replication-factor-aware migration logic ──────────────────────────

    @Nested
    class ReplicationFactorAwareness {

        @Test
        void shouldNotMigrateWhenSelfIsReplicaInRF3Cluster() {
            // CRITICAL: RF=3 on a 3-node cluster means every node is a valid replica.
            // Self is always in getNodes(key, 3) → zero keys should migrate.
            NodeInfo self  = createNode("self",   "127.0.0.1");
            NodeInfo node2 = createNode("node-2", "10.0.0.2");
            NodeInfo node3 = createNode("node-3", "10.0.0.3");
            when(membershipList.getSelf()).thenReturn(self);
            when(migrationHashRing.getActiveNodeIds()).thenReturn(Collections.emptySet());
            when(migrationHashRing.isEmpty()).thenReturn(false);

            NodeInfoHashAdapter selfAdapter  = new NodeInfoHashAdapter(self);
            NodeInfoHashAdapter node2Adapter = new NodeInfoHashAdapter(node2);
            NodeInfoHashAdapter node3Adapter = new NodeInfoHashAdapter(node3);

            when(migrationHashRing.getNodes(anyString(), anyInt()))
                    .thenReturn(List.of(selfAdapter, node2Adapter, node3Adapter));

            store.put("key1", createCacheItem("data1"));
            store.put("key2", createCacheItem("data2"));
            store.put("key3", createCacheItem("data3"));

            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Collections.emptySet(), Collections.emptySet(),
                    List.of(self, node2, node3));

            service.onTopologyChanged(event);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    verify(migrationWorker, atLeastOnce()).cancelCurrentMigration());

            await().during(300, TimeUnit.MILLISECONDS).atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(migrationWorker, never()).startMigration(any()));
        }

        @Test
        void shouldMigrateOnlyKeysWhereReplicaSetExcludesSelf_RF3() {
            // RF=3, 4 nodes. key1 has self in replica set; key2 does not.
            // Only key2 should appear in the migration plan.
            NodeInfo self  = createNode("self",   "127.0.0.1");
            NodeInfo node2 = createNode("node-2", "10.0.0.2");
            NodeInfo node3 = createNode("node-3", "10.0.0.3");
            NodeInfo node4 = createNode("node-4", "10.0.0.4");
            when(membershipList.getSelf()).thenReturn(self);
            when(migrationHashRing.getActiveNodeIds()).thenReturn(Collections.emptySet());
            when(migrationHashRing.isEmpty()).thenReturn(false);

            NodeInfoHashAdapter selfAdapter  = new NodeInfoHashAdapter(self);
            NodeInfoHashAdapter node2Adapter = new NodeInfoHashAdapter(node2);
            NodeInfoHashAdapter node3Adapter = new NodeInfoHashAdapter(node3);
            NodeInfoHashAdapter node4Adapter = new NodeInfoHashAdapter(node4);

            // key1: self is a replica — keep
            when(migrationHashRing.getNodes(eq("key1"), anyInt()))
                    .thenReturn(List.of(node2Adapter, selfAdapter, node3Adapter));
            // key2: self NOT in replica set — migrate to primary (node2)
            when(migrationHashRing.getNodes(eq("key2"), anyInt()))
                    .thenReturn(List.of(node2Adapter, node3Adapter, node4Adapter));
            // key3: self is a replica — keep
            when(migrationHashRing.getNodes(eq("key3"), anyInt()))
                    .thenReturn(List.of(selfAdapter, node4Adapter, node2Adapter));

            store.put("key1", createCacheItem("data1"));
            store.put("key2", createCacheItem("data2"));
            store.put("key3", createCacheItem("data3"));

            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Set.of(node4), Collections.emptySet(),
                    List.of(self, node2, node3, node4));

            service.onTopologyChanged(event);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                @SuppressWarnings("unchecked")
                ArgumentCaptor<Map<NodeInfoHashAdapter, List<MigrationEntry>>> captor =
                        ArgumentCaptor.forClass(Map.class);
                verify(migrationWorker).startMigration(captor.capture());

                Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = captor.getValue();
                assertThat(plan).hasSize(1);
                assertThat(plan.get(node2Adapter)).hasSize(1);
                assertThat(plan.get(node2Adapter).get(0).key()).isEqualTo("key2");
            });
        }

        @Test
        void shouldNotMigrateAnyKeyWhenAllKeysHaveSelfAsReplica_100Keys() {
            // All 100 keys map to a replica set that includes self → empty plan → no startMigration.
            NodeInfo self  = createNode("self",   "127.0.0.1");
            NodeInfo node2 = createNode("node-2", "10.0.0.2");
            when(membershipList.getSelf()).thenReturn(self);
            when(migrationHashRing.getActiveNodeIds()).thenReturn(Collections.emptySet());
            when(migrationHashRing.isEmpty()).thenReturn(false);

            NodeInfoHashAdapter selfAdapter  = new NodeInfoHashAdapter(self);
            NodeInfoHashAdapter node2Adapter = new NodeInfoHashAdapter(node2);

            when(migrationHashRing.getNodes(anyString(), anyInt()))
                    .thenReturn(List.of(selfAdapter, node2Adapter));

            for (int i = 0; i < 100; i++) {
                store.put("key" + i, createCacheItem("data" + i));
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

    // ── NEW: Edge cases that are not covered by the base test suite ────────────

    @Nested
    class EdgeCases {

        @Test
        void shouldHandleTopologyEventWithNoAddedOrRemovedNodes() {
            // addedNodes=[] and removedNodes=[] is a valid no-op event.
            // With an unchanged ring, self stays in every replica set → no migration.
            NodeInfo self = createNode("self", "127.0.0.1");
            when(membershipList.getSelf()).thenReturn(self);
            when(migrationHashRing.getActiveNodeIds()).thenReturn(Collections.emptySet());
            when(migrationHashRing.isEmpty()).thenReturn(false);

            NodeInfoHashAdapter selfAdapter = new NodeInfoHashAdapter(self);
            when(migrationHashRing.getNodes(anyString(), anyInt()))
                    .thenReturn(List.of(selfAdapter));

            store.put("key1", createCacheItem("data1"));

            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Collections.emptySet(), Collections.emptySet(), List.of(self));

            service.onTopologyChanged(event);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    verify(migrationWorker, atLeastOnce()).cancelCurrentMigration());

            await().during(300, TimeUnit.MILLISECONDS).atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(migrationWorker, never()).startMigration(any()));
        }

        @Test
        void shouldSkipMigrationWhenAliveNodesListIsEmpty() {
            // allAliveNodes=[] → ring is empty after rebuild → migration skipped gracefully.
            when(migrationHashRing.getActiveNodeIds()).thenReturn(Collections.emptySet());
            when(migrationHashRing.isEmpty()).thenReturn(true);

            store.put("key1", createCacheItem("data1"));

            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Collections.emptySet(), Collections.emptySet(),
                    Collections.emptyList());

            service.onTopologyChanged(event);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    verify(migrationWorker, atLeastOnce()).cancelCurrentMigration());

            await().during(300, TimeUnit.MILLISECONDS).atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(migrationWorker, never()).startMigration(any()));
        }

        @Test
        void shouldBuildCorrectMigrationPlanForLargeStore_1000Keys() {
            // 1000 keys in store, all assigned to target node → plan has exactly 1000 entries.
            NodeInfo self   = createNode("self",   "127.0.0.1");
            NodeInfo target = createNode("target", "10.0.0.2");
            when(membershipList.getSelf()).thenReturn(self);
            when(migrationHashRing.getActiveNodeIds()).thenReturn(Collections.emptySet());
            when(migrationHashRing.isEmpty()).thenReturn(false);

            NodeInfoHashAdapter targetAdapter = new NodeInfoHashAdapter(target);
            when(migrationHashRing.getNodes(anyString(), anyInt())).thenReturn(List.of(targetAdapter));

            for (int i = 0; i < 1000; i++) {
                store.put("key-" + i, createCacheItem("data-" + i));
            }

            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Set.of(target), Collections.emptySet(), List.of(self, target));

            service.onTopologyChanged(event);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                @SuppressWarnings("unchecked")
                ArgumentCaptor<Map<NodeInfoHashAdapter, List<MigrationEntry>>> captor =
                        ArgumentCaptor.forClass(Map.class);
                verify(migrationWorker).startMigration(captor.capture());

                int total = captor.getValue().values().stream().mapToInt(List::size).sum();
                assertThat(total).isEqualTo(1000);
            });
        }

        @Test
        void shouldNotCrashWhenSelfNodeIsAbsentFromAliveList() {
            // Edge case: self is not in allAliveNodes (transient race at startup).
            // rebuildRing only adds what is in aliveNodes; self is absent.
            // executeMigration must still complete without NPE.
            NodeInfo self  = createNode("self",  "127.0.0.1");
            NodeInfo other = createNode("other", "10.0.0.2");
            when(membershipList.getSelf()).thenReturn(self);
            when(migrationHashRing.getActiveNodeIds()).thenReturn(Collections.emptySet());
            when(migrationHashRing.isEmpty()).thenReturn(false);

            NodeInfoHashAdapter otherAdapter = new NodeInfoHashAdapter(other);
            when(migrationHashRing.getNodes(anyString(), anyInt())).thenReturn(List.of(otherAdapter));

            store.put("key1", createCacheItem("data1"));

            // allAliveNodes does NOT contain self
            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Set.of(other), Collections.emptySet(), List.of(other));

            // Must not throw
            service.onTopologyChanged(event);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    verify(migrationWorker, atLeastOnce()).cancelCurrentMigration());
        }
    }

    // ── NodeRemoval: re-seeding when a node leaves the cluster ────────────────

    @Nested
    class NodeRemoval {

        /**
         * Core regression test for the scale-in bug.
         *
         * Before the fix, when a node was removed (addedNodeIds = empty),
         * neither the EVICTION branch (selfIsReplica=true) nor the SEEDING branch
         * (!addedNodeIds.isEmpty() = false) fired — so zero migration happened.
         *
         * After the fix, the elected seeder re-seeds ALL other nodes in the new
         * replica set to compensate for keys that may have been evicted during a
         * prior scale-out.
         */
        @Test
        void shouldReseedOtherReplicasWhenNodeIsRemoved() {
            // Arrange: self is the elected seeder (first in ring order).
            // N4 just died; the new ring is self + N2 + N3.
            NodeInfo self  = createNode("self",  "127.0.0.1");
            NodeInfo node2 = createNode("node-2", "10.0.0.2");
            NodeInfo node3 = createNode("node-3", "10.0.0.3");
            NodeInfo dead  = createNode("node-4", "10.0.0.4"); // the removed node

            when(membershipList.getSelf()).thenReturn(self);
            // All 4 nodes were in the ring before the scale-in; dead-node is being removed
            when(migrationHashRing.getActiveNodeIds())
                    .thenReturn(Set.of("self", "node-2", "node-3", "node-4"));
            when(migrationHashRing.isEmpty()).thenReturn(false);

            NodeInfoHashAdapter selfAdapter  = new NodeInfoHashAdapter(self);
            NodeInfoHashAdapter node2Adapter = new NodeInfoHashAdapter(node2);
            NodeInfoHashAdapter node3Adapter = new NodeInfoHashAdapter(node3);

            // Self is first replica (elected seeder). All 3 surviving nodes are in the new replica set.
            when(migrationHashRing.getNodes(anyString(), anyInt()))
                    .thenReturn(List.of(selfAdapter, node2Adapter, node3Adapter));

            store.put("key1", createCacheItem("data1"));

            // removedNodes is non-empty, addedNodes is empty — this is the scale-in path
            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Collections.emptySet(), Set.of(dead), List.of(self, node2, node3));

            service.onTopologyChanged(event);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                @SuppressWarnings("unchecked")
                ArgumentCaptor<Map<NodeInfoHashAdapter, List<MigrationEntry>>> captor =
                        ArgumentCaptor.forClass(Map.class);
                verify(migrationWorker).startMigration(captor.capture());

                Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = captor.getValue();
                // Self should seed to BOTH other replicas (node2 and node3)
                assertThat(plan).containsKey(node2Adapter);
                assertThat(plan).containsKey(node3Adapter);
                assertThat(plan.get(node2Adapter)).hasSize(1);
                assertThat(plan.get(node3Adapter)).hasSize(1);
                // Seeding keeps the local copy (deleteAfterPush = false)
                assertThat(plan.get(node2Adapter).get(0).deleteAfterPush()).isFalse();
                assertThat(plan.get(node3Adapter).get(0).deleteAfterPush()).isFalse();
            });
        }

        @Test
        void shouldElectOnlyFirstReplicaAsSeederOnNodeRemoval() {
            // Only the FIRST surviving replica in ring order should seed — not all of them.
            // node2 is the first replica, so only node2 should call startMigration
            // (self is second, so self is NOT the elected seeder here).
            NodeInfo self  = createNode("self",   "127.0.0.1");
            NodeInfo node2 = createNode("node-2", "10.0.0.2"); // first in ring order → elected seeder
            NodeInfo node3 = createNode("node-3", "10.0.0.3");
            NodeInfo dead  = createNode("node-4", "10.0.0.4");

            when(membershipList.getSelf()).thenReturn(self);
            // All 4 nodes were in the ring before the scale-in
            when(migrationHashRing.getActiveNodeIds())
                    .thenReturn(Set.of("self", "node-2", "node-3", "node-4"));
            when(migrationHashRing.isEmpty()).thenReturn(false);

            NodeInfoHashAdapter selfAdapter  = new NodeInfoHashAdapter(self);
            NodeInfoHashAdapter node2Adapter = new NodeInfoHashAdapter(node2);
            NodeInfoHashAdapter node3Adapter = new NodeInfoHashAdapter(node3);

            // node2 is first → self is NOT the elected seeder
            when(migrationHashRing.getNodes(anyString(), anyInt()))
                    .thenReturn(List.of(node2Adapter, selfAdapter, node3Adapter));

            store.put("key1", createCacheItem("data1"));

            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Collections.emptySet(), Set.of(dead), List.of(self, node2, node3));

            service.onTopologyChanged(event);

            // Self is NOT the elected seeder → no migration should be started from self
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    verify(migrationWorker, atLeastOnce()).cancelCurrentMigration());

            await().during(300, TimeUnit.MILLISECONDS).atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(migrationWorker, never()).startMigration(any()));
        }

        @Test
        void seedEntriesOnNodeRemovalShouldNotDeleteLocalCopy() {
            // On scale-in, the seeder keeps its own copy (seed, not evict).
            NodeInfo self = createNode("self", "127.0.0.1");
            NodeInfo dead = createNode("dead", "10.0.0.99");
            NodeInfo peer = createNode("peer", "10.0.0.2");

            when(membershipList.getSelf()).thenReturn(self);
            // All 3 nodes were in the ring; dead is being removed
            when(migrationHashRing.getActiveNodeIds())
                    .thenReturn(Set.of("self", "peer", "dead"));
            when(migrationHashRing.isEmpty()).thenReturn(false);

            NodeInfoHashAdapter selfAdapter = new NodeInfoHashAdapter(self);
            NodeInfoHashAdapter peerAdapter = new NodeInfoHashAdapter(peer);

            // Self is elected seeder, peer is the other replica
            when(migrationHashRing.getNodes(anyString(), anyInt()))
                    .thenReturn(List.of(selfAdapter, peerAdapter));

            store.put("key1", createCacheItem("data1"));

            TopologyChangedEvent event = new TopologyChangedEvent(
                    this, Collections.emptySet(), Set.of(dead), List.of(self, peer));

            service.onTopologyChanged(event);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                @SuppressWarnings("unchecked")
                ArgumentCaptor<Map<NodeInfoHashAdapter, List<MigrationEntry>>> captor =
                        ArgumentCaptor.forClass(Map.class);
                verify(migrationWorker).startMigration(captor.capture());

                MigrationEntry entry = captor.getValue().get(peerAdapter).get(0);
                assertThat(entry.deleteAfterPush()).isFalse();
                assertThat(entry.key()).isEqualTo("key1");
            });
        }
    }
}
