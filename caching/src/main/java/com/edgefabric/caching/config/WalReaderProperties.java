package com.edgefabric.caching.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "cache.wal")
@Validated
public class WalReaderProperties {
    private boolean enabled = false;

    @NotBlank(message = "cache.wal.s3-bucket must not be blank")
    private String s3Bucket = "ef-hermes-wal";

    @NotBlank(message = "cache.wal.lb-id must not be blank")
    private String lbId = "lb1";

    @NotBlank(message = "cache.wal.region must not be blank")
    private String region = "ap-south-1";

    @Positive(message = "cache.wal.interval-ms must be positive")
    private long intervalMs = 60000;

    @Min(value = 1, message = "cache.wal.max-entries-per-cycle must be at least 1")
    private int maxEntriesPerCycle = 500;

    @Positive(message = "cache.wal.peer-timeout-ms must be positive")
    private int peerTimeoutMs = 5000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getS3Bucket() {
        return s3Bucket;
    }

    public void setS3Bucket(String s3Bucket) {
        this.s3Bucket = s3Bucket;
    }

    public String getLbId() {
        return lbId;
    }

    public void setLbId(String lbId) {
        this.lbId = lbId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public int getMaxEntriesPerCycle() {
        return maxEntriesPerCycle;
    }

    public void setMaxEntriesPerCycle(int maxEntriesPerCycle) {
        this.maxEntriesPerCycle = maxEntriesPerCycle;
    }

    public int getPeerTimeoutMs() {
        return peerTimeoutMs;
    }

    public void setPeerTimeoutMs(int peerTimeoutMs) {
        this.peerTimeoutMs = peerTimeoutMs;
    }
}
