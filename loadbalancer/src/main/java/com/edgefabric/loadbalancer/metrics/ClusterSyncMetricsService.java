package com.edgefabric.loadbalancer.metrics;

import com.edgefabric.loadbalancer.service.CacheRouter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Metrics service for cluster synchronization operations in the load balancer.
 * Tracks DNS resolutions, node additions/removals, sync errors, and ring size.
 */
@Slf4j
@Service
public class ClusterSyncMetricsService {

    private final MeterRegistry registry;
    private final Counter nodesAddedCounter;
    private final Counter nodesRemovedCounter;
    private final Counter syncErrorsCounter;
    private final Gauge ringSizeGauge;
    private final CacheRouter cacheRouter;

    public ClusterSyncMetricsService(MeterRegistry registry, CacheRouter cacheRouter) {
        this.registry = registry;
        this.cacheRouter = cacheRouter;

        if (registry == null) {
            log.error("MeterRegistry is null - metrics will not be recorded");
            this.nodesAddedCounter = null;
            this.nodesRemovedCounter = null;
            this.syncErrorsCounter = null;
            this.ringSizeGauge = null;
            return;
        }

        this.nodesAddedCounter = Counter.builder("edgefabric.cluster.nodes.added.total")
                .description("Total number of cache nodes added to the ring")
                .register(registry);

        this.nodesRemovedCounter = Counter.builder("edgefabric.cluster.nodes.removed.total")
                .description("Total number of cache nodes removed from the ring")
                .register(registry);

        this.syncErrorsCounter = Counter.builder("edgefabric.cluster.sync.errors.total")
                .description("Total number of cluster synchronization errors")
                .register(registry);

        this.ringSizeGauge = Gauge.builder("edgefabric.cluster.ring.size", this,
                service -> service.cacheRouter != null ? service.cacheRouter.nodeCount() : 0)
                .description("Current number of nodes in the hash ring")
                .register(registry);

        log.info("ClusterSyncMetricsService initialized with 3 counters and 1 gauge");
    }

    /**
     * Records a DNS resolution attempt.
     * @param success true if the resolution succeeded, false otherwise
     */
    public void recordDnsResolution(boolean success) {
        if (registry != null) {
            Counter.builder("edgefabric.cluster.dns.resolutions.total")
                    .description("Total number of DNS resolution attempts")
                    .tag("result", success ? "success" : "failure")
                    .register(registry)
                    .increment();
        }
    }

    /**
     * Records a cache node being added to the hash ring.
     */
    public void recordNodeAdded() {
        if (nodesAddedCounter != null) {
            nodesAddedCounter.increment();
        }
    }

    /**
     * Records a cache node being removed from the hash ring.
     */
    public void recordNodeRemoved() {
        if (nodesRemovedCounter != null) {
            nodesRemovedCounter.increment();
        }
    }

    /**
     * Records a cluster synchronization error.
     */
    public void recordSyncError() {
        if (syncErrorsCounter != null) {
            syncErrorsCounter.increment();
        }
    }
}
