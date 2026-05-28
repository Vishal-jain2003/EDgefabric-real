package com.edgefabric.registry.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class ErrorResponses {
    private final String message;
    private final int status;
    private final Instant timestamp;

    public ErrorResponses(String message, int status) {
        this.message = message;
        this.status = status;
        this.timestamp = Instant.now();
    }

}
    