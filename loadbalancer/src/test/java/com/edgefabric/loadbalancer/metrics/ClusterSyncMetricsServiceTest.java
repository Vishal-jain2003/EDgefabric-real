package com.edgefabric.loadbalancer.metrics;

import com.edgefabric.loadbalancer.service.CacheRouter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ClusterSyncMetricsServiceTest {

    private MeterRegistry registry;

    @Mock
    private CacheRouter cacheRouter;

    private ClusterSyncMetricsService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new ClusterSyncMetricsService(registry, cacheRouter);
    }

    @Test
    void recordDnsResolution_success_incrementsCounter() {
        // Act
        service.recordDnsResolution(true);

        // Assert
        double count = registry.counter("edgefabric.cluster.dns.resolutions.total", "result", "success").count();
        assertEquals(1.0, count);
    }

    @Test
    void recordDnsResolution_failure_incrementsCounter() {
        // Act
        service.recordDnsResolution(false);

        // Assert
        double count = registry.counter("edgefabric.cluster.dns.resolutions.total", "result", "failure").count();
        assertEquals(1.0, count);
    }

    @Test
    void recordNodeAdded_incrementsCounter() {
        // Act
        service.recordNodeAdded();

        // Assert
        double count = registry.counter("edgefabric.cluster.nodes.added.total").count();
        assertEquals(1.0, count);
    }

    @Test
    void recordNodeRemoved_incrementsCounter() {
        // Act
        service.recordNodeRemoved();

        // Assert
        double count = registry.counter("edgefabric.cluster.nodes.removed.total").count();
        assertEquals(1.0, count);
    }

    @Test
    void recordSyncError_incrementsCounter() {
        // Act
        service.recordSyncError();

        // Assert
        double count = registry.counter("edgefabric.cluster.sync.errors.total").count();
        assertEquals(1.0, count);
    }

    @Test
    void constructor_registersAllMetrics() {
        // Assert - verify all metrics are registered
        assertNotNull(registry.find("edgefabric.cluster.nodes.added.total").counter());
        assertNotNull(registry.find("edgefabric.cluster.nodes.removed.total").counter());
        assertNotNull(registry.find("edgefabric.cluster.sync.errors.total").counter());
        assertNotNull(registry.find("edgefabric.cluster.ring.size").gauge());
    }
}
