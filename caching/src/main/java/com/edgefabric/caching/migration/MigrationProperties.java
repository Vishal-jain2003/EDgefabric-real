package com.edgefabric.caching.migration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "migration")
public class MigrationProperties {

    private boolean enabled = true;
    private int rateLimit = 500;
    private long debounceMs = 2000;
    private int maxRetries = 3;
    private long backoffBaseMs = 1000;
    private long backoffMaxMs = 30000;
    /**
     * Must match quorum.replication-factor on the load balancer.
     * Used to determine whether this node is still a replica for a key
     * after a topology change — only keys where self is no longer in
     * the replica set are moved (and deleted) to the new owner.
     */
    private int replicationFactor = 3;
    /**
     * How long (ms) to keep a key locally after successfully pushing it
     * to its new owner.  Gives the LB ring-sync (default 5 s interval)
     * time to catch up before the old replica is gone.
     * Set to 0 to restore the previous immediate-delete behaviour.
     */
    private long deleteDelayMs = 15000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(int rateLimit) {
        if (rateLimit <= 0) {
            throw new IllegalArgumentException("rateLimit must be > 0, got: " + rateLimit);
        }
        this.rateLimit = rateLimit;
    }

    public long getDebounceMs() {
        return debounceMs;
    }

    public void setDebounceMs(long debounceMs) {
        this.debounceMs = debounceMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getBackoffBaseMs() {
        return backoffBaseMs;
    }

    public void setBackoffBaseMs(long backoffBaseMs) {
        this.backoffBaseMs = backoffBaseMs;
    }

    public long getBackoffMaxMs() {
        return backoffMaxMs;
    }

    public void setBackoffMaxMs(long backoffMaxMs) {
        this.backoffMaxMs = backoffMaxMs;
    }

    public int getReplicationFactor() {
        return replicationFactor;
    }

    public void setReplicationFactor(int replicationFactor) {
        if (replicationFactor <= 0) {
            throw new IllegalArgumentException("replicationFactor must be > 0, got: " + replicationFactor);
        }
        this.replicationFactor = replicationFactor;
    }

    public long getDeleteDelayMs() {
        return deleteDelayMs;
    }

    public void setDeleteDelayMs(long deleteDelayMs) {
        if (deleteDelayMs < 0) {
            throw new IllegalArgumentException("deleteDelayMs must be >= 0, got: " + deleteDelayMs);
        }
        this.deleteDelayMs = deleteDelayMs;
    }
}

