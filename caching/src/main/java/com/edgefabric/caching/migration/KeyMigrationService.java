package com.edgefabric.caching.migration;

import com.edgefabric.caching.event.TopologyChangedEvent;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.resolver.TtlCacheResolver;
import com.edgefabric.hashing.core.ConsistentHashRing;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(name = "migration.enabled", havingValue = "true", matchIfMissing = true)
public class KeyMigrationService {

    private final Map<String, CacheItem> store;
    private final ConsistentHashRing<NodeInfoHashAdapter> migrationHashRing;
    private final MigrationWorker migrationWorker;
    private final MembershipList membershipList;
    private final MigrationProperties properties;
    private final TtlCacheResolver ttlResolver;

    private final ConcurrentHashMap<String, NodeInfoHashAdapter> adapterMap = new ConcurrentHashMap<>();

    private final ScheduledExecutorService debounceExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "migration-debounce");
                t.setDaemon(true);
                return t;
            });

    private final AtomicReference<ScheduledFuture<?>> pendingMigration = new AtomicReference<>();

    public KeyMigrationService(Map<String, CacheItem> store,
                               ConsistentHashRing<NodeInfoHashAdapter> migrationHashRing,
                               MigrationWorker migrationWorker,
                               MembershipList membershipList,
                               MigrationProperties properties,
                               TtlCacheResolver ttlResolver) {
        this.store = store;
        this.migrationHashRing = migrationHashRing;
        this.migrationWorker = migrationWorker;
        this.membershipList = membershipList;
        this.properties = properties;
        this.ttlResolver = ttlResolver;
    }

    @EventListener
    public void onTopologyChanged(TopologyChangedEvent event) {
        log.info("Topology change detected: {} added, {} removed",
                event.getAddedNodes().size(), event.getRemovedNodes().size());

        ScheduledFuture<?> pending = this.pendingMigration.get();
        if (pending != null && !pending.isDone()) {
            pending.cancel(false);
            log.debug("Cancelled pending debounced migration");
        }

        this.pendingMigration.set(debounceExecutor.schedule(
                () -> executeMigration(event),
                properties.getDebounceMs(),
                TimeUnit.MILLISECONDS
        ));
    }

    private void executeMigration(TopologyChangedEvent event) {
        try {
            migrationWorker.cancelCurrentMigration();

            Set<String> actuallyAddedNodeIds = rebuildRing(event.getAllAliveNodes());

            if (migrationHashRing.isEmpty()) {
                log.warn("Migration ring is empty — skipping migration");
                return;
            }

            String selfId = membershipList.getSelf().getCacheNodeId();
            int replicationFactor = properties.getReplicationFactor();

            // Node IDs that truly joined the ring for the first time (not rejoined).
            // Using event.getAddedNodes() would include rejoined nodes that already
            // had data, causing unnecessary seeding on reconnect.
            Set<String> addedNodeIds = actuallyAddedNodeIds;

            Map<NodeInfoHashAdapter, List<MigrationEntry>> migrationPlan = new HashMap<>();
            int scannedKeys = 0;

            // When nodes are removed, surviving replicas must re-seed ALL other
            // nodes in the new replica set. During a prior scale-out some of
            // those nodes may have had their local copies evicted (deleteAfterPush),
            // so they are now missing the key even though the ring says they
            // should hold it.
            boolean nodesWereRemoved = !event.getRemovedNodes().isEmpty();

            for (Map.Entry<String, CacheItem> entry : store.entrySet()) {
                scannedKeys++;

                // Skip items that are already expired — no point migrating garbage
                // to the new owner. Lazy eviction will clean them up locally.
                if (ttlResolver.isExpired(entry.getValue())) {
                    continue;
                }

                // Ask for the full replica set, not just the primary owner.
                // With RF=3 on 3 nodes every node is a valid replica, so checking
                // only the primary caused all non-primary nodes to migrate their
                // copy away — leaving just 1 copy and breaking the read quorum.
                List<NodeInfoHashAdapter> replicaOwners =
                        migrationHashRing.getNodes(entry.getKey(), replicationFactor);

                boolean selfIsReplica = replicaOwners.stream()
                        .anyMatch(n -> n.getNodeId().equals(selfId));

                if (!selfIsReplica && !replicaOwners.isEmpty()) {
                    // EVICTION: This node is no longer in the replica set — push
                    // to the primary (first) owner and delete the local copy.
                    // Read Repair will propagate the key to any other replicas
                    // the next time the key is read.
                    NodeInfoHashAdapter primary = replicaOwners.get(0);
                    migrationPlan
                            .computeIfAbsent(primary, k -> new ArrayList<>())
                            .add(MigrationEntry.evict(entry.getKey(), entry.getValue()));

                } else if (selfIsReplica && (!addedNodeIds.isEmpty() || nodesWereRemoved)) {
                    // SEEDING: Self is still a valid replica and one of these is true:
                    //   (a) NEW nodes joined  → seed only the brand-new nodes (they start empty)
                    //   (b) NODES WERE REMOVED → re-seed ALL other replicas in the new ring,
                    //       because a prior scale-out may have evicted keys from those nodes
                    //       and we have no way to know which ones are now missing the key.
                    //
                    // To avoid N nodes all pushing the same key to the same targets,
                    // only the FIRST non-new replica in the ordered list acts as the
                    // "elected seeder" for this key.
                    boolean iAmElectedSeeder = replicaOwners.stream()
                            .filter(n -> !addedNodeIds.contains(n.getNodeId()))
                            .findFirst()
                            .map(n -> n.getNodeId().equals(selfId))
                            .orElse(false);

                    if (iAmElectedSeeder) {
                        for (NodeInfoHashAdapter replica : replicaOwners) {
                            boolean isNewNode = addedNodeIds.contains(replica.getNodeId());
                            boolean isOtherReplica = nodesWereRemoved && !replica.getNodeId().equals(selfId);
                            if (isNewNode || isOtherReplica) {
                                migrationPlan
                                        .computeIfAbsent(replica, k -> new ArrayList<>())
                                        .add(MigrationEntry.seed(entry.getKey(), entry.getValue()));
                            }
                        }
                    }
                }
            }

            int totalKeys = migrationPlan.values().stream().mapToInt(List::size).sum();
            log.info("Migration scan complete: scanned={}, toMigrate={}, targetNodes={}",
                    scannedKeys, totalKeys, migrationPlan.size());

            if (totalKeys > 0) {
                migrationWorker.startMigration(migrationPlan);
            }
        } catch (Exception e) {
            log.error("Migration failed unexpectedly", e);
        }
    }

    private Set<String> rebuildRing(List<NodeInfo> aliveNodes) {
        Set<String> newNodeIds = new HashSet<>();
        Map<String, NodeInfoHashAdapter> newAdapters = new HashMap<>();

        for (NodeInfo node : aliveNodes) {
            NodeInfoHashAdapter adapter = new NodeInfoHashAdapter(node);
            newNodeIds.add(adapter.getNodeId());
            newAdapters.put(adapter.getNodeId(), adapter);
        }

        // Remove nodes no longer alive
        Set<String> currentNodeIds = migrationHashRing.getActiveNodeIds();
        for (String nodeId : currentNodeIds) {
            if (!newNodeIds.contains(nodeId)) {
                NodeInfoHashAdapter adapter = adapterMap.remove(nodeId);
                if (adapter != null) {
                    migrationHashRing.removeNode(adapter);
                    log.debug("Removed node from migration ring: {}", nodeId);
                }
            }
        }

        // Add truly new nodes and track which ones were actually added
        Set<String> addedNodeIds = new HashSet<>();
        for (Map.Entry<String, NodeInfoHashAdapter> entry : newAdapters.entrySet()) {
            if (!currentNodeIds.contains(entry.getKey())) {
                migrationHashRing.addNode(entry.getValue());
                adapterMap.put(entry.getKey(), entry.getValue());
                addedNodeIds.add(entry.getKey());
                log.debug("Added node to migration ring: {}", entry.getKey());
            }
        }
        return addedNodeIds;
    }

    @PreDestroy
    public void shutdown() {
        ScheduledFuture<?> pending = this.pendingMigration.get();
        if (pending != null) {
            pending.cancel(true);
        }
        debounceExecutor.shutdown();
    }
}
