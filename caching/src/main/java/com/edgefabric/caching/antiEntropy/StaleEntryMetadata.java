package com.edgefabric.caching.antiEntropy;

/**
 * Metadata about a stale cache entry.
 *
 * @param version    The quorum version of the entry when staleness was detected
 * @param reason     Human-readable reason for staleness (e.g., "local_write_failed", "node_rejoin")
 * @param detectedAt Timestamp (milliseconds) when staleness was detected
 */
public record StaleEntryMetadata(long version, String reason, long detectedAt) {

    public StaleEntryMetadata {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Reason must not be null or blank");
        }
        if (detectedAt <= 0) {
            throw new IllegalArgumentException("DetectedAt must be positive");
        }
    }
}
