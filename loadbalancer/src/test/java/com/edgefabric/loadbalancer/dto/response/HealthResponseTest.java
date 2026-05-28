package com.edgefabric.loadbalancer.dto.response;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class HealthResponseTest {

    @Test
    void constructorAndGetters() {
        Instant now = Instant.now();
        HealthResponse response = new HealthResponse("UP", now);

        assertEquals("UP", response.getStatus());
        assertEquals(now, response.getTimestamp());
    }

    @Test
    void differentStatusValues() {
        HealthResponse down = new HealthResponse("DOWN", Instant.now());
        assertEquals("DOWN", down.getStatus());
    }
}

