package com.edgefabric.agentops.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * System health endpoint for the agentic-ops service.
 * Consumed by load balancers, liveness probes, and CI smoke tests.
 */
@Tag(name = "System", description = "Service health check")
@RestController
@RequestMapping("/api/v1/system")
public class HealthController {

    @Operation(summary = "Health check", description = "Returns 200 OK with {\"status\":\"UP\"} when the service is healthy")
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
