package com.edgefabric.caching.service;

import com.edgefabric.caching.model.CacheItem;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

/**
 * Sampling-based approximate LRU eviction (Redis-style).
 * <p>
 * On each eviction cycle, {@value SAMPLE_SIZE} random entries are drawn from the store.
 * Within the sample, expired entries are preferred victims; otherwise the entry with
 * the oldest {@code lastAccessTime} is evicted.
 * </p>
 *
 * <p><b>Memory-based:</b> eviction continues until {@code currentMemoryUsage <= maxMemoryBytes}.
 * Every removal decrements the shared {@code currentMemoryUsage} counter so the caller's
 * view of memory stays consistent.</p>
 *
 * <p>This class is intentionally <b>disabled</b> in the current system —
 * {@link LruEvictionService} (exact doubly-linked-list LRU) is active instead.
 * {@code SamplingBasedEviction} is kept as a ready-to-plug-in alternative.</p>
 */
public class SamplingBasedEvictionService {

    // Sampling size for approximate LRU
    private static final int SAMPLE_SIZE = 5;

    // Secure random instance (Sonar-safe)
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Evicts entries from the store until memory usage falls within the given limit.
     *
     * @param store              the cache store to evict from
     * @param currentMemoryUsage shared memory counter — decremented on each eviction
     * @param maxMemoryBytes     upper memory bound; must be &gt; 0
     * @throws IllegalArgumentException if {@code maxMemoryBytes} is zero or negative
     */
    public void evictIfRequired(Map<String, CacheItem> store,
                                AtomicLong currentMemoryUsage,
                                long maxMemoryBytes) {
        if (maxMemoryBytes <= 0) {
            throw new IllegalArgumentException("maxMemoryBytes must be greater than 0");
        }
        while (currentMemoryUsage.get() > maxMemoryBytes) {
            if (store.isEmpty()) {
                // Counter drift — nothing left to evict; reconciliation will correct the counter
                break;
            }
            evictOne(store, currentMemoryUsage);
        }
    }

    private void evictOne(Map<String, CacheItem> store, AtomicLong currentMemoryUsage) {
        if (store.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();

        // Snapshot entries — safe for iteration even if store is modified concurrently
        List<Map.Entry<String, CacheItem>> entries = new ArrayList<>(store.entrySet());

        // Guard against concurrent removal between the isEmpty() check and snapshot
        if (entries.isEmpty()) {
            return;
        }

        // Draw SAMPLE_SIZE random candidates (with replacement)
        List<Map.Entry<String, CacheItem>> sample = IntStream.range(0, SAMPLE_SIZE)
                .mapToObj(i -> entries.get(RANDOM.nextInt(entries.size())))
                .toList();

        // Priority 1: evict the first expired candidate immediately
        Optional<Map.Entry<String, CacheItem>> expired = sample.stream()
                .filter(e -> e.getValue().getExpiryTime() < now)
                .findFirst();

        if (expired.isPresent()) {
            CacheItem removed = store.remove(expired.get().getKey());
            if (removed != null) {
                currentMemoryUsage.addAndGet(-removed.getMemorySize());
            }
            return;
        }

        // Priority 2: evict the least-recently-used candidate
        sample.stream()
                .min(Comparator.comparingLong(e -> e.getValue().getLastAccessTime()))
                .ifPresent(victim -> {
                    CacheItem removed = store.remove(victim.getKey());
                    if (removed != null) {
                        currentMemoryUsage.addAndGet(-removed.getMemorySize());
                    }
                });
    }
}
