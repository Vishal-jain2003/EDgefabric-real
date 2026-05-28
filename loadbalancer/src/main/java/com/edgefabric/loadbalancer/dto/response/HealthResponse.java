package com.edgefabric.loadbalancer.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public class HealthResponse {

    private final String status;
    @JsonFormat(shape = JsonFormat.Shape.STRING,
            pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'",
            timezone = "UTC")
    private final Instant timestamp;

    public HealthResponse(String status, Instant timestamp) {
        this.status = status;
        this.timestamp = timestamp;
    }

    public String getStatus() {
        return status;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}