package com.edgefabric.loadbalancer.controller;

import com.edgefabric.loadbalancer.dto.response.HealthResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@Tag(name = "System", description = "Load balancer health and liveness probes")
@RestController
@RequestMapping("/api/v1/system")
public class HealthCheckController {

    @Operation(summary = "Health check", description = "Returns the current health status of the load balancer. " +
            "Used by Docker health checks and monitoring.")
    @ApiResponse(responseCode = "200", description = "Load balancer is healthy",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = HealthResponse.class)))
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> healthCheck() {

     HealthResponse response = new HealthResponse(
             "UP",
             Instant.now()
     );

     return ResponseEntity.ok(response);
 }

}
