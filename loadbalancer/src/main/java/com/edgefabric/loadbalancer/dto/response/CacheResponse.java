package com.edgefabric.loadbalancer.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import reactor.core.publisher.Flux;
import org.springframework.core.io.buffer.DataBuffer;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CacheResponse {
    private final String message;
    private final String key;
    @JsonFormat(shape = JsonFormat.Shape.STRING,
            pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'",
            timezone = "UTC")
    private final Instant timestamp;
    private final long expiresAt;

    // Internal fields used by the service layer — not exposed in API responses or Swagger schema
    @JsonIgnore
    @Schema(hidden = true)
    private final byte[] data;
    @JsonIgnore
    @Schema(hidden = true)
    private final Flux<DataBuffer> dataFlux;
    @JsonIgnore
    @Schema(hidden = true)
    private final String contentType;
    @JsonIgnore
    @Schema(hidden = true)
    private final long version;
}
