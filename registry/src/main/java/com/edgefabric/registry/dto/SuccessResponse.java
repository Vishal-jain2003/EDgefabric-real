package com.edgefabric.registry.dto;

public class SuccessResponse {
    private final String message;
    public SuccessResponse(String message) {
        this.message = message;
    }
    public String getMessage() {
        return message;
    }
}
