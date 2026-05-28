package com.edgefabric.caching.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Metrics service for failure detection operations in cache nodes.
 * Tracks ping success/failure rates, suspect/alive transitions, and false positives.
 */
@Slf4j
@Service
public class FailureDetectorMetricsService {

    private final MeterRegistry registry;
    private final Counter suspectsCounter;
    private final Counter aliveTransitionsCounter;
    private final Counter falsePositivesCounter;

    public FailureDetectorMetricsService(MeterRegistry registry) {
        this.registry = registry;

        if (registry == null) {
            log.error("MeterRegistry is null - metrics will not be recorded");
            this.suspectsCounter = null;
            this.aliveTransitionsCounter = null;
            this.falsePositivesCounter = null;
            return;
        }

        this.suspectsCounter = Counter.builder("edgefabric.failure_detector.suspects.total")
                .description("Total number of nodes marked as suspect")
                .register(registry);

        this.aliveTransitionsCounter = Counter.builder("edgefabric.failure_detector.alive_transitions.total")
                .description("Total number of suspect-to-alive transitions")
                .register(registry);

        this.falsePositivesCounter = Counter.builder("edgefabric.failure_detector.false_positives.total")
                .description("Total number of false positive failure detections")
                .register(registry);

        log.info("FailureDetectorMetricsService initialized");
    }

    /**
     * Records a ping operation result.
     * @param result The result of the ping: ack or timeout
     */
    public void recordPing(String result) {
        if (registry != null) {
            Counter.builder("edgefabric.failure_detector.pings.total")
                    .description("Total number of failure detector ping operations")
                    .tag("result", result)
                    .register(registry)
                    .increment();
        }
    }

    /**
     * Records a node being marked as suspect.
     */
    public void recordSuspect() {
        if (suspectsCounter != null) {
            suspectsCounter.increment();
        }
    }

    /**
     * Records a suspect node transitioning back to alive.
     */
    public void recordAliveTransition() {
        if (aliveTransitionsCounter != null) {
            aliveTransitionsCounter.increment();
        }
    }

    /**
     * Records a false positive failure detection (suspect node comes back alive quickly).
     */
    public void recordFalsePositive() {
        if (falsePositivesCounter != null) {
            falsePositivesCounter.increment();
        }
    }
}
