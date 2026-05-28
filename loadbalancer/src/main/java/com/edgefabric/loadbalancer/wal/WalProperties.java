package com.edgefabric.loadbalancer.wal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wal")
public class WalProperties {

    private boolean enabled = false;
    /** "s3" (default) or "local" — use "local" for dev without AWS credentials. */
    private String storage = "s3";
    private String s3Bucket = "ef-hermes-wal";
    private String region = "ap-south-1";
    private String lbId = "lb1";
    private int segmentSizeBytes = 4 * 1024 * 1024;   // 4 MB
    /**
     * Maximum entries drained from the queue in a single flush cycle.
     * Keeping this bounded prevents one slow S3 write from holding a gigantic batch.
     */
    private int flushBatchSize = 100;
    /**
     * Extra queue slots beyond {@code flushBatchSize}.
     * While a flush is in progress the queue still has {@code bufferHeadroom} free
     * slots so concurrent {@code append()} calls never block.
     */
    private int bufferHeadroom = 50;
    private int maxFlushRetries = 3;
    private long retryBackoffMs = 500;
    private long flushIntervalMs = 500;
    /** Root directory used when {@code storage=local}. */
    private String localDir = "wal-local";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getStorage() { return storage; }
    public void setStorage(String storage) { this.storage = storage; }

    public String getS3Bucket() { return s3Bucket; }
    public void setS3Bucket(String s3Bucket) { this.s3Bucket = s3Bucket; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getLbId() { return lbId; }
    public void setLbId(String lbId) { this.lbId = lbId; }

    public int getSegmentSizeBytes() { return segmentSizeBytes; }
    public void setSegmentSizeBytes(int segmentSizeBytes) { this.segmentSizeBytes = segmentSizeBytes; }

    public int getFlushBatchSize() { return flushBatchSize; }
    public void setFlushBatchSize(int flushBatchSize) { this.flushBatchSize = flushBatchSize; }

    public int getBufferHeadroom() { return bufferHeadroom; }
    public void setBufferHeadroom(int bufferHeadroom) { this.bufferHeadroom = bufferHeadroom; }

    public int getMaxFlushRetries() { return maxFlushRetries; }
    public void setMaxFlushRetries(int maxFlushRetries) { this.maxFlushRetries = maxFlushRetries; }

    public long getRetryBackoffMs() { return retryBackoffMs; }
    public void setRetryBackoffMs(long retryBackoffMs) { this.retryBackoffMs = retryBackoffMs; }

    public long getFlushIntervalMs() { return flushIntervalMs; }
    public void setFlushIntervalMs(long flushIntervalMs) { this.flushIntervalMs = flushIntervalMs; }

    public String getLocalDir() { return localDir; }
    public void setLocalDir(String localDir) { this.localDir = localDir; }
}
