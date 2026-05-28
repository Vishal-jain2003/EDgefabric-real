package com.edgefabric.caching.migration;

import com.edgefabric.caching.model.CacheItem;

/**
 * A key-value pair queued for migration to another node.
 *
 * <p>{@code deleteAfterPush = true}  → eviction: self is no longer a replica,
 * delete the local copy once the push succeeds.</p>
 *
 * <p>{@code deleteAfterPush = false} → seeding: self is still a replica,
 * just copy the key to a new node that joined the replica set.</p>
 */
public record MigrationEntry(String key, CacheItem item, boolean deleteAfterPush) {

    /** Convenience factory for the eviction path (original behaviour). */
    public static MigrationEntry evict(String key, CacheItem item) {
        return new MigrationEntry(key, item, true);
    }

    /** Convenience factory for the seeding path (new node joining). */
    public static MigrationEntry seed(String key, CacheItem item) {
        return new MigrationEntry(key, item, false);
    }
}
