package com.edgefabric.caching.model;

import lombok.Getter;

/**
 * Represents a single cached entry with its payload, metadata, and memory footprint.
 * <p>
 * The {@code memorySize} is computed once at construction time and remains immutable,
 * since the underlying {@code byte[]} data is final and never mutated after creation.
 * </p>
 */
@Getter
public class CacheItem {

    /**
     * Approximate fixed overhead per CacheItem object on the JVM heap (bytes).
     * Accounts for: object header (~16), field references and primitives (~40),
     * byte[] array header (~16), and a baseline String object estimate (~40).
     */
    private static final long OBJECT_OVERHEAD_BYTES = 112;

    private final byte[] data;
    private final String contentType;
    private final long expiryTime;
    private final long version;

    /** Estimated heap memory consumed by this entry (bytes). Immutable after construction. */
    private final long memorySize;

    private volatile long lastAccessTime;

    public CacheItem(byte[] data, long ttl, String contentType) {
        this(data, ttl, contentType, System.nanoTime());
    }

    public CacheItem(byte[] data, long ttl, String contentType, long version) {
        this(data, System.currentTimeMillis() + ttl, contentType, version, true);
    }

    public CacheItem(byte[] data, long expiryTime, String contentType, long version, boolean absoluteExpiry) {
        if (!absoluteExpiry) {
            throw new IllegalArgumentException("Absolute-expiry constructor requires absoluteExpiry=true");
        }
        this.data = data;
        this.expiryTime = expiryTime;
        this.contentType = contentType;
        this.lastAccessTime = System.currentTimeMillis();
        this.version = version;
        this.memorySize = estimateMemorySize(data, contentType);
    }

    public void updateAccessTime() {
        this.lastAccessTime = System.currentTimeMillis();
    }

    /**
     * Estimates the approximate heap memory consumed by the given data and content type.
     *
     * @param data        the cached payload
     * @param contentType the MIME type string
     * @return estimated memory in bytes
     */
    private static long estimateMemorySize(byte[] data, String contentType) {
        long size = OBJECT_OVERHEAD_BYTES;
        if (data != null) {
            size += data.length;
        }
        if (contentType != null) {
            // String overhead (~40 bytes) + char storage (2 bytes per character)
            size += 40L + (long) contentType.length() * 2;
        }
        return size;
    }
}
