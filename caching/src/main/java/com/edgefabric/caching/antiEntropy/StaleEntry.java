package com.edgefabric.caching.antiEntropy;

/**
 * Represents a stale cache entry for self-healing.
 *
 * @param key     The cache key
 * @param version The quorum version when staleness was detected
 * @param reason  Why this entry is stale
 */
public record StaleEntry(String key, long version, String reason) {

    public StaleEntry {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Key must not be null or blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Reason must not be null or blank");
        }
    }
}
