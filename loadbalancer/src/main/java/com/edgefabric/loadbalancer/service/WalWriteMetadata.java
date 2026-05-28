package com.edgefabric.loadbalancer.service;

import java.util.Set;

/**
 * Metadata returned from {@link QuorumService#quorumWrite} describing the outcome
 * of a distributed write operation.
 *
 * <p>Used to populate the enhanced WAL entry for targeted anti-entropy repair.
 *
 * @param version         the monotonic version assigned to this write
 * @param successfulNodes node IDs that successfully acknowledged the write
 * @param failedNodes     node IDs that failed (timeout, error, or unreachable)
 */
public record WalWriteMetadata(
        long version,
        Set<String> successfulNodes,
        Set<String> failedNodes
) {
    /**
     * Returns true if any nodes failed to acknowledge the write.
     * These nodes need targeted repair via anti-entropy.
     */
    public boolean hasFailures() {
        return failedNodes != null && !failedNodes.isEmpty();
    }
}
