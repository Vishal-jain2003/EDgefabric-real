package com.edgefabric.caching.service;

import com.edgefabric.caching.dto.CacheStatsDTO;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.HealthStatus;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads per-node cache statistics from the shared {@link MeterRegistry}
 * (populated by {@link CacheMetricsService}) and the node's gossip state.
 *
 * <p>No new counter state is introduced — Strategy 2: reuse existing Micrometer counters.</p>
 */
@Service
public class CacheStatsService {

    private static final double DEGRADED_MEMORY_THRESHOLD = 0.80;

    private final MeterRegistry registry;
    private final AtomicLong currentMemoryUsage;
    private final MembershipList membershipList;
    private final long maxMemoryBytes;

    public CacheStatsService(MeterRegistry registry,
                             AtomicLong currentMemoryUsage,
                             MembershipList membershipList,
                             @Value("${edgefabric.cache.max-memory-bytes}") long maxMemoryBytes) {
        this.registry = registry;
        this.currentMemoryUsage = currentMemoryUsage;
        this.membershipList = membershipList;
        this.maxMemoryBytes = maxMemoryBytes;
    }

    /**
     * Returns a point-in-time snapshot of this node's cache statistics.
     *
     * @return {@link CacheStatsDTO} containing hit rate, hit/miss counts,
     *         cache size, memory used, and derived health status
     */
    public CacheStatsDTO getStats() {
        long totalHits   = readCounter("edgefabric.cache.hits");
        long totalMisses = readCounter("edgefabric.cache.misses");
        long total       = totalHits + totalMisses;
        double hitRate   = total == 0 ? 0.0 : (totalHits * 100.0) / total;

        long cacheSize  = readGauge("edgefabric.cache.size");
        long memoryUsed = currentMemoryUsage.get();

        HealthStatus healthStatus = deriveHealthStatus(memoryUsed);

        return new CacheStatsDTO(hitRate, totalHits, totalMisses, cacheSize, memoryUsed, healthStatus);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private long readCounter(String name) {
        var counter = registry.find(name).counter();
        return counter == null ? 0L : (long) counter.count();
    }

    private long readGauge(String name) {
        var gauge = registry.find(name).gauge();
        return gauge == null ? 0L : (long) gauge.value();
    }

    private HealthStatus deriveHealthStatus(long memoryUsed) {
        NodeInfo self   = membershipList.getSelf();
        Status   status = self.getStatus();

        if (status == Status.SUSPECT || status == Status.DEAD) {
            return HealthStatus.DOWN;
        }

        // ALIVE or DRAINING
        double memoryRatio = (double) memoryUsed / maxMemoryBytes;
        return memoryRatio >= DEGRADED_MEMORY_THRESHOLD ? HealthStatus.DEGRADED : HealthStatus.UP;
    }
}
