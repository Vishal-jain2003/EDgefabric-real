package com.edgefabric.loadbalancer.config;

import org.junit.jupiter.api.Test;

import org.springframework.web.servlet.config.annotation.CorsRegistry;

import static org.junit.jupiter.api.Assertions.*;

class CorsConfigTest {

    @Test
    void addCorsMappings_doesNotThrow() {
        CorsConfig corsConfig = new CorsConfig();
        CorsRegistry registry = new CorsRegistry();

        assertDoesNotThrow(() -> corsConfig.addCorsMappings(registry));
    }
}

