package com.edgefabric.caching.dto;

import com.edgefabric.caching.model.HealthStatus;

/**
 * Snapshot of per-node cache statistics returned by GET /api/v1/cache/stats.
 *
 * @param hitRate      cache hit percentage in the range 0.0–100.0
 * @param totalHits    cumulative cache hit count since node start
 * @param totalMisses  cumulative cache miss count since node start
 * @param cacheSize    current number of entries in the cache store
 * @param memoryUsed   current heap memory used by all cache entries, in bytes
 * @param healthStatus derived node health — UP, DEGRADED, or DOWN
 */
public record CacheStatsDTO(
        double hitRate,
        long totalHits,
        long totalMisses,
        long cacheSize,
        long memoryUsed,
        HealthStatus healthStatus
) {}
