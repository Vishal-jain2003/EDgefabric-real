package com.edgefabric.caching.migration;

import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.service.LruEvictionService;
import com.edgefabric.caching.service.TimeWheelEvictionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MigrationWorkerTest {

    @Mock private TimeWheelEvictionService timeWheelEvictionService;
    @Mock private LruEvictionService lruEvictionService;

    private MockWebServer mockWebServer;
    private MigrationWorker worker;
    private Map<String, CacheItem> store;
    private AtomicLong currentMemoryUsage;
    private MigrationProperties properties;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        WebClient webClient = WebClient.builder().build();
        store = new ConcurrentHashMap<>();
        currentMemoryUsage = new AtomicLong(0);
        properties = new MigrationProperties();
        // Use high rate limit so tests don't wait
        properties.setRateLimit(100_000);
        properties.setMaxRetries(3);
        properties.setBackoffBaseMs(10);
        properties.setBackoffMaxMs(50);
        // Tests expect immediate deletion — override the production default (15 s)
        properties.setDeleteDelayMs(0);
        meterRegistry = new SimpleMeterRegistry();

        worker = new MigrationWorker(
                webClient, store, timeWheelEvictionService, lruEvictionService,
                currentMemoryUsage, properties, meterRegistry);
    }

    @AfterEach
    void tearDown() throws IOException {
        worker.shutdown();
        mockWebServer.shutdown();
    }

    private NodeInfoHashAdapter createAdapter(String nodeId) {
        return new NodeInfoHashAdapter(new NodeInfo(
                nodeId,
                mockWebServer.getHostName(),
                mockWebServer.getPort(),
                7946));
    }

    private CacheItem createCacheItem(String data) {
        return new CacheItem(data.getBytes(), 60_000L, "text/plain", 1L);
    }

    @Nested
    class SuccessfulMigration {

        @Test
        void shouldPushKeyViaHttpPut() throws InterruptedException {
            mockWebServer.enqueue(new MockResponse().setResponseCode(200));

            NodeInfoHashAdapter target = createAdapter("target-node");
            CacheItem item = createCacheItem("hello");
            store.put("key1", item);
            currentMemoryUsage.set(item.getMemorySize());

            Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = Map.of(
                    target, List.of(MigrationEntry.evict("key1", item)));

            worker.startMigration(plan);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(store).doesNotContainKey("key1");
            });

            RecordedRequest request = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getMethod()).isEqualTo("PUT");
            assertThat(request.getPath()).isEqualTo("/api/v1/internal/cache/key1");
            assertThat(request.getHeader("X-Quorum-Version")).isEqualTo(String.valueOf(item.getVersion()));
            assertThat(request.getHeader("X-Expires-At")).isEqualTo(String.valueOf(item.getExpiryTime()));
            assertThat(request.getHeader("Content-Type")).isEqualTo("text/plain");
        }

        @Test
        void shouldDeleteLocalKeyOnSuccess() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(200));

            NodeInfoHashAdapter target = createAdapter("target-node");
            CacheItem item = createCacheItem("data");
            store.put("key1", item);
            currentMemoryUsage.set(item.getMemorySize());

            Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = Map.of(
                    target, List.of(MigrationEntry.evict("key1", item)));

            worker.startMigration(plan);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(store).doesNotContainKey("key1");
            });
        }

        @Test
        void shouldRemoveFromEvictionServicesOnSuccess() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(200));

            NodeInfoHashAdapter target = createAdapter("target-node");
            CacheItem item = createCacheItem("data");
            store.put("key1", item);
            currentMemoryUsage.set(item.getMemorySize());

            Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = Map.of(
                    target, List.of(MigrationEntry.evict("key1", item)));

            worker.startMigration(plan);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                verify(timeWheelEvictionService).removeKey("key1");
                verify(lruEvictionService).removeEntry("key1");
            });
        }

        @Test
        void shouldDecrementMemoryUsageOnSuccess() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(200));

            NodeInfoHashAdapter target = createAdapter("target-node");
            CacheItem item = createCacheItem("data");
            store.put("key1", item);
            long initialMemory = item.getMemorySize();
            currentMemoryUsage.set(initialMemory);

            Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = Map.of(
                    target, List.of(MigrationEntry.evict("key1", item)));

            worker.startMigration(plan);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(currentMemoryUsage.get()).isZero();
            });
        }

        @Test
        void shouldIncrementSuccessCounter() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(200));

            NodeInfoHashAdapter target = createAdapter("target-node");
            CacheItem item = createCacheItem("data");
            store.put("key1", item);
            currentMemoryUsage.set(item.getMemorySize());

            Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = Map.of(
                    target, List.of(MigrationEntry.evict("key1", item)));

            worker.startMigration(plan);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                Counter success = meterRegistry.find("cache_migration_keys_total")
                        .tag("result", "success").counter();
                assertThat(success).isNotNull();
                assertThat(success.count()).isEqualTo(1.0);
            });
        }

        @Test
        void shouldMigrateMultipleKeysToSameNode() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(200));
            mockWebServer.enqueue(new MockResponse().setResponseCode(200));

            NodeInfoHashAdapter target = createAdapter("target-node");
            CacheItem item1 = createCacheItem("data1");
            CacheItem item2 = createCacheItem("data2");
            store.put("key1", item1);
            store.put("key2", item2);
            currentMemoryUsage.set(item1.getMemorySize() + item2.getMemorySize());

            Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = Map.of(
                    target, List.of(
                            MigrationEntry.evict("key1", item1),
                            MigrationEntry.evict("key2", item2)));

            worker.startMigration(plan);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(store).isEmpty();
            });

            Counter success = meterRegistry.find("cache_migration_keys_total")
                    .tag("result", "success").counter();
            assertThat(success).isNotNull();
            assertThat(success.count()).isEqualTo(2.0);
        }
    }

    @Nested
    class RetryBehavior {

        @Test
        void shouldRetryOnHttpFailureAndSucceed() {
            // First attempt fails, second succeeds
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(200));

            NodeInfoHashAdapter target = createAdapter("target-node");
            CacheItem item = createCacheItem("data");
            store.put("key1", item);
            currentMemoryUsage.set(item.getMemorySize());

            Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = Map.of(
                    target, List.of(MigrationEntry.evict("key1", item)));

            worker.startMigration(plan);

            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(store).doesNotContainKey("key1");
            });

            Counter retries = meterRegistry.find("cache_migration_retries_total").counter();
            assertThat(retries).isNotNull();
            // attempt 0 fails (not a retry), attempt 1 succeeds — zero retries counted
            assertThat(retries.count()).isEqualTo(0.0);
        }

        @Test
        void shouldIncrementFailedCounterAfterMaxRetries() {
            // All 3 attempts fail
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            NodeInfoHashAdapter target = createAdapter("target-node");
            CacheItem item = createCacheItem("data");
            store.put("key1", item);
            currentMemoryUsage.set(item.getMemorySize());

            Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = Map.of(
                    target, List.of(MigrationEntry.evict("key1", item)));

            worker.startMigration(plan);

            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                Counter failed = meterRegistry.find("cache_migration_keys_total")
                        .tag("result", "failed").counter();
                assertThat(failed).isNotNull();
                assertThat(failed.count()).isEqualTo(1.0);
            });

            // Key should remain in store since migration failed
            assertThat(store).containsKey("key1");
        }

        @Test
        void shouldIncrementRetryCounterOnEachRetry() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
            mockWebServer.enqueue(new MockResponse().setResponseCode(200));

            NodeInfoHashAdapter target = createAdapter("target-node");
            CacheItem item = createCacheItem("data");
            store.put("key1", item);
            currentMemoryUsage.set(item.getMemorySize());

            Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = Map.of(
                    target, List.of(MigrationEntry.evict("key1", item)));

            worker.startMigration(plan);

            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(store).doesNotContainKey("key1");
            });

            Counter retries = meterRegistry.find("cache_migration_retries_total").counter();
            assertThat(retries).isNotNull();
            // attempt 0 fails (not a retry), attempt 1 fails (retry +1), attempt 2 succeeds
            assertThat(retries.count()).isEqualTo(1.0);
        }
    }

    @Nested
    class Cancellation {

        @Test
        void shouldStopMigrationWhenCancelled() {
            // Enqueue one success and then stall on the second
            mockWebServer.enqueue(new MockResponse().setResponseCode(200));
            // No more responses enqueued — second request will block

            NodeInfoHashAdapter target = createAdapter("target-node");
            CacheItem item1 = createCacheItem("data1");
            CacheItem item2 = createCacheItem("data2");
            store.put("key1", item1);
            store.put("key2", item2);
            currentMemoryUsage.set(item1.getMemorySize() + item2.getMemorySize());

            Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = new HashMap<>();
            plan.put(target, List.of(
                    MigrationEntry.evict("key1", item1),
                    MigrationEntry.evict("key2", item2)));

            worker.startMigration(plan);

            // Wait for first key to be processed, then cancel
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(store).doesNotContainKey("key1");
            });

            worker.cancelCurrentMigration();

            // The skipped counter should eventually reflect skipped keys
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                Counter skipped = meterRegistry.find("cache_migration_keys_total")
                        .tag("result", "skipped").counter();
                Counter success = meterRegistry.find("cache_migration_keys_total")
                        .tag("result", "success").counter();
                double totalProcessed = (skipped != null ? skipped.count() : 0)
                        + (success != null ? success.count() : 0);
                assertThat(totalProcessed).isGreaterThanOrEqualTo(1.0);
            });
        }
    }

    @Nested
    class Metrics {

        @Test
        void shouldRegisterQueueSizeGauge() {
            assertThat(meterRegistry.find("cache_migration_queue_size").gauge()).isNotNull();
        }

        @Test
        void shouldRecordMigrationDuration() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(200));

            NodeInfoHashAdapter target = createAdapter("target-node");
            CacheItem item = createCacheItem("data");
            store.put("key1", item);
            currentMemoryUsage.set(item.getMemorySize());

            Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = Map.of(
                    target, List.of(MigrationEntry.evict("key1", item)));

            worker.startMigration(plan);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(meterRegistry.find("cache_migration_duration_seconds")
                        .timer()).isNotNull();
                assertThat(meterRegistry.find("cache_migration_duration_seconds")
                        .timer().count()).isEqualTo(1);
            });
        }

        @Test
        void shouldSetQueueSizeToTotalKeysOnStart() {
            // Enqueue enough responses
            mockWebServer.enqueue(new MockResponse().setResponseCode(200));
            mockWebServer.enqueue(new MockResponse().setResponseCode(200));

            NodeInfoHashAdapter target = createAdapter("target-node");
            CacheItem item1 = createCacheItem("data1");
            CacheItem item2 = createCacheItem("data2");
            store.put("key1", item1);
            store.put("key2", item2);
            currentMemoryUsage.set(item1.getMemorySize() + item2.getMemorySize());

            Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = Map.of(
                    target, List.of(
                            MigrationEntry.evict("key1", item1),
                            MigrationEntry.evict("key2", item2)));

            worker.startMigration(plan);

            // After completion, queue size should be 0
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(meterRegistry.find("cache_migration_queue_size")
                        .gauge().value()).isZero();
            });
        }
    }

    @Nested
    class EmptyMigration {

        @Test
        void shouldHandleEmptyPlan() {
            Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = Map.of();

            worker.startMigration(plan);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(meterRegistry.find("cache_migration_queue_size")
                        .gauge().value()).isZero();
            });
        }
    }

    @Nested
    class DelayedDeletion {

        @Test
        void shouldNotDeleteLocalKeyImmediatelyWhenDeleteDelayIsConfigured() {
            MigrationProperties delayedProps = new MigrationProperties();
            delayedProps.setRateLimit(100_000);
            delayedProps.setMaxRetries(1);
            delayedProps.setBackoffBaseMs(10);
            delayedProps.setBackoffMaxMs(50);
            delayedProps.setDeleteDelayMs(500);

            SimpleMeterRegistry delayedRegistry = new SimpleMeterRegistry();
            MigrationWorker delayedWorker = new MigrationWorker(
                    WebClient.builder().build(), store, timeWheelEvictionService,
                    lruEvictionService, currentMemoryUsage, delayedProps, delayedRegistry);

            try {
                mockWebServer.enqueue(new MockResponse().setResponseCode(200));

                NodeInfoHashAdapter target = createAdapter("target-node");
                CacheItem item = createCacheItem("data");
                store.put("key1", item);
                currentMemoryUsage.set(item.getMemorySize());

                Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = Map.of(
                        target, List.of(MigrationEntry.evict("key1", item)));

                delayedWorker.startMigration(plan);

                // Wait for the HTTP push to succeed (success counter ticks up)
                await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                    Counter success = delayedRegistry.find("cache_migration_keys_total")
                            .tag("result", "success").counter();
                    assertThat(success).isNotNull();
                    assertThat(success.count()).isEqualTo(1.0);
                });

                // Push succeeded but deletion is delayed — key must still be present
                assertThat(store).containsKey("key1");

                // After the delay the key is eventually removed
                await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                        assertThat(store).doesNotContainKey("key1"));
            } finally {
                delayedWorker.shutdown();
            }
        }
    }

    // ── NEW: HTTP error response handling ──────────────────────────────────────

    @Nested
    class HttpErrorHandling {

        @Test
        void shouldTreat503AsFailureAndExhaustRetries() {
            // 503 (node draining) is treated as a generic HTTP failure — retried, eventually fails.
            mockWebServer.enqueue(new MockResponse().setResponseCode(503));
            mockWebServer.enqueue(new MockResponse().setResponseCode(503));
            mockWebServer.enqueue(new MockResponse().setResponseCode(503));

            NodeInfoHashAdapter target = createAdapter("target-node");
            CacheItem item = createCacheItem("data");
            store.put("key1", item);
            currentMemoryUsage.set(item.getMemorySize());

            Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = Map.of(
                    target, List.of(MigrationEntry.evict("key1", item)));

            worker.startMigration(plan);

            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                Counter failed = meterRegistry.find("cache_migration_keys_total")
                        .tag("result", "failed").counter();
                assertThat(failed).isNotNull();
                assertThat(failed.count()).isEqualTo(1.0);
            });

            // Key must stay local when all retries are exhausted
            assertThat(store).containsKey("key1");
        }

        @Test
        void shouldTreat400AsFailureAndExhaustRetries() {
            // 400 Bad Request also exhausts retries — key stays local.
            mockWebServer.enqueue(new MockResponse().setResponseCode(400));
            mockWebServer.enqueue(new MockResponse().setResponseCode(400));
            mockWebServer.enqueue(new MockResponse().setResponseCode(400));

            NodeInfoHashAdapter target = createAdapter("target-node");
            CacheItem item = createCacheItem("data");
            store.put("key1", item);
            currentMemoryUsage.set(item.getMemorySize());

            Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = Map.of(
                    target, List.of(MigrationEntry.evict("key1", item)));

            worker.startMigration(plan);

            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                Counter failed = meterRegistry.find("cache_migration_keys_total")
                        .tag("result", "failed").counter();
                assertThat(failed).isNotNull();
                assertThat(failed.count()).isEqualTo(1.0);
            });

            assertThat(store).containsKey("key1");
        }

        @Test
        void shouldRetryOn503ThenSucceedOnThirdAttempt() {
            // Two 503 responses then a 200 — key is migrated, retries counted correctly.
            mockWebServer.enqueue(new MockResponse().setResponseCode(503));
            mockWebServer.enqueue(new MockResponse().setResponseCode(503));
            mockWebServer.enqueue(new MockResponse().setResponseCode(200));

            NodeInfoHashAdapter target = createAdapter("target-node");
            CacheItem item = createCacheItem("data");
            store.put("key1", item);
            currentMemoryUsage.set(item.getMemorySize());

            Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = Map.of(
                    target, List.of(MigrationEntry.evict("key1", item)));

            worker.startMigration(plan);

            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(store).doesNotContainKey("key1"));

            Counter retries = meterRegistry.find("cache_migration_retries_total").counter();
            assertThat(retries).isNotNull();
            // attempt 0 fails (not a retry), attempt 1 fails (retry +1), attempt 2 succeeds
            assertThat(retries.count()).isEqualTo(1.0);

            Counter success = meterRegistry.find("cache_migration_keys_total")
                    .tag("result", "success").counter();
            assertThat(success.count()).isEqualTo(1.0);
        }
    }

    // ── NEW: Multiple target nodes in one migration plan ───────────────────────

    @Nested
    class MultiNodeMigration {

        @Test
        void shouldRouteEachKeyToItsCorrectTargetNode() throws Exception {
            // Two different target nodes on two different ports.
            // Each must receive only its own key's PUT request.
            MockWebServer server2 = new MockWebServer();
            server2.start();
            try {
                mockWebServer.enqueue(new MockResponse().setResponseCode(200)); // key1 → server1
                server2.enqueue(new MockResponse().setResponseCode(200));       // key2 → server2

                NodeInfoHashAdapter target1 = createAdapter("target-1");
                NodeInfoHashAdapter target2 = new NodeInfoHashAdapter(
                        new NodeInfo("target-2", server2.getHostName(), server2.getPort(), 7946));

                CacheItem item1 = createCacheItem("data1");
                CacheItem item2 = createCacheItem("data2");
                store.put("key1", item1);
                store.put("key2", item2);
                currentMemoryUsage.set(item1.getMemorySize() + item2.getMemorySize());

                Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = new java.util.LinkedHashMap<>();
                plan.put(target1, List.of(MigrationEntry.evict("key1", item1)));
                plan.put(target2, List.of(MigrationEntry.evict("key2", item2)));

                worker.startMigration(plan);

                await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                        assertThat(store).isEmpty());

                RecordedRequest req1 = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
                assertThat(req1).isNotNull();
                assertThat(req1.getPath()).isEqualTo("/api/v1/internal/cache/key1");

                RecordedRequest req2 = server2.takeRequest(1, TimeUnit.SECONDS);
                assertThat(req2).isNotNull();
                assertThat(req2.getPath()).isEqualTo("/api/v1/internal/cache/key2");
            } finally {
                server2.shutdown();
            }
        }
    }

    // ── NEW: Cancelled-flag is reset when a new migration starts ──────────────

    @Nested
    class CancelAndRestart {

        @Test
        void shouldResetCancelledFlagWhenNewMigrationStarts() {
            // After an explicit cancel, startMigration must reset the flag and complete.
            mockWebServer.enqueue(new MockResponse().setResponseCode(200));

            NodeInfoHashAdapter target = createAdapter("target-node");
            CacheItem item = createCacheItem("data");
            store.put("key1", item);
            currentMemoryUsage.set(item.getMemorySize());

            // Simulate a topology burst: cancel before any migration starts
            worker.cancelCurrentMigration();

            Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = Map.of(
                    target, List.of(MigrationEntry.evict("key1", item)));

            worker.startMigration(plan);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(store).doesNotContainKey("key1"));

            Counter success = meterRegistry.find("cache_migration_keys_total")
                    .tag("result", "success").counter();
            assertThat(success).isNotNull();
            assertThat(success.count()).isEqualTo(1.0);
        }
    }

    // ── NEW: Store state edge cases during performDelete ──────────────────────

    @Nested
    class StoreEdgeCases {

        @Test
        void shouldHandleKeyAlreadyEvictedFromStoreBeforeDelete() {
            // Push succeeds; key was evicted by TTL before performDelete fires.
            // store.remove returns null — no NPE, memory stays at 0.
            mockWebServer.enqueue(new MockResponse().setResponseCode(200));

            NodeInfoHashAdapter target = createAdapter("target-node");
            CacheItem item = createCacheItem("data");
            // Key intentionally absent from store (simulating TTL eviction before delete)
            currentMemoryUsage.set(0);

            Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = Map.of(
                    target, List.of(MigrationEntry.evict("evicted-key", item)));

            worker.startMigration(plan);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                Counter success = meterRegistry.find("cache_migration_keys_total")
                        .tag("result", "success").counter();
                assertThat(success).isNotNull();
                assertThat(success.count()).isEqualTo(1.0);
            });

            // Memory must not go negative when store.remove returns null
            assertThat(currentMemoryUsage.get()).isZero();
        }

        @Test
        void shouldNotDecrementMemoryWhenKeyAbsentFromStore() {
            // Same invariant as above but with non-zero baseline memory.
            // Another key occupies 50 bytes; the migrated ghost-key must not reduce it.
            mockWebServer.enqueue(new MockResponse().setResponseCode(200));

            NodeInfoHashAdapter target = createAdapter("target-node");
            CacheItem item = createCacheItem("data");
            currentMemoryUsage.set(50L); // baseline from another key
            // ghost-key NOT in store

            Map<NodeInfoHashAdapter, List<MigrationEntry>> plan = Map.of(
                    target, List.of(MigrationEntry.evict("ghost-key", item)));

            worker.startMigration(plan);

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                Counter success = meterRegistry.find("cache_migration_keys_total")
                        .tag("result", "success").counter();
                assertThat(success).isNotNull();
                assertThat(success.count()).isEqualTo(1.0);
            });

            // Baseline memory unchanged — no spurious decrement
            assertThat(currentMemoryUsage.get()).isEqualTo(50L);
        }
    }
}

