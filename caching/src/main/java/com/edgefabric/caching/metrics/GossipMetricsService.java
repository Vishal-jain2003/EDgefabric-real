package com.edgefabric.caching.metrics;

import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.Status;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Metrics service for gossip protocol operations in cache nodes.
 * Tracks reachable nodes count for partition detection alerting
 * and per-status node counts for cluster health recording rules.
 */
@Slf4j
@Service
public class GossipMetricsService {

    private final MeterRegistry registry;
    private final MembershipList membershipList;

    public GossipMetricsService(MeterRegistry registry, MembershipList membershipList) {
        this.registry = registry;
        this.membershipList = membershipList;

        if (registry == null) {
            log.error("MeterRegistry is null - gossip metrics will not be recorded");
            return;
        }

        if (membershipList == null) {
            log.error("MembershipList is null - gossip metrics will not be recorded");
            return;
        }

        // Register gauge for reachable nodes count
        Gauge.builder("edgefabric.gossip.reachable_nodes", membershipList, this::getReachableNodesCount)
                .description("Number of reachable nodes in the gossip cluster (excludes self)")
                .register(registry);

        // Register per-status gauges for cluster health recording rule
        for (Status status : Status.values()) {
            Gauge.builder("edgefabric.gossip.node_status", membershipList,
                            ml -> countByStatus(ml, status))
                    .tag("status", status.name())
                    .description("Number of nodes in " + status.name() + " state")
                    .register(registry);
        }

        log.info("GossipMetricsService initialized - tracking reachable nodes and per-status counts");
    }

    /**
     * Gets the count of reachable nodes (ALIVE status) excluding self.
     * This is used by the GossipPartitionDetected alert.
     *
     * @param membershipList the membership list
     * @return count of alive nodes excluding self
     */
    private double getReachableNodesCount(MembershipList membershipList) {
        try {
            // Get alive nodes count (excluding self)
            int aliveCount = membershipList.getAliveNodes().size();

            // Subtract 1 to exclude self from the count
            // (getAliveNodes includes the current node)
            int reachableCount = Math.max(0, aliveCount - 1);

            log.trace("Gossip reachable nodes: {} (total alive: {})", reachableCount, aliveCount);
            return reachableCount;
        } catch (Exception e) {
            log.error("Error calculating reachable nodes count", e);
            return 0;
        }
    }

    private double countByStatus(MembershipList ml, Status status) {
        try {
            return ml.getDigest().stream()
                    .filter(node -> node.getStatus() == status)
                    .count();
        } catch (Exception e) {
            log.error("Error counting nodes with status {}", status, e);
            return 0;
        }
    }
}
