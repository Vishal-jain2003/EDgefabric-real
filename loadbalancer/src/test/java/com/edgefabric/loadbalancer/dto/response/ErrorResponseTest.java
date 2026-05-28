package com.edgefabric.loadbalancer.dto.response;

import com.edgefabric.loadbalancer.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ErrorResponseTest {

    @Test
    void builderAndGetters() {
        Instant now = Instant.now();
        Map<String, Object> details = Map.of("field", "value");

        ErrorResponse response = ErrorResponse.builder()
                .errorCode(ErrorCode.NOT_FOUND)
                .message("Not found")
                .details(details)
                .timestamp(now)
                .build();

        assertEquals(ErrorCode.NOT_FOUND, response.getErrorCode());
        assertEquals("Not found", response.getMessage());
        assertEquals(details, response.getDetails());
        assertEquals(now, response.getTimestamp());
    }

    @Test
    void builderWithoutDetails() {
        ErrorResponse response = ErrorResponse.builder()
                .errorCode(ErrorCode.INTERNAL_ERROR)
                .message("Something went wrong")
                .timestamp(Instant.now())
                .build();

        assertNull(response.getDetails());
        assertEquals(ErrorCode.INTERNAL_ERROR, response.getErrorCode());
    }
}

