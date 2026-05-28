package com.edgefabric.loadbalancer.service;

import com.edgefabric.loadbalancer.client.ClusterClient;
import com.edgefabric.loadbalancer.dto.response.CacheResponse;
import com.edgefabric.loadbalancer.model.CacheNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Handles asynchronous Read Repair for the distributed cache.
 *
 * <p>When a quorum read detects that some replicas hold a stale version of a key,
 * this service writes the latest (winning) value back to those replicas in the
 * background — without blocking the client response.
 *
 * <p>Read Repair restores eventual consistency after partial writes or node
 * failures, without requiring a separate reconciliation process.
 */
@Service
public class ReadRepairService {

    private static final Logger logger = LoggerFactory.getLogger(ReadRepairService.class);

    private final ClusterClient clusterClient;
    private final ExecutorService repairExecutor;

    public ReadRepairService(ClusterClient clusterClient, ExecutorService repairExecutor) {
        this.clusterClient = clusterClient;
        this.repairExecutor = repairExecutor;
    }

    /**
     * Identifies stale replicas and asynchronously repairs them by writing the
     * latest value.
     *
     * <p>A replica is considered stale when its version is strictly less than the
     * winner's version. The repair write is fire-and-forget: failures are logged
     * but never propagated to the caller.
     *
     * @param tenantKey   the fully-qualified tenant-scoped cache key
     * @param allReplicas all replicas that were contacted during the quorum read
     * @param winner      the response with the highest version (the authoritative value)
     */
    public void repairStaleReplicas(String tenantKey,
                                    List<StaleReplica> allReplicas,
                                    CacheResponse winner) {

        if (winner == null) {
            logger.warn("Read Repair skipped: winner response is null for key={}", tenantKey);
            return;
        }

        if (allReplicas == null || allReplicas.isEmpty()) {
            logger.debug("Read Repair skipped: no replicas provided for key={}", tenantKey);
            return;
        }

        List<StaleReplica> staleReplicas = filterStaleReplicas(allReplicas, winner.getVersion());

        if (staleReplicas.isEmpty()) {
            logger.debug("Read Repair: all replicas are up-to-date for key={}", tenantKey);
            return;
        }

        logger.info("Read Repair: detected {} stale replica(s) for key={} — repairing asynchronously",
                staleReplicas.size(), tenantKey);

        for (StaleReplica stale : staleReplicas) {
            scheduleRepair(tenantKey, stale.node(), winner);
        }
    }

    // ─────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────

    private List<StaleReplica> filterStaleReplicas(List<StaleReplica> replicas, long winnerVersion) {
        return replicas.stream()
                .filter(r -> r.version() < winnerVersion)
                .toList();
    }

    private void scheduleRepair(String tenantKey, CacheNode node, CacheResponse winner) {
        CompletableFuture.runAsync(
                () -> executeRepair(tenantKey, node, winner),
                repairExecutor
        ).exceptionally(ex -> {
            logger.warn("Async read repair scheduling failed for node={} key={}",
                    node.getNodeId(),
                    tenantKey,
                    ex);
            return null;
        });
    }

    private void executeRepair(String tenantKey, CacheNode node, CacheResponse winner) {
        try {
            if (winner.getData() == null) {
                logger.debug("Read Repair skipped: streaming payloads are not repaired asynchronously.");
                return;
            }
            logger.debug("Read Repair: writing latest value to stale node={} key={} version={}",
                    node.getNodeId(), tenantKey, winner.getVersion());

            // Block on the reactive PUT - acceptable since we're already in a background executor
            clusterClient.forwardPutRequest(
                    node,
                    tenantKey,
                    winner.getData(),
                    winner.getExpiresAt(),
                    winner.getContentType(),
                    winner.getVersion()
            ).block();

            logger.info("Read Repair: successfully repaired node={} key={}", node.getNodeId(), tenantKey);

        } catch (Exception e) {
            // Repair failures are non-fatal — log and move on.
            // The next read will detect and re-attempt repair.
            logger.warn("Read Repair: failed to repair node={} key={}",
                    node.getNodeId(),
                    tenantKey,
                    e);
        }
    }

    /**
     * Represents a replica node alongside the version it returned during the quorum read.
     * Used to determine which replicas are stale relative to the winner.
     *
     * @param node    the cache node
     * @param version the version this node returned (or -1 if the node did not respond)
     */
    public record StaleReplica(CacheNode node, long version) {}
}
