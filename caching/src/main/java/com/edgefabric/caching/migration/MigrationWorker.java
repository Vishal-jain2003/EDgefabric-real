package com.edgefabric.caching.migration;

import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.service.LruEvictionService;
import com.edgefabric.caching.service.TimeWheelEvictionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@ConditionalOnProperty(name = "migration.enabled", havingValue = "true", matchIfMissing = true)
public class MigrationWorker {

    private static final String CACHE_MIGRATION_KEYS_METRIC = "cache_migration_keys_total";
    private static final String RESULT_TAG = "result";

    private final WebClient migrationWebClient;
    private final Map<String, CacheItem> store;
    private final TimeWheelEvictionService timeWheelEvictionService;
    private final LruEvictionService lruEvictionService;
    private final AtomicLong currentMemoryUsage;
    private final MigrationProperties properties;

    private final Counter successCounter;
    private final Counter failedCounter;
    private final Counter skippedCounter;
    private final Counter retryCounter;
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private final Timer migrationTimer;

    private volatile boolean cancelled = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "migration-worker");
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService deleteScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "migration-delete-scheduler");
                t.setDaemon(true);
                return t;
            });

    public MigrationWorker(WebClient migrationWebClient,
                           Map<String, CacheItem> store,
                           TimeWheelEvictionService timeWheelEvictionService,
                           LruEvictionService lruEvictionService,
                           AtomicLong currentMemoryUsage,
                           MigrationProperties properties,
                           MeterRegistry meterRegistry) {
        this.migrationWebClient = migrationWebClient;
        this.store = store;
        this.timeWheelEvictionService = timeWheelEvictionService;
        this.lruEvictionService = lruEvictionService;
        this.currentMemoryUsage = currentMemoryUsage;
        this.properties = properties;

        this.successCounter = Counter.builder(CACHE_MIGRATION_KEYS_METRIC)
                .tag(RESULT_TAG, "success")
                .register(meterRegistry);
        this.failedCounter = Counter.builder(CACHE_MIGRATION_KEYS_METRIC)
                .tag(RESULT_TAG, "failed")
                .register(meterRegistry);
        this.skippedCounter = Counter.builder(CACHE_MIGRATION_KEYS_METRIC)
                .tag(RESULT_TAG, "skipped")
                .register(meterRegistry);
        this.retryCounter = Counter.builder("cache_migration_retries_total")
                .register(meterRegistry);
        this.migrationTimer = Timer.builder("cache_migration_duration_seconds")
                .register(meterRegistry);

        meterRegistry.gauge("cache_migration_queue_size", queueSize);
    }

    public void startMigration(Map<NodeInfoHashAdapter, List<MigrationEntry>> plan) {
        int totalKeys = plan.values().stream().mapToInt(List::size).sum();
        queueSize.set(totalKeys);
        cancelled = false;

        executor.submit(() -> {
            Timer.Sample sample = Timer.start();
            log.info("Migration started: {} keys to {} nodes", totalKeys, plan.size());

            int success = 0;
            int failed = 0;
            int skipped = 0;

            for (var nodeEntry : plan.entrySet()) {
                if (cancelled) {
                    skipped += nodeEntry.getValue().size();
                    continue;
                }
                int[] result = migrateEntriesForNode(nodeEntry.getKey(), nodeEntry.getValue());
                success += result[0];
                failed += result[1];
                skipped += result[2];
            }

            skippedCounter.increment(skipped);
            sample.stop(migrationTimer);
            queueSize.set(0);

            log.info("Migration complete: {} success, {} failed, {} skipped",
                    success, failed, skipped);
        });
    }

    private int[] migrateEntriesForNode(NodeInfoHashAdapter targetNode, List<MigrationEntry> entries) {
        int success = 0;
        int failed = 0;
        int skipped = 0;
        for (MigrationEntry entry : entries) {
            if (cancelled) {
                skipped++;
                queueSize.decrementAndGet();
                continue;
            }
            if (migrateKeyWithRetry(targetNode, entry)) {
                success++;
                successCounter.increment();
                if (entry.deleteAfterPush()) {
                    deleteLocalKey(entry.key());
                } else {
                    log.debug("Seeded key={} to new node {} (keeping local copy)", entry.key(), targetNode.getNodeId());
                }
            } else {
                failed++;
                failedCounter.increment();
            }
            queueSize.decrementAndGet();
            rateLimitWait();
        }
        return new int[]{success, failed, skipped};
    }

    private boolean migrateKeyWithRetry(NodeInfoHashAdapter target, MigrationEntry entry) {
        for (int attempt = 0; attempt < properties.getMaxRetries(); attempt++) {
            if (cancelled) {
                return false;
            }
            try {
                pushKey(target, entry);
                return true;
            } catch (Exception e) {
                if (attempt > 0) {
                    retryCounter.increment();
                }
                log.warn("Migration failed for key={} to {} (attempt {}/{}): {}",
                        entry.key(), target.getNodeId(), attempt + 1,
                        properties.getMaxRetries(), e.getMessage());

                if (attempt + 1 < properties.getMaxRetries()) {
                    long delay = Math.min(
                            properties.getBackoffBaseMs() * (1L << attempt),
                            properties.getBackoffMaxMs()
                    );
                    sleepMs(delay);
                }
            }
        }
        log.warn("Max retries exhausted for key={}, skipping", entry.key());
        return false;
    }

    private void pushKey(NodeInfoHashAdapter target, MigrationEntry entry) {
        String url = String.format("http://%s:%d/api/v1/internal/cache/%s",
                target.getHost(), target.getServicePort(), entry.key());

        CacheItem item = entry.item();

        migrationWebClient.put()
                .uri(url)
                .header("X-Quorum-Version", String.valueOf(item.getVersion()))
                .header("X-Expires-At", String.valueOf(item.getExpiryTime()))
                .header("Content-Type", item.getContentType())
                .bodyValue(item.getData())
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(5));
    }

    private void deleteLocalKey(String key) {
        long delayMs = properties.getDeleteDelayMs();
        if (delayMs <= 0) {
            performDelete(key);
        } else {
            // Delay deletion so the LB ring-sync has time to discover the new owner
            // before the old replica disappears.  The default delay (15 s) covers
            // three LB sync cycles (default 5 s each).
            deleteScheduler.schedule(() -> performDelete(key), delayMs, TimeUnit.MILLISECONDS);
            log.debug("Scheduled delayed deletion of key={} in {}ms", key, delayMs);
        }
    }

    private void performDelete(String key) {
        CacheItem removed = store.remove(key);
        if (removed != null) {
            timeWheelEvictionService.removeKey(key);
            lruEvictionService.removeEntry(key);
            currentMemoryUsage.addAndGet(-removed.getMemorySize());
            log.debug("Migrated and deleted local key: {}", key);
        }
    }

    public void cancelCurrentMigration() {
        cancelled = true;
    }

    private void rateLimitWait() {
        if (properties.getRateLimit() > 0) {
            long intervalNanos = TimeUnit.SECONDS.toNanos(1) / properties.getRateLimit();
            long start = System.nanoTime();
            //noinspection StatementWithEmptyBody
            while (System.nanoTime() - start < intervalNanos) {
                // busy-wait for sub-ms precision at 500 keys/sec (~2ms interval)
            }
        }
    }

    private void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void shutdown() {
        cancelled = true;
        executor.shutdown();
        deleteScheduler.shutdown();
    }
}
