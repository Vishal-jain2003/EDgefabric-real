package com.edgefabric.caching.service;

import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.model.NodeInfo;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registers and manages all Prometheus metrics for the EdgeFabric cache layer.
 *
 * <p>Exposes 6 metrics on {@code /actuator/prometheus}, each tagged with
 * {@code node_id} and {@code node_host} so scrapes from multiple nodes can be
 * distinguished in Grafana / Prometheus.</p>
 *
 * <ul>
 *   <li>{@code edgefabric.cache.hits}        — counter, incremented on every cache hit</li>
 *   <li>{@code edgefabric.cache.misses}       — counter, incremented on every miss or TTL expiry</li>
 *   <li>{@code edgefabric.cache.puts}         — counter, incremented on every accepted PUT</li>
 *   <li>{@code edgefabric.cache.evictions}    — counter, incremented on every LRU / TTL eviction</li>
 *   <li>{@code edgefabric.cache.size}         — gauge,   live entry count from the store map</li>
 *   <li>{@code edgefabric.cache.memory.used}  — gauge,   live bytes from the memory counter</li>
 * </ul>
 */
@Service
public class CacheMetricsService {

    private final Counter hits;
    private final Counter misses;
    private final Counter puts;
    private final Counter evictions;

    public CacheMetricsService(MeterRegistry registry,
                                Map<String, CacheItem> store,
                                AtomicLong currentMemoryUsage,
                                NodeInfo selfNodeInfo) {

        Tags tags = Tags.of(
                "node_id",   selfNodeInfo.getCacheNodeId(),
                "node_host", selfNodeInfo.getHost()
        );

        this.hits = Counter.builder("edgefabric.cache.hits")
                .description("Total number of cache hits")
                .tags(tags)
                .register(registry);

        this.misses = Counter.builder("edgefabric.cache.misses")
                .description("Total number of cache misses (including TTL expiries)")
                .tags(tags)
                .register(registry);

        this.puts = Counter.builder("edgefabric.cache.puts")
                .description("Total number of accepted cache puts")
                .tags(tags)
                .register(registry);

        this.evictions = Counter.builder("edgefabric.cache.evictions")
                .description("Total number of cache evictions (LRU + TTL)")
                .tags(tags)
                .register(registry);

        Gauge.builder("edgefabric.cache.size", store, Map::size)
                .description("Current number of entries in the cache store")
                .tags(tags)
                .register(registry);

        Gauge.builder("edgefabric.cache.memory.used", currentMemoryUsage, AtomicLong::get)
                .description("Current memory used by all cache entries in bytes")
                .tags(tags)
                .register(registry);
    }

    public void recordHit()      { hits.increment(); }
    public void recordMiss()     { misses.increment(); }
    public void recordPut()      { puts.increment(); }
    public void recordEviction() { evictions.increment(); }
}
