package com.edgefabric.loadbalancer.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;


@Configuration
@ConfigurationProperties(prefix = "quorum")
@Validated
@Getter
@Setter
public class QuorumProperties {

    private static final Logger log = LoggerFactory.getLogger(QuorumProperties.class);

    /** Total number of replicas (N). */
    private int replicationFactor = 3;

    /** Minimum successful writes required (W). */
    private int write = 2;

    /** Minimum successful reads required (R). */
    private int read = 2;

    /** Timeout in milliseconds for each replica call. */
    private long timeoutMs = 2000;

    /**
     * Minimum expected cluster node count. When set to a positive value, startup
     * fails if {@code replicationFactor} exceeds the declared cluster size — catching
     * a misconfiguration that would otherwise only surface as a confusing
     * {@code QuorumNotMetException} at runtime. Set to 0 (default) to disable.
     */
    @Min(0)
    private int minClusterSize = 0;

    @PostConstruct
    public void validate() {
        if (replicationFactor <= 0) {
            throw new IllegalArgumentException(
                    "quorum.replication-factor must be > 0, got: " + replicationFactor);
        }
        if (write <= 0 || write > replicationFactor) {
            throw new IllegalArgumentException(
                    "quorum.write must be in [1, " + replicationFactor + "], got: " + write);
        }
        if (read <= 0 || read > replicationFactor) {
            throw new IllegalArgumentException(
                    "quorum.read must be in [1, " + replicationFactor + "], got: " + read);
        }
        if (read + write <= replicationFactor) {
            throw new IllegalArgumentException(String.format(
                    "Quorum invariant violated: R(%d) + W(%d) must be > N(%d) for strong consistency",
                    read, write, replicationFactor));
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException(
                    "quorum.timeout-ms must be > 0, got: " + timeoutMs);
        }
        if (minClusterSize > 0 && replicationFactor > minClusterSize) {
            throw new IllegalArgumentException(String.format(
                    "quorum.replication-factor (%d) exceeds quorum.min-cluster-size (%d): " +
                    "the cluster does not have enough nodes to satisfy replication. " +
                    "Either reduce replication-factor or increase min-cluster-size.",
                    replicationFactor, minClusterSize));
        }

        log.info("Quorum config validated — N={}, W={}, R={}, timeout={}ms, minClusterSize={}",
                replicationFactor, write, read, timeoutMs, minClusterSize);
    }
}

