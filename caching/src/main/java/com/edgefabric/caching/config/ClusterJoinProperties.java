package com.edgefabric.caching.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for cluster-join behaviour.
 *
 * <pre>
 * cluster.join.jitter-max-ms          = 3000   # max random sleep before first DNS call
 * cluster.join.dns-reconcile-interval-ms = 15000  # how often the reconciliation thread re-checks
 * cluster.join.dns-reconcile-timeout-ms  = 300000 # how long to keep reconciling (5 min); 0 = disabled
 * </pre>
 */
@Configuration
@ConfigurationProperties("cluster.join")
@Getter
@Setter
public class ClusterJoinProperties {

    /**
     * Maximum jitter (milliseconds) applied before the initial DNS lookup.
     * Each node picks a random value in [0, jitterMaxMs) so that all nodes
     * starting at the same time (e.g. an Auto Scaling Group launch) do not
     * hammer each other with join requests simultaneously.
     */
    private long jitterMaxMs = 3000;

    /**
     * How often (milliseconds) the DNS reconciliation background thread
     * re-resolves DNS and tries to join nodes that are in DNS but not yet
     * in the local membership list.
     */
    private long dnsReconcileIntervalMs = 15000;

    /**
     * How long (milliseconds) the reconciliation thread keeps running before
     * giving up. Set to 0 or negative to disable the thread entirely
     * (useful in unit tests).
     */
    private long dnsReconcileTimeoutMs = 300_000; // 5 minutes
}

