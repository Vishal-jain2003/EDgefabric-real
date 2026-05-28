package com.edgefabric.caching.service;

import com.edgefabric.caching.model.CacheItem;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SamplingBasedEvictionServiceTest {

    private final SamplingBasedEvictionService samplingBasedEviction = new SamplingBasedEvictionService();

    // Both helpers use identical data + contentType → identical memorySize (keeps math clean)
    private static CacheItem freshItem() {
        return new CacheItem("data".getBytes(StandardCharsets.UTF_8),
                System.currentTimeMillis() + 60_000,
                "text", System.nanoTime(), true);
    }

    private static CacheItem expiredItem() {
        return new CacheItem("data".getBytes(StandardCharsets.UTF_8),
                System.currentTimeMillis() - 1_000,
                "text", System.nanoTime(), true);
    }

    // Ensures eviction reduces memory when limit is exceeded
    @Test
    void shouldEvictWhenMemoryExceedsLimit() {
        Map<String, CacheItem> store = new HashMap<>();

        long singleItemSize = freshItem().getMemorySize();
        long maxMemoryBytes = singleItemSize * 10;

        // Fill beyond memory limit (15 items)
        for (int i = 0; i < 15; i++) {
            store.put("k" + i, freshItem());
        }
        AtomicLong currentMemoryUsage = new AtomicLong(singleItemSize * 15);

        samplingBasedEviction.evictIfRequired(store, currentMemoryUsage, maxMemoryBytes);

        assertTrue(currentMemoryUsage.get() <= maxMemoryBytes);
    }

    // Should not crash on empty store
    @Test
    void shouldHandleEmptyStoreGracefully() {
        Map<String, CacheItem> store = new HashMap<>();
        AtomicLong currentMemoryUsage = new AtomicLong(0);

        samplingBasedEviction.evictIfRequired(store, currentMemoryUsage, 10_000);

        assertTrue(store.isEmpty());
        assertEquals(0, currentMemoryUsage.get());
    }

    @Test
    void shouldThrowWhenMaxMemoryBytesIsZero() {
        Map<String, CacheItem> store = new HashMap<>();
        store.put("k1", freshItem());
        AtomicLong currentMemoryUsage = new AtomicLong(100);

        assertThrows(IllegalArgumentException.class,
                () -> samplingBasedEviction.evictIfRequired(store, currentMemoryUsage, 0));
    }

    @Test
    void shouldThrowWhenMaxMemoryBytesIsNegative() {
        Map<String, CacheItem> store = new HashMap<>();
        AtomicLong currentMemoryUsage = new AtomicLong(0);

        assertThrows(IllegalArgumentException.class,
                () -> samplingBasedEviction.evictIfRequired(store, currentMemoryUsage, -5));
    }

    @Test
    void shouldNotEvictWhenMemoryIsBelowLimit() {
        Map<String, CacheItem> store = new HashMap<>();
        CacheItem item = freshItem();
        store.put("k1", item);
        AtomicLong currentMemoryUsage = new AtomicLong(item.getMemorySize());

        samplingBasedEviction.evictIfRequired(store, currentMemoryUsage, 10_000);

        assertEquals(1, store.size());
        assertEquals(item.getMemorySize(), currentMemoryUsage.get());
    }

    // Expired entry should be preferred victim over fresh (LRU) entries.
    // Uses 1 fresh + 4 expired: P(all 5 random samples pick "fresh") = (1/5)^5 ≈ 0.03%
    @Test
    void shouldEvictExpiredEntryFirst() {
        Map<String, CacheItem> store = new HashMap<>();
        CacheItem fresh = freshItem();
        store.put("fresh", fresh);

        for (int i = 0; i < 4; i++) {
            store.put("expired" + i, expiredItem());
        }

        // All items have same size → 5 items total
        long singleItemSize = fresh.getMemorySize();
        AtomicLong currentMemoryUsage = new AtomicLong(5 * singleItemSize);
        long maxMemoryBytes = 4 * singleItemSize; // exactly 1 eviction needed

        samplingBasedEviction.evictIfRequired(store, currentMemoryUsage, maxMemoryBytes);

        // Fresh item must survive — expired items are preferred eviction targets
        assertTrue(store.containsKey("fresh"));
        assertTrue(currentMemoryUsage.get() <= maxMemoryBytes);
    }

    // ── evictIfRequired: store empties during eviction loop ──
    // Memory counter exceeds limit but store becomes empty mid-loop → must break without NPE.

    @Test
    void shouldBreakWhenStoreBecomesEmptyDuringLoop() {
        Map<String, CacheItem> store = new HashMap<>();
        CacheItem item = freshItem();
        store.put("k1", item);

        // Drift: counter says 10x the item size, but there is only one item.
        // After one eviction the store is empty; the loop must exit cleanly.
        long singleSize = item.getMemorySize();
        AtomicLong currentMemoryUsage = new AtomicLong(singleSize * 10);

        assertDoesNotThrow(() ->
                samplingBasedEviction.evictIfRequired(store, currentMemoryUsage, singleSize));

        assertTrue(store.isEmpty());
    }

    // ── evictIfRequired: LRU eviction path (all fresh, no expired) ──
    // Confirms the min-lastAccessTime branch is reached when no expired entry exists.

    @Test
    void shouldEvictLruWhenNoExpiredEntries() {
        Map<String, CacheItem> store = new HashMap<>();
        CacheItem item1 = freshItem();
        CacheItem item2 = freshItem();
        store.put("k1", item1);
        store.put("k2", item2);

        long singleSize = item1.getMemorySize();
        AtomicLong currentMemoryUsage = new AtomicLong(singleSize * 2);
        long maxMemoryBytes = singleSize; // force one eviction

        samplingBasedEviction.evictIfRequired(store, currentMemoryUsage, maxMemoryBytes);

        assertEquals(1, store.size());
        assertTrue(currentMemoryUsage.get() <= maxMemoryBytes);
    }

    // ── memory already within limit → no eviction ──

    @Test
    void shouldDoNothingWhenMemoryAlreadyWithinLimit() {
        Map<String, CacheItem> store = new HashMap<>();
        CacheItem item = freshItem();
        store.put("k1", item);

        long size = item.getMemorySize();
        AtomicLong currentMemoryUsage = new AtomicLong(size);

        // Limit is large enough — no eviction
        samplingBasedEviction.evictIfRequired(store, currentMemoryUsage, size * 2);

        assertEquals(1, store.size());
        assertEquals(size, currentMemoryUsage.get());
    }

    // ── eviction decrements memory counter correctly for multiple evictions ──

    @Test
    void shouldDecrementMemoryCounterForEachEviction() {
        Map<String, CacheItem> store = new HashMap<>();
        CacheItem item = freshItem();
        long singleSize = item.getMemorySize();

        for (int i = 0; i < 10; i++) {
            store.put("k" + i, freshItem());
        }
        AtomicLong currentMemoryUsage = new AtomicLong(singleSize * 10);
        long limit = singleSize * 5;

        samplingBasedEviction.evictIfRequired(store, currentMemoryUsage, limit);

        assertTrue(currentMemoryUsage.get() <= limit,
                "memory should have been reduced to at most the limit");
        assertEquals(store.size(), (int)(currentMemoryUsage.get() / singleSize),
                "store size and memory counter must be consistent");
    }
}
