package com.edgefabric.caching.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FailureDetectorMetricsServiceTest {

    private MeterRegistry registry;
    private FailureDetectorMetricsService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new FailureDetectorMetricsService(registry);
    }

    @Test
    void recordPing_ack_incrementsCounter() {
        // Act
        service.recordPing("ack");

        // Assert
        double count = registry.counter("edgefabric.failure_detector.pings.total", "result", "ack").count();
        assertEquals(1.0, count);
    }

    @Test
    void recordPing_timeout_incrementsCounter() {
        // Act
        service.recordPing("timeout");

        // Assert
        double count = registry.counter("edgefabric.failure_detector.pings.total", "result", "timeout").count();
        assertEquals(1.0, count);
    }

    @Test
    void recordSuspect_incrementsCounter() {
        // Act
        service.recordSuspect();

        // Assert
        double count = registry.counter("edgefabric.failure_detector.suspects.total").count();
        assertEquals(1.0, count);
    }

    @Test
    void recordAliveTransition_incrementsCounter() {
        // Act
        service.recordAliveTransition();

        // Assert
        double count = registry.counter("edgefabric.failure_detector.alive_transitions.total").count();
        assertEquals(1.0, count);
    }

    @Test
    void recordFalsePositive_incrementsCounter() {
        // Act
        service.recordFalsePositive();

        // Assert
        double count = registry.counter("edgefabric.failure_detector.false_positives.total").count();
        assertEquals(1.0, count);
    }

    @Test
    void constructor_registersAllCounters() {
        // Assert - verify counters are registered
        assertNotNull(registry.find("edgefabric.failure_detector.suspects.total").counter());
        assertNotNull(registry.find("edgefabric.failure_detector.alive_transitions.total").counter());
        assertNotNull(registry.find("edgefabric.failure_detector.false_positives.total").counter());
    }
}
