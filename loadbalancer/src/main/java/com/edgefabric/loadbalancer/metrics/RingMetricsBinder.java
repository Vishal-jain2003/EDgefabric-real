package com.edgefabric.loadbalancer.metrics;

import com.edgefabric.loadbalancer.service.CacheRouter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * Registers Prometheus metrics for the consistent hash ring:
 *
 *   hashing_ring_node_count          — current number of physical nodes in the ring
 *   hashing_ring_size                — current number of virtual slots in the ring
 *   hashing_ring_route_total         — total routing calls (tagged by operation + node_id)
 *   hashing_ring_node_added_total    — total nodes added to the ring
 *   hashing_ring_node_removed_total  — total nodes removed from the ring
 */
@Component
public class RingMetricsBinder implements MeterBinder {

    private final CacheRouter cacheRouter;

    private MeterRegistry registry;
    private Counter nodeAddedCounter;
    private Counter nodeRemovedCounter;

    public RingMetricsBinder(CacheRouter cacheRouter) {
        this.cacheRouter = cacheRouter;
        cacheRouter.setMetricsBinder(this);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;

        Gauge.builder("hashing_ring_node_count", cacheRouter, CacheRouter::nodeCount)
                .description("Number of physical cache nodes in the consistent hash ring")
                .register(registry);

        Gauge.builder("hashing_ring_size", cacheRouter, CacheRouter::ringSize)
                .description("Total number of virtual slots (virtual nodes) in the ring")
                .register(registry);

        nodeAddedCounter = Counter.builder("hashing_ring_node_added_total")
                .description("Total number of nodes added to the ring")
                .register(registry);

        nodeRemovedCounter = Counter.builder("hashing_ring_node_removed_total")
                .description("Total number of nodes removed from the ring")
                .register(registry);
    }

    /**
     * Increments the single-key routing counter for the resolved node.
     * Uses per-node_id tags so Grafana can show per-node key distribution.
     */
    public void incrementRouteSingle(String nodeId) {
        if (registry != null) {
            Counter.builder("hashing_ring_route_total")
                    .description("Total routing calls through the consistent hash ring")
                    .tag("operation", "single")
                    .tag("node_id", nodeId)
                    .register(registry)
                    .increment();
        }
    }

    /**
     * Increments the replica routing counter for each replica node selected.
     */
    public void incrementRouteReplicas(String nodeId) {
        if (registry != null) {
            Counter.builder("hashing_ring_route_total")
                    .description("Total routing calls through the consistent hash ring")
                    .tag("operation", "replicas")
                    .tag("node_id", nodeId)
                    .register(registry)
                    .increment();
        }
    }

    public void incrementNodeAdded() {
        if (nodeAddedCounter != null) nodeAddedCounter.increment();
    }

    public void incrementNodeRemoved() {
        if (nodeRemovedCounter != null) nodeRemovedCounter.increment();
    }
}
