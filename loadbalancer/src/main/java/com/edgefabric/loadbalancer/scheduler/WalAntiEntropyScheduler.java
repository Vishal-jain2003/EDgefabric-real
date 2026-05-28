package com.edgefabric.loadbalancer.scheduler;

import com.edgefabric.loadbalancer.model.CacheNode;
import com.edgefabric.loadbalancer.service.QuorumService;
import com.edgefabric.loadbalancer.util.LogSanitizer;
import com.edgefabric.loadbalancer.wal.OperationType;
import com.edgefabric.loadbalancer.wal.WalEntry;
import com.edgefabric.loadbalancer.wal.WalWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background scheduler for WAL-based anti-entropy repair.
 *
 * <p>Periodically scans the WAL for entries with failed nodes and sends targeted
 * repair PUTs only to those specific nodes, avoiding the overhead of random sampling
 * and full cluster GET+PUT rounds.
 *
 * <p>This is the "fast path" for anti-entropy, handling partial write failures
 * (W=2/3 success with 1 node miss) near real-time. The random sampling
 * {@code AntiEntropyScrubber} remains as a safety net for silent corruption.
 *
 * <p>Only enabled when WAL is enabled ({@code wal.enabled=true}).
 */
@Component
@ConditionalOnBean(WalWriter.class)
public class WalAntiEntropyScheduler {

    private static final Logger log = LoggerFactory.getLogger(WalAntiEntropyScheduler.class);

    private final WalWriter walWriter;
    private final QuorumService quorumService;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final int maxEntriesPerRound;

    private Counter entriesScannedCounter;
    private Counter nodesRepairedCounter;
    private Counter repairFailuresCounter;
    private Timer repairLatencyTimer;

    public WalAntiEntropyScheduler(
            WalWriter walWriter,
            QuorumService quorumService,
            MeterRegistry meterRegistry,
            @Value("${wal.anti-entropy.enabled:true}") boolean enabled,
            @Value("${wal.anti-entropy.max-entries-per-round:1000}") int maxEntriesPerRound) {
        this.walWriter = walWriter;
        this.quorumService = quorumService;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.maxEntriesPerRound = maxEntriesPerRound;
    }

    @PostConstruct
    public void initMetrics() {
        entriesScannedCounter = Counter.builder("edgefabric.wal_anti_entropy.entries_scanned")
                .description("Total WAL entries scanned for stale nodes")
                .register(meterRegistry);

        nodesRepairedCounter = Counter.builder("edgefabric.wal_anti_entropy.nodes_repaired")
                .description("Total nodes successfully repaired from WAL")
                .register(meterRegistry);

        repairFailuresCounter = Counter.builder("edgefabric.wal_anti_entropy.repair_failures")
                .description("Total failed repair attempts from WAL")
                .register(meterRegistry);

        repairLatencyTimer = Timer.builder("edgefabric.wal_anti_entropy.repair_latency")
                .description("Time taken to repair individual stale entries")
                .register(meterRegistry);

        io.micrometer.core.instrument.Gauge.builder("edgefabric.wal.pending_entries", walWriter, WalWriter::getPendingCount)
                .description("Number of WAL entries waiting to be flushed")
                .baseUnit("entries")
                .register(meterRegistry);

        log.info("WAL-based anti-entropy scheduler initialized | enabled={} maxEntriesPerRound={}",
                enabled, maxEntriesPerRound);
    }

    /**
     * Scheduled background repair job.
     * Runs every 60 seconds (configurable) to repair stale entries from WAL.
     */
    @Scheduled(fixedDelayString = "${wal.anti-entropy.interval-ms:60000}")
    public void repairStaleEntries() {
        if (!enabled) {
            return;
        }

        long startTime = System.currentTimeMillis();
        AtomicInteger scanned = new AtomicInteger(0);
        AtomicInteger repaired = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);

        try {
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                walWriter.replay(entry -> {
                    // Stop after max entries to avoid long-running rounds
                    if (scanned.incrementAndGet() > maxEntriesPerRound) {
                        return;
                    }

                    entriesScannedCounter.increment();

                    // Skip entries with no failed nodes
                    if (!entry.hasStaleNodes()) {
                        skipped.incrementAndGet();
                        return;
                    }

                    // Skip expired entries
                    if (System.currentTimeMillis() > entry.expiresAt()) {
                        log.debug("Skipping expired stale entry key={}", LogSanitizer.sanitize(entry.key()));
                        skipped.incrementAndGet();
                        return;
                    }

                    if (entry.operationType() == OperationType.PUT) {
                        log.debug("Repairing stale entry key={} failed nodes: {}",
                                LogSanitizer.sanitize(entry.key()), entry.failedNodes());

                        // Repair each failed node
                        for (String failedNodeId : entry.failedNodes()) {
                            executor.submit(() -> {
                                Timer.Sample sample = Timer.start(meterRegistry);
                                try {
                                    CacheNode node = quorumService.getCacheRouter().getNodeById(failedNodeId);
                                    if (node == null) {
                                        log.debug("Failed node {} not found in cluster for key={}",
                                                failedNodeId, LogSanitizer.sanitize(entry.key()));
                                        failed.incrementAndGet();
                                        repairFailuresCounter.increment();
                                        return;
                                    }

                                    // Send repair PUT
                                    quorumService.getClusterClient().forwardPutRequest(
                                            node,
                                            entry.key(),
                                            entry.data(),
                                            entry.expiresAt(),
                                            entry.contentType(),
                                            entry.version()
                                    ).block();

                                    repaired.incrementAndGet();
                                    nodesRepairedCounter.increment();
                                    sample.stop(repairLatencyTimer);

                                    log.debug("Successfully repaired key={} on node={}",
                                            LogSanitizer.sanitize(entry.key()), failedNodeId);

                                } catch (Exception e) {
                                    failed.incrementAndGet();
                                    repairFailuresCounter.increment();
                                    log.warn("Failed to repair key={} on node={}: {}",
                                            LogSanitizer.sanitize(entry.key()), failedNodeId, e.getMessage());
                                }
                            });
                        }
                    }
                });
            } // executor.close() waits for all repairs

            long duration = System.currentTimeMillis() - startTime;
            if (scanned.get() > 0 && repaired.get() > 0) {
                log.info("WAL anti-entropy round complete | scanned={} repaired={} failed={} skipped={} duration={}ms",
                        scanned.get(), repaired.get(), failed.get(), skipped.get(), duration);
            }

        } catch (Exception e) {
            log.error("WAL anti-entropy repair round failed", e);
        }
    }
}
