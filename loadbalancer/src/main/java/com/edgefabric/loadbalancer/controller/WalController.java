package com.edgefabric.loadbalancer.controller;

import com.edgefabric.loadbalancer.util.LogSanitizer;
import com.edgefabric.loadbalancer.wal.WalEntry;
import com.edgefabric.loadbalancer.wal.WalWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exposes a REST API endpoint to manually trigger a WAL replay.
 * Can be useful when a cluster comes back online and needs to ingest from the S3/local logs.
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(com.edgefabric.loadbalancer.wal.WalWriter.class)
@RestController
@RequestMapping("/api/v1/wal")
public class WalController {

    private static final Logger log = LoggerFactory.getLogger(WalController.class);
    private final WalWriter walWriter;
    private final com.edgefabric.loadbalancer.service.QuorumService quorumService;

    public WalController(WalWriter walWriter, com.edgefabric.loadbalancer.service.QuorumService quorumService) {
        this.walWriter = walWriter;
        this.quorumService = quorumService;
    }

    /**
     * POST /api/v1/wal/replay
     * Manually triggers the WAL to pull chronological segments (from S3 or Local)
     * and streams them into the application.
     *
     * <p>Only PUT entries whose {@code expiresAt} is in the future are forwarded to
     * {@link com.edgefabric.loadbalancer.service.QuorumService#quorumWrite}.  Expired
     * entries are silently skipped and counted separately.  The {@code quorumWrite}
     * {@code Mono} is subscribed via {@code .block()} inside a virtual-thread executor
     * so the reactive pipeline actually executes (AC3).
     */
    @PostMapping("/replay")
    public ResponseEntity<String> triggerReplay() {
        log.info("Manual WAL replay triggered via REST API");
        AtomicInteger replayedCount = new AtomicInteger(0);
        AtomicInteger skippedCount  = new AtomicInteger(0);

        try {
            try (java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                walWriter.replay(entry -> {
                    log.debug("WAL entry key: {}, operation: {}", LogSanitizer.sanitize(entry.key()), entry.operationType());

                    if (entry.operationType() == com.edgefabric.loadbalancer.wal.OperationType.PUT) {
                        // AC2: skip entries that have already expired
                        if (System.currentTimeMillis() > entry.expiresAt()) {
                            log.debug("Skipping expired WAL entry key={} expiresAt={}",
                                    LogSanitizer.sanitize(entry.key()), entry.expiresAt());
                            skippedCount.incrementAndGet();
                            return;
                        }

                        replayedCount.incrementAndGet();
                        executor.submit(() -> {
                            try {
                                // AC3: subscribe the cold Mono via .block() so quorumWrite actually executes
                                quorumService.quorumWrite(
                                        entry.key(),
                                        entry.data(),
                                        entry.expiresAt(),
                                        entry.contentType()
                                ).block();
                            } catch (Exception e) {
                                log.error("Failed to route replayed PUT for key {}", LogSanitizer.sanitize(entry.key()), e);
                            }
                        });
                    } else if (entry.operationType() == com.edgefabric.loadbalancer.wal.OperationType.DELETE) {
                        replayedCount.incrementAndGet();
                        log.warn("DELETE operation replay not yet supported for key: {}", LogSanitizer.sanitize(entry.key()));
                    }
                });
            } // executor.close() automatically waits for all tasks to complete

            String msg = String.format(
                    "Replay completed successfully. Total entries replayed: %d (replayed: %d, skipped expired: %d)",
                    replayedCount.get() + skippedCount.get(),
                    replayedCount.get(),
                    skippedCount.get());
            log.info(msg);
            return ResponseEntity.ok(msg);

        } catch (Exception e) {
            log.error("Manual replay failed", e);
            return ResponseEntity.internalServerError().body("Replay failed: " + e.getMessage());
        }
    }

    /**
     * POST /api/v1/wal/replay-stale
     * Replays only WAL entries that have failed nodes, performing targeted anti-entropy repair.
     *
     * <p>This endpoint scans the WAL for entries where {@code failedNodes} is non-empty,
     * then re-sends the PUT request only to those specific nodes that missed the original write.
     *
     * <p>Typical use: scheduled background job to repair partial write failures without
     * the overhead of full random sampling anti-entropy.
     */
    @PostMapping("/replay-stale")
    public ResponseEntity<String> replayStaleEntries() {
        log.info("Stale WAL entry replay triggered via REST API");
        java.util.concurrent.atomic.AtomicInteger repairedCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger skippedCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger failedCount = new java.util.concurrent.atomic.AtomicInteger(0);

        try {
            try (java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                walWriter.replay(entry -> {
                    // Skip entries with no failed nodes
                    if (!entry.hasStaleNodes()) {
                        skippedCount.incrementAndGet();
                        return;
                    }

                    // Skip expired entries
                    if (System.currentTimeMillis() > entry.expiresAt()) {
                        log.debug("Skipping expired stale entry key={} expiresAt={}",
                                LogSanitizer.sanitize(entry.key()), entry.expiresAt());
                        skippedCount.incrementAndGet();
                        return;
                    }

                    if (entry.operationType() == com.edgefabric.loadbalancer.wal.OperationType.PUT) {
                        log.info("Repairing stale entry key={} failed nodes: {}",
                                LogSanitizer.sanitize(entry.key()), entry.failedNodes());

                        // Repair each failed node
                        for (String failedNodeId : entry.failedNodes()) {
                            executor.submit(() -> {
                                try {
                                    // Get the failed node from router
                                    com.edgefabric.loadbalancer.model.CacheNode node = findNodeById(failedNodeId);
                                    if (node == null) {
                                        log.warn("Failed node {} not found in cluster for key={}",
                                                failedNodeId, LogSanitizer.sanitize(entry.key()));
                                        failedCount.incrementAndGet();
                                        return;
                                    }

                                    // Send repair PUT to this specific node
                                    quorumService.getClusterClient().forwardPutRequest(
                                            node,
                                            entry.key(),
                                            entry.data(),
                                            entry.expiresAt(),
                                            entry.contentType(),
                                            entry.version()
                                    ).block();

                                    repairedCount.incrementAndGet();
                                    log.info("Successfully repaired key={} on node={}",
                                            LogSanitizer.sanitize(entry.key()), failedNodeId);

                                } catch (Exception e) {
                                    failedCount.incrementAndGet();
                                    log.error("Failed to repair key={} on node={}",
                                            LogSanitizer.sanitize(entry.key()), failedNodeId, e);
                                }
                            });
                        }
                    }
                });
            } // executor.close() waits for all repairs to complete

            String msg = String.format(
                    "Stale entry replay completed. Total: %d entries | Repaired: %d nodes | Skipped: %d | Failed: %d",
                    repairedCount.get() + skippedCount.get() + failedCount.get(),
                    repairedCount.get(),
                    skippedCount.get(),
                    failedCount.get());
            log.info(msg);
            return ResponseEntity.ok(msg);

        } catch (Exception e) {
            log.error("Stale entry replay failed", e);
            return ResponseEntity.internalServerError().body("Stale replay failed: " + e.getMessage());
        }
    }

    /**
     * Helper to find a cache node by ID from the router's current view.
     * Returns null if node is not in the current cluster topology.
     */
    private com.edgefabric.loadbalancer.model.CacheNode findNodeById(String nodeId) {
        return quorumService.getCacheRouter().getNodeById(nodeId);
    }
}
