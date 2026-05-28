package com.edgefabric.loadbalancer.dto.response;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class CacheResponseTest {

    @Test
    void builderAndGetters() {
        byte[] data = "hello".getBytes();
        Instant now = Instant.now();

        CacheResponse response = CacheResponse.builder()
                .message("success")
                .key("mykey")
                .timestamp(now)
                .data(data)
                .contentType("text/plain")
                .version(42L)
                .expiresAt(99999L)
                .build();

        assertEquals("success", response.getMessage());
        assertEquals("mykey", response.getKey());
        assertEquals(now, response.getTimestamp());
        assertArrayEquals(data, response.getData());
        assertEquals("text/plain", response.getContentType());
        assertEquals(42L, response.getVersion());
        assertEquals(99999L, response.getExpiresAt());
    }

    @Test
    void builderWithNullableFields() {
        CacheResponse response = CacheResponse.builder()
                .data(null)
                .contentType(null)
                .version(0L)
                .expiresAt(0L)
                .build();

        assertNull(response.getData());
        assertNull(response.getContentType());
        assertEquals(0L, response.getVersion());
        assertEquals(0L, response.getExpiresAt());
        assertNull(response.getMessage());
        assertNull(response.getKey());
    }
}

