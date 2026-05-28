package com.edgefabric.caching.membership;

import com.edgefabric.caching.antiEntropy.StaleKeyRegistry;
import com.edgefabric.caching.event.TopologyChangedEvent;
import com.edgefabric.caching.migration.NodeInfoHashAdapter;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import com.edgefabric.hashing.core.ConsistentHashRing;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class InMemoryMembershipList implements MembershipList{
    private final MembershipStore store;
    private final MembershipMerger merger;
    private final MembershipStateManager stateHandler;
    private final DirtyTracker dirtyTracker;
    private final MembershipQueryService queryService;

    private final String selfId;
    private final AtomicReference<ApplicationEventPublisher> eventPublisher = new AtomicReference<>();
    private final AtomicReference<StaleKeyRegistry> staleKeyRegistry = new AtomicReference<>();
    private final AtomicReference<ConsistentHashRing<NodeInfoHashAdapter>> hashRing = new AtomicReference<>();
    private final AtomicReference<Map<String, ?>> cacheStore = new AtomicReference<>();
    private final AtomicReference<Integer> replicationFactor = new AtomicReference<>(3);

    public InMemoryMembershipList(NodeInfo self) {
        Objects.requireNonNull(self, "self NodeInfo must not be null");

        this.store = new MembershipStore();
        this.merger = new MembershipMerger();
        this.stateHandler = new MembershipStateManager();
        this.dirtyTracker = new DirtyTracker();

        this.selfId = self.getCacheNodeId();
        this.queryService = new MembershipQueryService(store, selfId);

        // Initialize self node
        this.store.put(self);
    }

    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher.set(eventPublisher);
    }

    /**
     * Sets the StaleKeyRegistry for marking keys stale on node rejoin.
     * Optional — if not set, node rejoin detection is skipped.
     */
    public void setStaleKeyRegistry(StaleKeyRegistry staleKeyRegistry,
                                     ConsistentHashRing<NodeInfoHashAdapter> hashRing,
                                     Map<String, ?> cacheStore,
                                     int replicationFactor) {
        this.staleKeyRegistry.set(staleKeyRegistry);
        this.hashRing.set(hashRing);
        this.cacheStore.set(cacheStore);
        this.replicationFactor.set(replicationFactor);
    }

    @Override
    public NodeInfo getSelf() {
        return store.get(selfId);
    }

    @Override
    public NodeInfo getNode(String nodeId) {
        return store.get(nodeId);
    }

    @Override
    public int size() {
        return store.size();
    }

    @Override
    public void bumpSelfHeartbeat() {
        NodeInfo self = getSelf();
        stateHandler.bumpHeartbeat(self);
        dirtyTracker.markDirty(selfId);
    }

    @Override
    public void refuteSuspicion() {
        NodeInfo self = getSelf();
        stateHandler.refute(self, self.getIncarnation());
        dirtyTracker.markDirty(selfId);
    }

    @Override
    public void merge(NodeInfo incoming) {
        Objects.requireNonNull(incoming, "incoming NodeInfo must not be null");

        String nodeId = incoming.getCacheNodeId();

        // Handle self gossip separately
        if (nodeId.equals(selfId)) {
            handleSelfGossip(incoming);
            return;
        }

        NodeInfo existing = store.get(nodeId);

        // New node discovered — stamp lastUpdatedTime at merge time
        if (existing == null) {
            incoming.applyUpdate(incoming.getStatus(), incoming.getHeartbeat(), incoming.getIncarnation());
            store.put(incoming);
            dirtyTracker.markDirty(nodeId);
            if (incoming.getStatus() == Status.ALIVE) {
                publishTopologyEvent(Set.of(incoming), Collections.emptySet());
            }
            return;
        }

        // Decide merge — update existing instance in-place for thread safety
        if (merger.shouldAccept(incoming, existing)) {
            Status oldStatus = existing.getStatus();
            existing.applyUpdate(incoming.getStatus(), incoming.getHeartbeat(), incoming.getIncarnation());
            dirtyTracker.markDirty(nodeId);

            Status newStatus = existing.getStatus();
            if (oldStatus != newStatus) {
                if (newStatus == Status.ALIVE && oldStatus == Status.DEAD) {
                    // Node rejoined — mark all its owned keys as stale
                    markKeysStaleForRejoinedNode(nodeId);
                    publishTopologyEvent(Set.of(existing), Collections.emptySet());
                } else if (newStatus == Status.DEAD) {
                    publishTopologyEvent(Collections.emptySet(), Set.of(existing));
                }
            }
        }
    }

    private void handleSelfGossip(NodeInfo incoming) {
        NodeInfo self = getSelf();

        boolean isSuspicion =
                incoming.getStatus() == Status.SUSPECT ||
                        incoming.getStatus() == Status.DEAD;

        if (isSuspicion && incoming.getIncarnation() >= self.getIncarnation()) {
            stateHandler.refute(self, incoming.getIncarnation());
            dirtyTracker.markDirty(selfId);
        }
    }

    @Override
    public void markSuspect(String nodeId) {
        NodeInfo node = store.get(nodeId);
        if (node != null && stateHandler.markSuspect(node)) {
            dirtyTracker.markDirty(nodeId);
        }
    }

    @Override
    public void markAlive(String nodeId) {
        NodeInfo node = store.get(nodeId);
        if (node != null && stateHandler.markAlive(node)) {
            dirtyTracker.markDirty(nodeId);
            if (!nodeId.equals(selfId)) {
                publishTopologyEvent(Set.of(node), Collections.emptySet());
            }
        }
    }

    @Override
    public void markDead(String nodeId) {
        NodeInfo node = store.get(nodeId);
        if (node != null && stateHandler.markDead(node)) {
            dirtyTracker.markDirty(nodeId);
            if (!nodeId.equals(selfId)) {
                publishTopologyEvent(Collections.emptySet(), Set.of(node));
            }
        }
    }

    @Override
    public void markDraining(String nodeId) {
        NodeInfo node = store.get(nodeId);
        if (node != null && stateHandler.markDraining(node)) {
            dirtyTracker.markDirty(nodeId);
        }
    }

    @Override
    public void cancelDraining(String nodeId) {
        NodeInfo node = store.get(nodeId);
        if (node != null && stateHandler.cancelDraining(node)) {
            dirtyTracker.markDirty(nodeId);
        }
    }


    @Override
    public List<NodeInfo> getAliveNodes() {
        return queryService.getAliveNodes();
    }

    @Override
    public List<NodeInfo> getRandomPeers(int k) {
        return queryService.getRandomPeers(k);
    }

    @Override
    public List<NodeInfo> getNodesForSuspectCheck() {
        return queryService.getNodesForSuspectCheck();
    }

    @Override
    public List<NodeInfo> getDigest() {
        return List.copyOf(store.getAll());
    }


    @Override
    public List<NodeInfo> getDirtyDigestAndClear() {
        return dirtyTracker.getAndClear(store);
    }

    @Override
    public void markDirty(String nodeId) {
        if (store.contains(nodeId)) {
            dirtyTracker.markDirty(nodeId);
        }
    }

    @Override
    public boolean removeNode(String nodeId) {
        if (nodeId == null || nodeId.equals(selfId)) {
            return false; // never remove self
        }
        NodeInfo node = store.get(nodeId);
        if (node == null || node.getStatus() != Status.DEAD) {
            return false; // only evict DEAD nodes
        }
        store.remove(nodeId);
        return true;
    }

    @Override
    public List<NodeInfo> getDeadNodes() {
        return store.getAll().stream()
                .filter(n -> n.getStatus() == Status.DEAD)
                .toList();
    }

    /**
     * Marks all keys owned by a rejoined node as stale for self-healing.
     * This handles the case where a node was down, potentially missed writes,
     * and is now back online with stale data.
     */
    private void markKeysStaleForRejoinedNode(String rejoinedNodeId) {
        StaleKeyRegistry registry = this.staleKeyRegistry.get();
        ConsistentHashRing<NodeInfoHashAdapter> ring = this.hashRing.get();
        Map<String, ?> localCacheStore = this.cacheStore.get();
        Integer rf = this.replicationFactor.get();

        // Skip if registry not configured (optional feature)
        if (registry == null || ring == null || localCacheStore == null || rf == null) {
            return;
        }

        // For each key in local cache, check if the rejoined node is a replica
        // Create immutable snapshot to avoid ConcurrentModificationException
        List<String> keysSnapshot = new java.util.ArrayList<>(localCacheStore.keySet());

        keysSnapshot.forEach(key -> {
            try {
                List<NodeInfoHashAdapter> replicas = ring.getNodes(key, rf);
                boolean rejoinedNodeIsReplica = replicas.stream()
                        .anyMatch(adapter -> adapter.getNodeId().equals(rejoinedNodeId));

                if (rejoinedNodeIsReplica) {
                    // Mark key as stale — the rejoined node likely has outdated data
                    registry.markStale(key, 0, "node_rejoin:" + rejoinedNodeId);
                }
            } catch (Exception e) {
                log.debug("Failed to check stale key '{}' for rejoined node '{}': {}",
                        key, rejoinedNodeId, e.getMessage());
            }
        });
    }

    private void publishTopologyEvent(Set<NodeInfo> added, Set<NodeInfo> removed) {
        ApplicationEventPublisher publisher = this.eventPublisher.get();
        if (publisher == null) {
            return;
        }
        List<NodeInfo> aliveNodes = queryService.getAliveNodes();
        publisher.publishEvent(new TopologyChangedEvent(this, added, removed, aliveNodes));
    }
}
