package com.edgefabric.loadbalancer.metrics;

import com.edgefabric.loadbalancer.service.CacheRouter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class RingMetricsBinderTest {

    @Mock
    private CacheRouter cacheRouter;

    private RingMetricsBinder binder;
    private MeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        binder = new RingMetricsBinder(cacheRouter);
    }

    @Test
    @DisplayName("incrementRouteSingle increments hashing_ring_route_total[operation=single]")
    void incrementRouteSingle_IncrementsCounter() {
        binder.bindTo(registry);

        binder.incrementRouteSingle("node-1");
        binder.incrementRouteSingle("node-1");

        Counter counter = registry.find("hashing_ring_route_total")
                .tag("operation", "single")
                .tag("node_id", "node-1")
                .counter();
        assertEquals(2.0, counter.count(), 0.001);
    }

    @Test
    @DisplayName("incrementRouteReplicas increments hashing_ring_route_total[operation=replicas]")
    void incrementRouteReplicas_IncrementsCounter() {
        binder.bindTo(registry);

        binder.incrementRouteReplicas("node-2");

        Counter counter = registry.find("hashing_ring_route_total")
                .tag("operation", "replicas")
                .tag("node_id", "node-2")
                .counter();
        assertEquals(1.0, counter.count(), 0.001);
    }

    @Test
    @DisplayName("incrementNodeAdded increments hashing_ring_node_added_total")
    void incrementNodeAdded_IncrementsCounter() {
        binder.bindTo(registry);

        binder.incrementNodeAdded();
        binder.incrementNodeAdded();
        binder.incrementNodeAdded();

        Counter counter = registry.find("hashing_ring_node_added_total").counter();
        assertEquals(3.0, counter.count(), 0.001);
    }

    @Test
    @DisplayName("increment methods are no-ops before bindTo is called (null guard)")
    void incrementMethods_BeforeBindTo_DoNotThrow() {
        binder.incrementRouteSingle("node-1");
        binder.incrementRouteReplicas("node-2");
        binder.incrementNodeAdded();
    }
}
