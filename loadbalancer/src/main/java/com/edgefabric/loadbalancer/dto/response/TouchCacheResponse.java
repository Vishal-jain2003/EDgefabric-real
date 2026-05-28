package com.edgefabric.loadbalancer.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

/**
 * Public API response for POST /api/v1/cache/{key}/touch operations.
 * Returns the key, updated absolute expiry time, and the TTL that was applied.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TouchCacheResponse {

    /** The cache key whose TTL was extended. */
    private final String key;

    /** Absolute expiry timestamp in epoch milliseconds (now + ttlMs at time of touch). */
    private final long expiresAt;

    /** The TTL in milliseconds that was applied. */
    private final long ttlMs;
}
