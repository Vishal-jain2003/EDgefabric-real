package com.edgefabric.loadbalancer.exception;

/**
 * Thrown when a cache key is not found on any replica node.
 * This is different from QuorumNotMet which indicates a quorum failure.
 */
public class CacheKeyNotFoundException extends RuntimeException {

    private final String key;

    public CacheKeyNotFoundException(String key) {
        super(String.format("Cache key not found: %s", key));
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
