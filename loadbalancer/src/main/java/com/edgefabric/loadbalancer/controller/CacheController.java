package com.edgefabric.loadbalancer.controller;

import com.edgefabric.loadbalancer.dto.response.CacheResponse;
import com.edgefabric.loadbalancer.dto.response.PutCacheResponse;
import com.edgefabric.loadbalancer.dto.response.TouchCacheResponse;
import com.edgefabric.loadbalancer.service.CacheGatewayService;
import com.edgefabric.loadbalancer.util.StructuredLogContext;
import com.edgefabric.loadbalancer.validation.CacheEntryValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@Tag(name = "Cache", description = "Store and retrieve binary cache entries via the load balancer. " +
        "Data is replicated across cache nodes using quorum-based consistent hashing.")
@Validated
@RestController
@RequestMapping("/api/v1/cache")
public class CacheController {

    private static final Logger logger = LoggerFactory.getLogger(CacheController.class);
    private final CacheGatewayService gatewayService;
    private final CacheEntryValidator cacheEntryValidator;

    public CacheController(CacheGatewayService gatewayService, CacheEntryValidator cacheEntryValidator) {
        this.gatewayService = gatewayService;
        this.cacheEntryValidator = cacheEntryValidator;
    }

    @Operation(summary = "Store a cache entry",
            description = "Stores binary data under the given key. Data is replicated to W nodes out of N " +
                    "using quorum writes. The entry expires after the specified TTL (default 60 s).")
    @ApiResponse(responseCode = "201", description = "Entry stored successfully",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PutCacheResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid key, TTL, or payload", content = @Content)
    @ApiResponse(responseCode = "413", description = "Payload exceeds the 2 MB limit", content = @Content)
    @ApiResponse(responseCode = "503", description = "Quorum not reached - insufficient healthy nodes", content = @Content)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Raw binary payload to store (max 2 MB)",
            content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    schema = @Schema(type = "string", format = "binary")))
    @PutMapping("/{key}")
    public ResponseEntity<PutCacheResponse> putCache(
            @Parameter(description = "Cache key (1–250 characters: alphanumeric, colon, hyphen, underscore)",
                    required = true, example = "user:session:abc123")
            @PathVariable
            @NotBlank(message = "Cache Key cannot be empty")
            @Size(max = 250, message = "Cache key must not exceed 250 characters")
            @Pattern(regexp = "^[a-zA-Z0-9:_-]+$", message = "Key should only contain alphanumeric characters, colon, hyphen and underscore.")
                    String key,
            @Parameter(in = ParameterIn.HEADER, name = "X-Tenant",
                    description = "Tenant namespace for key isolation (default: \"default\")",
                    schema = @Schema(type = "string", defaultValue = "default"))
            @RequestHeader(value = "X-Tenant", defaultValue = "default") String tenant,
            @Parameter(in = ParameterIn.HEADER, name = "X-TTL-MS",
                    description = "Time-to-live in milliseconds. Must be > 0. Default: 60000 (60 s).",
                    schema = @Schema(type = "integer", format = "int64", defaultValue = "60000"))
            @RequestHeader(value = "X-TTL-MS", defaultValue = "60000")
            @Positive(message = "TTL value should be positive") long ttl,
            @Parameter(hidden = true)
            @RequestHeader(value = "Content-Type", defaultValue = "application/octet-stream") String contentType,
            @RequestBody(required = false) byte[] data) {

        long startTime = System.currentTimeMillis();
        cacheEntryValidator.validateData(data);

        try (var logCtx = StructuredLogContext.create()
                .operation("PUT")
                .tenant(tenant)
                .key(key)) {

            if (logger.isDebugEnabled()) {
                logger.debug("Validation successful for key, TTL and value");
                logger.debug("LB: Received PUT request | size={} type={}",
                        (data != null ? data.length : 0), contentType);
            }

            long expiresAt = System.currentTimeMillis() + ttl;
            gatewayService.put(tenant, key, data, expiresAt, contentType);

            long duration = System.currentTimeMillis() - startTime;
            logCtx.duration(duration).statusCode(HttpStatus.CREATED.value()).result("SUCCESS");
            logger.info("Cache PUT completed successfully");

            PutCacheResponse response = PutCacheResponse.builder()
                    .message("Cache entry stored successfully")
                    .key(key)
                    .timestamp(Instant.now())
                    .expiresAt(expiresAt)
                    .build();

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }
    }

    @Operation(summary = "Retrieve a cache entry",
            description = "Reads binary data for the given key from the cache cluster using quorum reads. " +
                    "Returns the raw payload with its original Content-Type and expiry time.")
    @ApiResponse(responseCode = "200", description = "Cache hit - raw payload returned",
            content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE))
    @ApiResponse(responseCode = "400", description = "Invalid key format", content = @Content)
    @ApiResponse(responseCode = "404", description = "Key not found or expired", content = @Content)
    @ApiResponse(responseCode = "503", description = "Quorum not reached - insufficient healthy nodes", content = @Content)
    @GetMapping("/{key}")
    public ResponseEntity<byte[]> getCache(
            @Parameter(description = "Cache key to retrieve", required = true, example = "user:session:abc123")
            @PathVariable
            @NotBlank(message = "Cache key cannot be empty")
            @Size(max = 250, message = "Cache key must not exceed 250 characters")
            @Pattern(regexp = "^[a-zA-Z0-9:_-]+$", message = "Key should only contain alphanumeric characters, colon, hyphen and underscore.")
            String key,
            @Parameter(in = ParameterIn.HEADER, name = "X-Tenant",
                    description = "Tenant namespace - must match the namespace used on PUT",
                    schema = @Schema(type = "string", defaultValue = "default"))
            @RequestHeader(value = "X-Tenant", defaultValue = "default") String tenant) {

        if (logger.isDebugEnabled()) {
            logger.debug("LB: Received GET request | tenant={} key={}", tenant, key);
        }

        // Blocking call - acceptable with virtual threads
        CacheResponse response = gatewayService.get(tenant, key);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(response.getContentType()))
                .header("X-Expires-At", String.valueOf(response.getExpiresAt()))
                .body(response.getData());
    }

    @Operation(summary = "Extend the TTL of a cache entry",
            description = "Extends the expiry time of an existing cache entry without re-uploading the value. " +
                    "The entry's expiresAt is set to (now + ttl) on W quorum nodes. " +
                    "Returns 404 if the key does not exist or has already expired.")
    @ApiResponse(responseCode = "200", description = "TTL extended — entry will now expire at expiresAt",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TouchCacheResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid ttl (zero or negative)", content = @Content)
    @ApiResponse(responseCode = "404", description = "Key not found or already expired", content = @Content)
    @ApiResponse(responseCode = "503", description = "Quorum not reached - insufficient healthy nodes", content = @Content)
    @PostMapping("/{key}/touch")
    public ResponseEntity<TouchCacheResponse> touchCache(
            @Parameter(description = "Cache key to extend", required = true, example = "user:session:abc123")
            @PathVariable
            @NotBlank(message = "Cache key cannot be empty")
            @Size(max = 250, message = "Cache key must not exceed 250 characters")
            @Pattern(regexp = "^[a-zA-Z0-9:_-]+$",
                    message = "Key should only contain alphanumeric characters, colon, hyphen and underscore.")
            String key,
            @Parameter(description = "New TTL in milliseconds. Must be > 0.",
                    required = true, example = "3600000")
            @RequestParam
            @Positive(message = "TTL must be positive")
            long ttl,
            @Parameter(in = ParameterIn.HEADER, name = "X-Tenant",
                    description = "Tenant namespace for key isolation (default: \"default\")",
                    schema = @Schema(type = "string", defaultValue = "default"))
            @RequestHeader(value = "X-Tenant", defaultValue = "default") String tenant) {

        if (logger.isDebugEnabled()) {
            logger.debug("LB: Received TOUCH request | tenant={} key={} ttlMs={}", tenant, key, ttl);
        }

        TouchCacheResponse response = gatewayService.touch(tenant, key, ttl);
        return ResponseEntity.ok(response);
    }
}
