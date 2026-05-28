package com.edgefabric.caching.service;

import com.edgefabric.caching.dto.CacheStatsDTO;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.HealthStatus;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheStatsServiceTest {

    private static final long MAX_MEMORY_BYTES = 100_000_000L; // 100 MB for test

    private MeterRegistry registry;
    private AtomicLong currentMemoryUsage;

    @Mock
    private MembershipList membershipList;

    private NodeInfo selfNode;
    private CacheStatsService cacheStatsService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        currentMemoryUsage = new AtomicLong(0);
        selfNode = new NodeInfo("cache-node-test", "127.0.0.1", 8082, 7946);

        // Pre-register counters and gauge (as CacheMetricsService would do)
        Counter.builder("edgefabric.cache.hits").register(registry);
        Counter.builder("edgefabric.cache.misses").register(registry);
        Gauge.builder("edgefabric.cache.size", () -> 512.0).register(registry);

        when(membershipList.getSelf()).thenReturn(selfNode);

        cacheStatsService = new CacheStatsService(
                registry,
                currentMemoryUsage,
                membershipList,
                MAX_MEMORY_BYTES
        );
    }

    // ── Hit-rate computation ─────────────────────────────────────────────────

    @Test
    void getStats_WhenNoRequests_ReturnsZeroHitRate() {
        CacheStatsDTO dto = cacheStatsService.getStats();

        assertThat(dto.hitRate()).isZero();
        assertThat(dto.totalHits()).isZero();
        assertThat(dto.totalMisses()).isZero();
    }

    @Test
    void getStats_WithHitsAndMisses_ComputesHitRateCorrectly() {
        // 6 hits, 4 misses → hitRate = 60.0
        registry.find("edgefabric.cache.hits").counter().increment(6);
        registry.find("edgefabric.cache.misses").counter().increment(4);

        CacheStatsDTO dto = cacheStatsService.getStats();

        assertThat(dto.hitRate()).isCloseTo(60.0, within(0.001));
        assertThat(dto.totalHits()).isEqualTo(6L);
        assertThat(dto.totalMisses()).isEqualTo(4L);
    }

    // ── healthStatus mapping ─────────────────────────────────────────────────

    @Test
    void getStats_WhenAliveAndMemoryLow_ReturnsUp() {
        selfNode.setStatus(Status.ALIVE);
        currentMemoryUsage.set(MAX_MEMORY_BYTES / 2); // 50% — below threshold

        CacheStatsDTO dto = cacheStatsService.getStats();

        assertThat(dto.healthStatus()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void getStats_WhenAliveAndMemoryHigh_ReturnsDegraded() {
        selfNode.setStatus(Status.ALIVE);
        currentMemoryUsage.set((long) (MAX_MEMORY_BYTES * 0.85)); // 85% — above 80%

        CacheStatsDTO dto = cacheStatsService.getStats();

        assertThat(dto.healthStatus()).isEqualTo(HealthStatus.DEGRADED);
    }

    @Test
    void getStats_WhenNodeSuspect_ReturnDown() {
        selfNode.setStatus(Status.SUSPECT);
        currentMemoryUsage.set(0L);

        CacheStatsDTO dto = cacheStatsService.getStats();

        assertThat(dto.healthStatus()).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void getStats_WhenNodeDead_ReturnsDown() {
        selfNode.transitionToDead();

        CacheStatsDTO dto = cacheStatsService.getStats();

        assertThat(dto.healthStatus()).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void getStats_WhenDraining_MemoryLow_ReturnsUp() {
        selfNode.transitionToDraining();
        currentMemoryUsage.set(MAX_MEMORY_BYTES / 4); // 25% — below threshold

        CacheStatsDTO dto = cacheStatsService.getStats();

        assertThat(dto.healthStatus()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void getStats_WhenDraining_MemoryHigh_ReturnsDegraded() {
        selfNode.transitionToDraining();
        currentMemoryUsage.set((long) (MAX_MEMORY_BYTES * 0.90)); // 90% — above 80%

        CacheStatsDTO dto = cacheStatsService.getStats();

        assertThat(dto.healthStatus()).isEqualTo(HealthStatus.DEGRADED);
    }

    // ── cacheSize + memoryUsed ────────────────────────────────────────────────

    @Test
    void getStats_ReturnsCorrectCacheSizeAndMemoryUsed() {
        currentMemoryUsage.set(10_485_760L); // 10 MB

        CacheStatsDTO dto = cacheStatsService.getStats();

        assertThat(dto.cacheSize()).isEqualTo(512L);        // from gauge pre-registered in setUp
        assertThat(dto.memoryUsed()).isEqualTo(10_485_760L);
    }
}
