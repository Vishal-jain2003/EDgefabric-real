package com.edgefabric.loadbalancer.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for cluster node discovery.
 *
 * <p>The LB discovers cache nodes via Cloud Map DNS
 * ({@code cache-nodes.cache-cluster.internal}) and syncs membership
 * via the gossip endpoint exposed by each cache node.</p>
 */
@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "cluster")
public class ClusterSyncProperties {

    /**
     * Cloud Map DNS hostname that resolves to all healthy cache-node IPs.
     * e.g. cache-nodes.cache-cluster.internal
     */
    private String dnsName = "cache-nodes.cache-cluster.internal";

    /** Port on which every cache node listens (Spring Boot HTTP port). */
    private int nodePort = 8082;

    /** Gossip membership endpoint path exposed by each cache node. */
    private String membershipPath;

    /** Periodic sync interval in milliseconds. */
    private long syncIntervalMs = 5000;

    /** Max retries during bootstrap before giving up. */
    private int bootstrapMaxRetries = 5;

    /** Delay between bootstrap retries in milliseconds. */
    private long bootstrapRetryDelayMs = 2000;

    /** Timeout for each peer membership HTTP call in milliseconds. */
    private long syncTimeoutMs = 3000;

}

