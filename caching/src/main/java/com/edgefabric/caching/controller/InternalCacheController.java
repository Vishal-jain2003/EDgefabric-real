package com.edgefabric.caching.controller;

import com.edgefabric.caching.antiEntropy.StaleKeyRegistry;
import com.edgefabric.caching.client.WalClient;
import com.edgefabric.caching.dto.ApiResponseDTO;
import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.service.DrainService;
import com.edgefabric.caching.service.InternalCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@Tag(name = "Internal Cache", description = "Node-to-node cache replication endpoints. " +
        "Called by the load balancer during quorum writes and by peers during gossip-based replication. " +
        "Not intended for direct client use.")
@RestController
@RequestMapping("/api/v1/internal/cache")
public class InternalCacheController {

    private final InternalCacheService cacheService;
    private final DrainService drainService;
    private final StaleKeyRegistry staleKeyRegistry;

    /**
     * Optional WAL client — only present when {@code cache.wal.enabled=true}.
     * Nullable: when WAL is disabled no {@code WalClient} bean is registered.
     */
    private final WalClient walClient;

    /**
     * Primary constructor. Spring uses this when {@code WalClient} bean is present
     * ({@code cache.wal.enabled=true}). Mockito {@code @InjectMocks} also uses this
     * constructor (largest match) and injects the mock for {@code walClient}.
     */
    @Autowired
    public InternalCacheController(InternalCacheService cacheService,
                                   DrainService drainService,
                                   StaleKeyRegistry staleKeyRegistry,
                                   @Autowired(required = false) WalClient walClient) {
        this.cacheService = cacheService;
        this.drainService = drainService;
        this.staleKeyRegistry = staleKeyRegistry;
        this.walClient = walClient;
    }

    @Operation(summary = "Replicate a cache entry to this node",
            description = "Stores binary data directly on this cache node. Called by the load balancer during " +
                    "quorum writes. The X-Quorum-Version header is used for optimistic concurrency - only the " +
                    "highest version is kept if the same key is written concurrently by multiple coordinators.")
    @ApiResponse(responseCode = "200", description = "Entry stored on this node",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Missing or invalid headers", content = @Content)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Raw binary payload",
            content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    schema = @Schema(type = "string", format = "binary")))
    @PutMapping(value = "/{key}")
    public ResponseEntity<ApiResponseDTO> receiveData(
            @Parameter(description = "Cache key", required = true) @PathVariable("key") String key,
            @RequestBody byte[] data,
            @Parameter(in = ParameterIn.HEADER, name = "X-Expires-At",
                    description = "Absolute expiry time as epoch milliseconds. Takes priority over X-TTL-MS if both are set.",
                    schema = @Schema(type = "integer", format = "int64"))
            @RequestHeader(value = "X-Expires-At", required = false) Long requestedExpiresAt,
            @Parameter(in = ParameterIn.HEADER, name = "X-TTL-MS",
                    description = "Time-to-live in milliseconds (used when X-Expires-At is absent). Default: 60000.",
                    schema = @Schema(type = "integer", format = "int64"))
            @RequestHeader(value = "X-TTL-MS", required = false) Long requestedTtl,
            @Parameter(hidden = true)
            @RequestHeader(value = "Content-Type", defaultValue = "application/octet-stream") String contentType,
            @Parameter(in = ParameterIn.HEADER, name = "X-Quorum-Version",
                    description = "Monotonically increasing version for optimistic concurrency control. " +
                            "The node only stores the entry if this version is >= the stored version.",
                    required = true, schema = @Schema(type = "integer", format = "int64"))
            @RequestHeader(value = "X-Quorum-Version") long version) {

        if (drainService.isDraining()) {
            if (!drainService.isDrainingWithinGracePeriod()) {
                return ResponseEntity.status(503)
                        .body(ApiResponseDTO.error(503, "Node is draining - grace period expired, writes rejected"));
            }
        }

        long expiresAt;
        if (requestedExpiresAt != null) {
            expiresAt = requestedExpiresAt;
        } else {
            long ttl = requestedTtl != null ? requestedTtl : 60000L;
            expiresAt = System.currentTimeMillis() + ttl;
        }

        try {
            long appliedExpiresAt = cacheService.storeData(key, data, expiresAt, contentType, version);

            // Journal the bypass write to the LB WAL — best-effort, never propagates
            if (walClient != null) {
                try {
                    walClient.appendAsync(key, data, appliedExpiresAt, contentType, version);
                } catch (Exception e) {
                    // Intentionally swallowed — WAL append must never fail a cache write
                }
            }

            String responseMessage = "Entry created successfully.";

            if (appliedExpiresAt < expiresAt) {
                responseMessage = String.format(
                        "Entry created successfully. WARNING: Requested expiry exceeded maximum allowed limit. Adjusted to %d.",
                        appliedExpiresAt
                );
            }

            return ResponseEntity.ok()
                    .header("X-Expires-At", String.valueOf(appliedExpiresAt))
                    .body(ApiResponseDTO.success(key, responseMessage));

        } catch (Exception e) {
            // Mark this key as stale for self-healing
            staleKeyRegistry.markStale(key, version, "local_write_failed");
            throw e; // Re-throw to return error to loadbalancer
        }
    }

    @Operation(summary = "Extend the TTL of a cache entry on this node",
            description = "Updates only the expiresAt field of an existing cache entry without changing the value. " +
                    "Called by the load balancer during quorum-touch fan-out. " +
                    "Returns 404 if the key is absent, 410 if the key has expired.")
    @ApiResponse(responseCode = "200", description = "TTL extended successfully",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Key not found on this node", content = @Content)
    @ApiResponse(responseCode = "410", description = "Key present but already expired", content = @Content)
    @ApiResponse(responseCode = "503", description = "Node is draining (grace period expired)", content = @Content)
    @PatchMapping("/{key}/touch")
    public ResponseEntity<ApiResponseDTO> touchEntry(
            @Parameter(description = "Cache key to touch", required = true) @PathVariable("key") String key,
            @Parameter(in = ParameterIn.HEADER, name = "X-Expires-At",
                    description = "New absolute expiry time as epoch milliseconds.",
                    required = true, schema = @Schema(type = "integer", format = "int64"))
            @RequestHeader("X-Expires-At") long newExpiresAt,
            @Parameter(in = ParameterIn.HEADER, name = "X-Quorum-Version",
                    description = "Monotonically increasing version for optimistic concurrency control.",
                    required = true, schema = @Schema(type = "integer", format = "int64"))
            @RequestHeader("X-Quorum-Version") long version) {

        if (drainService.isDraining()) {
            if (!drainService.isDrainingWithinGracePeriod()) {
                return ResponseEntity.status(503)
                        .body(ApiResponseDTO.error(503, "Node is draining - grace period expired, writes rejected"));
            }
        }

        long appliedExpiresAt = cacheService.touch(key, newExpiresAt, version);

        ApiResponseDTO body = ApiResponseDTO.builder()
                .timestamp(Instant.now().toString())
                .status(200)
                .success(true)
                .message("TTL extended successfully.")
                .key(key)
                .expiresAt(appliedExpiresAt)
                .build();

        return ResponseEntity.ok()
                .header("X-Expires-At", String.valueOf(appliedExpiresAt))
                .body(body);
    }

    @Operation(summary = "Read a cache entry from this node",
            description = "Retrieves binary data directly from this node's local store. " +
                    "Returns the raw payload with its Content-Type, quorum version, and expiry time as response headers. " +
                    "If node is draining, new reads are rejected after grace period expires.")
    @ApiResponse(responseCode = "200", description = "Cache hit",
            content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE))
    @ApiResponse(responseCode = "404", description = "Key not found or expired", content = @Content)
    @ApiResponse(responseCode = "503", description = "Node is draining (grace period expired)", content = @Content)
    @GetMapping("/{key}")
    public ResponseEntity<byte[]> getData(
            @Parameter(description = "Cache key to retrieve", required = true) @PathVariable("key") String key) {

        if (drainService.isDraining()) {
            if (!drainService.isDrainingWithinGracePeriod()) {
                return ResponseEntity.status(503).build();
            }
        }

        CacheItem item = cacheService.get(key);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(item.getContentType()))
                .header("X-Quorum-Version", String.valueOf(item.getVersion()))
                .header("X-Expires-At", String.valueOf(item.getExpiryTime()))
                .body(item.getData());
    }
}
