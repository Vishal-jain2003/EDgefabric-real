package com.edgefabric.caching.service;

import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.model.NodeInfo;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CacheMetricsServiceTest {

    private MeterRegistry registry;
    private Map<String, CacheItem> store;
    private AtomicLong currentMemoryUsage;
    private CacheMetricsService metricsService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        store = new ConcurrentHashMap<>();
        currentMemoryUsage = new AtomicLong(0);
        NodeInfo nodeInfo = new NodeInfo("cache-node-test-8082", "127.0.0.1", 8082, 7946);
        metricsService = new CacheMetricsService(registry, store, currentMemoryUsage, nodeInfo);
    }

    // ── Counter registration ─────────────────────────────────────────────────

    @Test
    void allSixMetricsShouldBeRegistered() {
        assertNotNull(registry.find("edgefabric.cache.hits").counter());
        assertNotNull(registry.find("edgefabric.cache.misses").counter());
        assertNotNull(registry.find("edgefabric.cache.puts").counter());
        assertNotNull(registry.find("edgefabric.cache.evictions").counter());
        assertNotNull(registry.find("edgefabric.cache.size").gauge());
        assertNotNull(registry.find("edgefabric.cache.memory.used").gauge());
    }

    @Test
    void metricsShouldBeTaggedWithNodeIdAndNodeHost() {
        Counter hits = registry.find("edgefabric.cache.hits")
                .tag("node_id", "cache-node-test-8082")
                .tag("node_host", "127.0.0.1")
                .counter();
        assertNotNull(hits, "hits counter must carry node_id and node_host tags");
    }

    // ── Counter increments ───────────────────────────────────────────────────

    @Test
    void recordHit_ShouldIncrementHitsCounter() {
        metricsService.recordHit();
        metricsService.recordHit();

        Counter hits = registry.find("edgefabric.cache.hits").counter();
        assertNotNull(hits);
        assertEquals(2.0, hits.count(), 0.001);
    }

    @Test
    void recordMiss_ShouldIncrementMissesCounter() {
        metricsService.recordMiss();

        Counter misses = registry.find("edgefabric.cache.misses").counter();
        assertNotNull(misses);
        assertEquals(1.0, misses.count(), 0.001);
    }

    @Test
    void recordPut_ShouldIncrementPutsCounter() {
        metricsService.recordPut();
        metricsService.recordPut();
        metricsService.recordPut();

        Counter puts = registry.find("edgefabric.cache.puts").counter();
        assertNotNull(puts);
        assertEquals(3.0, puts.count(), 0.001);
    }

    @Test
    void recordEviction_ShouldIncrementEvictionsCounter() {
        metricsService.recordEviction();

        Counter evictions = registry.find("edgefabric.cache.evictions").counter();
        assertNotNull(evictions);
        assertEquals(1.0, evictions.count(), 0.001);
    }

    // ── Gauge live values ────────────────────────────────────────────────────

    @Test
    void sizeGauge_ShouldReflectLiveStoreSize() {
        Gauge sizeGauge = registry.find("edgefabric.cache.size").gauge();
        assertNotNull(sizeGauge);
        assertEquals(0.0, sizeGauge.value(), 0.001);

        store.put("key1", createCacheItem(100));
        store.put("key2", createCacheItem(100));

        assertEquals(2.0, sizeGauge.value(), 0.001);

        store.remove("key1");
        assertEquals(1.0, sizeGauge.value(), 0.001);
    }

    @Test
    void memoryUsedGauge_ShouldReflectLiveMemoryCounter() {
        Gauge memGauge = registry.find("edgefabric.cache.memory.used").gauge();
        assertNotNull(memGauge);
        assertEquals(0.0, memGauge.value(), 0.001);

        currentMemoryUsage.set(512_000);
        assertEquals(512_000.0, memGauge.value(), 0.001);

        currentMemoryUsage.set(0);
        assertEquals(0.0, memGauge.value(), 0.001);
    }

    @Test
    void countersStartAtZero() {
        assertEquals(0.0, registry.find("edgefabric.cache.hits").counter().count(), 0.001);
        assertEquals(0.0, registry.find("edgefabric.cache.misses").counter().count(), 0.001);
        assertEquals(0.0, registry.find("edgefabric.cache.puts").counter().count(), 0.001);
        assertEquals(0.0, registry.find("edgefabric.cache.evictions").counter().count(), 0.001);
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private CacheItem createCacheItem(int size) {
        return new CacheItem(new byte[size], System.currentTimeMillis() + 60_000,
                "application/octet-stream", 1L, true);
    }
}
