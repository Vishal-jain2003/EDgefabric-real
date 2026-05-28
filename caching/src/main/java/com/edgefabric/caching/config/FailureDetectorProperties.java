package com.edgefabric.caching.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties("failure-detector")
@Getter
@Setter
public class FailureDetectorProperties {

    private long probeIntervalMs = 1000;
    private long pingTimeoutMs = 300;
    private int indirectProbeFanout = 3;
    private long suspectTimeoutMs = 7000;
    private int gossipPort = 7000;

    /**
     * How long (milliseconds) a node must remain in DEAD state before it is
     * permanently evicted from the membership table. This prevents unbounded
     * growth of the member map when nodes leave the cluster permanently.
     * <p>Default: 300 000 ms (5 minutes). Set to 0 or negative to disable eviction.
     */
    private long deadNodeTtlMs = 300_000;

    /**
     * How often (milliseconds) the dead-node reaper runs.
     * <p>Default: 30 000 ms (30 seconds).
     */
    private long deadNodeReapIntervalMs = 30_000;
}

