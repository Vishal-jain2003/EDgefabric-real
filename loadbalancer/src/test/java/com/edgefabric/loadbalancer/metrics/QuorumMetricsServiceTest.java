package com.edgefabric.loadbalancer.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuorumMetricsServiceTest {

    private MeterRegistry registry;
    private QuorumMetricsService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new QuorumMetricsService(registry);
    }

    @Test
    void recordWrite_success_incrementsCounter() {
        // Act
        service.recordWrite("success");

        // Assert
        double count = registry.counter("edgefabric.quorum.writes.total", "result", "success").count();
        assertEquals(1.0, count);
    }

    @Test
    void recordWrite_quorumNotMet_incrementsCounter() {
        // Act
        service.recordWrite("quorum_not_met");

        // Assert
        double count = registry.counter("edgefabric.quorum.writes.total", "result", "quorum_not_met").count();
        assertEquals(1.0, count);
    }

    @Test
    void recordWrite_timeout_incrementsCounter() {
        // Act
        service.recordWrite("timeout");

        // Assert
        double count = registry.counter("edgefabric.quorum.writes.total", "result", "timeout").count();
        assertEquals(1.0, count);
    }

    @Test
    void recordRead_success_incrementsCounter() {
        // Act
        service.recordRead("success");

        // Assert
        double count = registry.counter("edgefabric.quorum.reads.total", "result", "success").count();
        assertEquals(1.0, count);
    }

    @Test
    void recordRead_quorumNotMet_incrementsCounter() {
        // Act
        service.recordRead("quorum_not_met");

        // Assert
        double count = registry.counter("edgefabric.quorum.reads.total", "result", "quorum_not_met").count();
        assertEquals(1.0, count);
    }

    @Test
    void recordRead_timeout_incrementsCounter() {
        // Act
        service.recordRead("timeout");

        // Assert
        double count = registry.counter("edgefabric.quorum.reads.total", "result", "timeout").count();
        assertEquals(1.0, count);
    }

    @Test
    void recordRepair_incrementsCounter() {
        // Act
        service.recordRepair();

        // Assert
        double count = registry.counter("edgefabric.quorum.repairs.triggered.total").count();
        assertEquals(1.0, count);
    }

    @Test
    void recordVersionConflict_incrementsCounter() {
        // Act
        service.recordVersionConflict();

        // Assert
        double count = registry.counter("edgefabric.quorum.version.conflicts.total").count();
        assertEquals(1.0, count);
    }

    @Test
    void constructor_registersAllCounters() {
        // Assert - verify counters are registered
        assertNotNull(registry.find("edgefabric.quorum.repairs.triggered.total").counter());
        assertNotNull(registry.find("edgefabric.quorum.version.conflicts.total").counter());
    }
}
