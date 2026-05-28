package com.edgefabric.loadbalancer.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Metrics service for quorum-based operations in the load balancer.
 * Tracks success, failure, and timeout rates for quorum reads/writes,
 * as well as repair operations and version conflicts.
 */
@Slf4j
@Service
public class QuorumMetricsService {

    private final MeterRegistry registry;
    private final Counter quorumRepairsCounter;
    private final Counter versionConflictsCounter;

    public QuorumMetricsService(MeterRegistry registry) {
        this.registry = registry;

        if (registry == null) {
            log.error("MeterRegistry is null - metrics will not be recorded");
            this.quorumRepairsCounter = null;
            this.versionConflictsCounter = null;
            return;
        }

        this.quorumRepairsCounter = Counter.builder("edgefabric.quorum.repairs.triggered.total")
                .description("Total number of read repair operations triggered")
                .register(registry);

        this.versionConflictsCounter = Counter.builder("edgefabric.quorum.version.conflicts.total")
                .description("Total number of version conflicts detected")
                .register(registry);

        log.info("QuorumMetricsService initialized");
    }

    /**
     * Records a quorum write operation.
     * @param result The result of the write: success, quorum_not_met, or timeout
     */
    public void recordWrite(String result) {
        if (registry != null) {
            Counter.builder("edgefabric.quorum.writes.total")
                    .description("Total number of quorum write operations")
                    .tag("result", result)
                    .register(registry)
                    .increment();
        }
    }

    /**
     * Records a quorum read operation.
     * @param result The result of the read: success, quorum_not_met, or timeout
     */
    public void recordRead(String result) {
        if (registry != null) {
            Counter.builder("edgefabric.quorum.reads.total")
                    .description("Total number of quorum read operations")
                    .tag("result", result)
                    .register(registry)
                    .increment();
        }
    }

    /**
     * Records a read repair operation being triggered.
     */
    public void recordRepair() {
        if (quorumRepairsCounter != null) {
            quorumRepairsCounter.increment();
        }
    }

    /**
     * Records a version conflict detected during quorum operations.
     */
    public void recordVersionConflict() {
        if (versionConflictsCounter != null) {
            versionConflictsCounter.increment();
        }
    }
}
