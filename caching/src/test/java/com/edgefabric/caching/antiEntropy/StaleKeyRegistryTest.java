package com.edgefabric.caching.antiEntropy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StaleKeyRegistry}.
 *
 * <p>Tests thread-safety, concurrent marking, drain atomicity, and memory bounds.</p>
 */
@ExtendWith(MockitoExtension.class)
class StaleKeyRegistryTest {

    private StaleKeyRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new StaleKeyRegistry();
    }

    // ── Basic Operations ──────────────────────────────────────────────────────

    @Test
    void markStale_shouldAddEntry() {
        // when
        registry.markStale("key1", 100L, "test_reason");

        // then
        List<StaleEntry> entries = registry.drainStaleKeys(10);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).key()).isEqualTo("key1");
        assertThat(entries.get(0).version()).isEqualTo(100L);
        assertThat(entries.get(0).reason()).isEqualTo("test_reason");
    }

    @Test
    void markStale_multipleKeys_shouldStoreAll() {
        // when
        registry.markStale("key1", 100L, "reason1");
        registry.markStale("key2", 200L, "reason2");
        registry.markStale("key3", 300L, "reason3");

        // then
        List<StaleEntry> entries = registry.drainStaleKeys(10);
        assertThat(entries).hasSize(3);
    }

    @Test
    void markStale_sameKeyMultipleTimes_shouldUpdateMetadata() {
        // when
        registry.markStale("key1", 100L, "reason1");
        registry.markStale("key1", 200L, "reason2");

        // then
        List<StaleEntry> entries = registry.drainStaleKeys(10);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).version()).isEqualTo(200L);
        assertThat(entries.get(0).reason()).isEqualTo("reason2");
    }

    // ── Drain Operations ──────────────────────────────────────────────────────

    @Test
    void drainStaleKeys_shouldRemoveEntries() {
        // given
        registry.markStale("key1", 100L, "reason1");
        registry.markStale("key2", 200L, "reason2");

        // when
        List<StaleEntry> firstDrain = registry.drainStaleKeys(10);
        List<StaleEntry> secondDrain = registry.drainStaleKeys(10);

        // then
        assertThat(firstDrain).hasSize(2);
        assertThat(secondDrain).isEmpty();
    }

    @Test
    void drainStaleKeys_withLimit_shouldRespectBatchSize() {
        // given
        for (int i = 0; i < 50; i++) {
            registry.markStale("key" + i, i, "reason" + i);
        }

        // when
        List<StaleEntry> batch1 = registry.drainStaleKeys(10);
        List<StaleEntry> batch2 = registry.drainStaleKeys(10);

        // then
        assertThat(batch1).hasSize(10);
        assertThat(batch2).hasSize(10);
    }

    @Test
    void drainStaleKeys_emptyRegistry_shouldReturnEmptyList() {
        // when
        List<StaleEntry> entries = registry.drainStaleKeys(10);

        // then
        assertThat(entries).isEmpty();
    }

    // ── Clear Operations ──────────────────────────────────────────────────────

    @Test
    void clear_shouldRemoveAllEntries() {
        // given
        registry.markStale("key1", 100L, "reason1");
        registry.markStale("key2", 200L, "reason2");

        // when
        registry.clear();
        List<StaleEntry> entries = registry.drainStaleKeys(10);

        // then
        assertThat(entries).isEmpty();
    }

    // ── Thread Safety ─────────────────────────────────────────────────────────

    @Test
    void concurrentMarking_shouldBeThreadSafe() throws InterruptedException {
        // given
        int threadCount = 10;
        int keysPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // when
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < keysPerThread; i++) {
                        String key = "thread" + threadId + "-key" + i;
                        registry.markStale(key, i, "concurrent_test");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // then
        List<StaleEntry> entries = registry.drainStaleKeys(threadCount * keysPerThread);
        assertThat(entries).hasSize(threadCount * keysPerThread);
    }

    @Test
    void concurrentDrain_shouldBeThreadSafe() throws InterruptedException {
        // given
        for (int i = 0; i < 100; i++) {
            registry.markStale("key" + i, i, "reason" + i);
        }

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Integer> drainedCounts = new java.util.concurrent.CopyOnWriteArrayList<>();

        // when
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    List<StaleEntry> drained = registry.drainStaleKeys(30);
                    drainedCounts.add(drained.size());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // then
        int totalDrained = drainedCounts.stream().mapToInt(Integer::intValue).sum();
        assertThat(totalDrained).isEqualTo(100);
    }

    // ── Size and Bounds ───────────────────────────────────────────────────────

    @Test
    void size_shouldReturnCurrentSize() {
        // given
        registry.markStale("key1", 100L, "reason1");
        registry.markStale("key2", 200L, "reason2");

        // when
        int size = registry.size();

        // then
        assertThat(size).isEqualTo(2);
    }

    @Test
    void size_afterDrain_shouldUpdateCorrectly() {
        // given
        registry.markStale("key1", 100L, "reason1");
        registry.markStale("key2", 200L, "reason2");
        registry.drainStaleKeys(1);

        // when
        int size = registry.size();

        // then
        assertThat(size).isEqualTo(1);
    }
}
