package com.edgefabric.caching.controller;

import com.edgefabric.caching.dto.CacheStatsDTO;
import com.edgefabric.caching.service.CacheStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes per-node cache statistics for monitoring and ops tooling.
 * No authentication required — intended for internal ops network only.
 */
@Tag(name = "Cache Stats", description = "Per-node cache statistics endpoint")
@RestController
@RequestMapping("/api/v1/cache")
@RequiredArgsConstructor
public class CacheStatsController {

    private final CacheStatsService cacheStatsService;

    @Operation(
            summary = "Get cache node statistics",
            description = "Returns hit rate, hit/miss counts, cache size, memory usage, " +
                    "and derived health status for this cache node."
    )
    @ApiResponse(
            responseCode = "200",
            description = "Cache statistics snapshot",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CacheStatsDTO.class)
            )
    )
    @GetMapping("/stats")
    public ResponseEntity<CacheStatsDTO> getStats() {
        return ResponseEntity.ok(cacheStatsService.getStats());
    }
}
