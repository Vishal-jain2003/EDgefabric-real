package com.edgefabric.loadbalancer.wal;

import java.util.Set;

/**
 * Immutable value object for a single pending WAL operation.
 * LSN is NOT assigned here — the flusher stamps entries just before writing to S3,
 * keeping the hot append path allocation-free.
 *
 * <p>Enhanced to track partial write outcomes for WAL-based anti-entropy:
 * <ul>
 *   <li>{@code successfulNodes} — nodes that successfully stored the entry</li>
 *   <li>{@code failedNodes} — nodes that failed to store (for targeted repair)</li>
 * </ul>
 */
public record WalEntry(
        String key,
        byte[] data,
        long expiresAt,
        String contentType,
        long version,
        OperationType operationType,
        long timestamp,
        Set<String> successfulNodes,
        Set<String> failedNodes
) {
    /**
     * Factory for legacy single-node writes (no quorum tracking).
     * Sets both node sets to empty — used for non-quorum operations.
     */
    public static WalEntry forPut(String key, byte[] data, long expiresAt, String contentType) {
        return new WalEntry(key, data, expiresAt, contentType, 0L, OperationType.PUT,
                System.currentTimeMillis(), Set.of(), Set.of());
    }

    /**
     * Factory for quorum writes with outcome tracking.
     * Records which nodes succeeded/failed for WAL-based anti-entropy.
     *
     * @param successfulNodes node IDs that successfully stored the entry
     * @param failedNodes     node IDs that failed to store (need repair)
     */
    public static WalEntry forQuorumPut(String key, byte[] data, long expiresAt,
                                        String contentType, long version,
                                        Set<String> successfulNodes, Set<String> failedNodes) {
        return new WalEntry(key, data, expiresAt, contentType, version, OperationType.PUT,
                System.currentTimeMillis(), successfulNodes, failedNodes);
    }

    /**
     * Returns true if this entry has failed nodes that need repair.
     */
    public boolean hasStaleNodes() {
        return failedNodes != null && !failedNodes.isEmpty();
    }
}
