package com.edgefabric.loadbalancer.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * Public API response for PUT cache operations.
 * This DTO is exposed to clients and does not contain internal quorum/version data.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PutCacheResponse {
    private final String message;
    private final String key;
    @JsonFormat(shape = JsonFormat.Shape.STRING,
            pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'",
            timezone = "UTC")
    private final Instant timestamp;
    private final long expiresAt;
}
