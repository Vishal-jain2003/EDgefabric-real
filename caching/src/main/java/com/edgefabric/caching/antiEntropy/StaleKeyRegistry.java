package com.edgefabric.caching.antiEntropy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for stale cache keys.
 *
 * <p>Tracks keys that need repair due to:
 * <ul>
 *   <li>Local write failures</li>
 *   <li>Node rejoin (suspect data)</li>
 *   <li>Self-healing retry failures</li>
 * </ul>
 *
 * <p>Memory bounded: max 10,000 entries (~10MB overhead).
 */
@Slf4j
@Service
public class StaleKeyRegistry {

    private static final int MAX_ENTRIES = 10_000;

    private final ConcurrentHashMap<String, StaleEntryMetadata> staleKeys = new ConcurrentHashMap<>();

    /**
     * Marks a key as stale.
     *
     * <p>If the key already exists, updates the metadata (version, reason, timestamp).
     *
     * @param key     The cache key
     * @param version The quorum version when staleness was detected
     * @param reason  Human-readable reason for staleness
     */
    public void markStale(String key, long version, String reason) {
        if (key == null || key.isBlank()) {
            log.warn("Attempted to mark null or blank key as stale");
            return;
        }

        if (staleKeys.size() >= MAX_ENTRIES && !staleKeys.containsKey(key)) {
            log.warn("StaleKeyRegistry at max capacity ({}). Dropping mark for key={}", MAX_ENTRIES, key);
            return;
        }

        long detectedAt = System.currentTimeMillis();
        StaleEntryMetadata metadata = new StaleEntryMetadata(version, reason, detectedAt);
        staleKeys.put(key, metadata);

        log.debug("Marked key={} as stale (version={}, reason={})", key, version, reason);
    }

    /**
     * Drains up to {@code limit} stale keys.
     *
     * <p>Atomically removes and returns entries from the registry. Thread-safe for concurrent drains.
     *
     * @param limit Maximum number of keys to drain
     * @return List of stale entries (empty if none)
     */
    public List<StaleEntry> drainStaleKeys(int limit) {
        if (limit <= 0) {
            return List.of();
        }

        List<StaleEntry> drained = new ArrayList<>();

        // Atomically drain keys one-by-one to avoid duplicate drains across concurrent threads
        for (var entry : staleKeys.entrySet()) {
            if (drained.size() >= limit) {
                break;
            }

            String key = entry.getKey();
            // Try to atomically remove this key. If another thread already removed it, skip.
            StaleEntryMetadata metadata = staleKeys.remove(key);

            if (metadata != null) {
                drained.add(new StaleEntry(key, metadata.version(), metadata.reason()));
            }
        }

        if (!drained.isEmpty()) {
            log.debug("Drained {} stale keys (registry size now: {})", drained.size(), staleKeys.size());
        }

        return drained;
    }

    /**
     * Clears all stale entries.
     */
    public void clear() {
        int size = staleKeys.size();
        staleKeys.clear();
        log.debug("Cleared StaleKeyRegistry ({} entries removed)", size);
    }

    /**
     * Returns the current number of stale keys.
     */
    public int size() {
        return staleKeys.size();
    }
}
