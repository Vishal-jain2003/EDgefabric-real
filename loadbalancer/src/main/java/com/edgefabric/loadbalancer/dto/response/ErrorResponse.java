package com.edgefabric.loadbalancer.dto.response;

import com.edgefabric.loadbalancer.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    private final ErrorCode errorCode;
    private final String message;
    private final Map<String, Object> details;
    @JsonFormat(shape = JsonFormat.Shape.STRING,
            pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'",
            timezone = "UTC")
    private final Instant timestamp;
}
