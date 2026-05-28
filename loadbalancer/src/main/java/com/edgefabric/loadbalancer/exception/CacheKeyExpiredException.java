package com.edgefabric.loadbalancer.exception;

/**
 * Thrown when a cache key has expired on the replicas.
 * This is different from QuorumNotMet which indicates a quorum failure.
 */
public class CacheKeyExpiredException extends RuntimeException {

    private final String key;
    private final long expiresAt;

    public CacheKeyExpiredException(String key, long expiresAt) {
        super(String.format("Cache key expired: %s (expiresAt=%d)", key, expiresAt));
        this.key = key;
        this.expiresAt = expiresAt;
    }

    public String getKey() {
        return key;
    }

    public long getExpiresAt() {
        return expiresAt;
    }
}
