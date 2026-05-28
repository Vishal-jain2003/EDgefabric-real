package com.edgefabric.loadbalancer.service;

import com.edgefabric.loadbalancer.dto.response.CacheResponse;
import com.edgefabric.loadbalancer.dto.response.TouchCacheResponse;
import com.edgefabric.loadbalancer.wal.WalEntry;
import com.edgefabric.loadbalancer.wal.WalWriter;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Service
public class CacheGatewayService {

    private static final Logger logger = LoggerFactory.getLogger(CacheGatewayService.class);

    private final QuorumService quorumService;
    private final Optional<WalWriter> walWriter;

    public CacheGatewayService(QuorumService quorumService,
                               @Autowired(required = false) WalWriter walWriter) {
        this.quorumService = quorumService;
        this.walWriter = Optional.ofNullable(walWriter);
    }

    /**
     * Perform quorum write with enhanced WAL tracking for anti-entropy.
     * With virtual threads enabled, blocking here is acceptable as virtual threads
     * handle blocking I/O efficiently without exhausting thread pools.
     *
     * <p>Uses {@link QuorumService#quorumWriteWithMetadata} to capture which nodes
     * succeeded/failed, enabling WAL-based targeted repair for partial write failures.
     */
    @Observed(name = "gateway.put", contextualName = "gateway-put-operation")
    public void put(String tenant, String key, byte[] data, long expiresAt, String contentType) {
        String tenantKey = tenant + ":" + key;

        if (logger.isDebugEnabled()) {
            logger.debug("LB-Service: Routing PUT via quorum | tenant={} key={}", tenant, key);
        }

        // Block on Mono - acceptable with virtual threads enabled
        // Capture write outcome metadata for WAL
        WalWriteMetadata metadata = quorumService.quorumWriteWithMetadata(
                tenantKey, data, expiresAt, contentType
        ).block();

        // AC10: WAL append failure must never propagate to the caller — it is a non-fatal side-effect
        try {
            walWriter.ifPresent(w -> {
                if (metadata != null) {
                    // Enhanced WAL entry with node outcome tracking
                    WalEntry entry = WalEntry.forQuorumPut(
                            tenantKey, data, expiresAt, contentType, metadata.version(),
                            metadata.successfulNodes(), metadata.failedNodes()
                    );
                    w.append(entry);

                    if (metadata.hasFailures()) {
                        logger.info("WAL recorded partial write for key={} | failed nodes: {}",
                                tenantKey, metadata.failedNodes());
                    }
                } else {
                    // Fallback for null metadata (should not happen)
                    logger.warn("Null metadata from quorumWrite for key={}, using legacy WAL entry", tenantKey);
                    w.append(WalEntry.forPut(tenantKey, data, expiresAt, contentType));
                }
            });
        } catch (Exception e) {
            logger.warn("WAL append failed (non-fatal) for key={}: {}", tenantKey, e.getMessage());
        }
    }

    /**
     * Extends the TTL of an existing cache entry without transferring the value.
     * Blocking call — acceptable with virtual threads enabled.
     *
     * @param tenant the tenant namespace (from X-Tenant header)
     * @param key    the cache key
     * @param ttlMs  the new TTL in milliseconds (must be > 0)
     * @return TouchCacheResponse with the applied expiresAt and ttlMs
     */
    @Observed(name = "gateway.touch", contextualName = "gateway-touch-operation")
    public TouchCacheResponse touch(String tenant, String key, long ttlMs) {
        String tenantKey = tenant + ":" + key;
        long newExpiresAt = System.currentTimeMillis() + ttlMs;

        if (logger.isDebugEnabled()) {
            logger.debug("LB-Service: Routing TOUCH via quorum | tenant={} key={} ttlMs={}", tenant, key, ttlMs);
        }

        // Block on Mono — acceptable with virtual threads enabled.
        // quorumTouch always emits exactly one TouchResult or errors; block() is never null here.
        QuorumService.TouchResult result = quorumService.quorumTouch(tenantKey, newExpiresAt).block();
        if (result == null) {
            // Defensive: should never happen — quorumTouch always emits one result or throws
            throw new IllegalStateException("quorumTouch returned empty Mono for key: " + tenantKey);
        }

        // WAL is not written for TOUCH — TTL-only mutations are idempotent and low-risk

        return TouchCacheResponse.builder()
                .key(key)
                .expiresAt(result.expiresAt())
                .ttlMs(ttlMs)
                .build();
    }

    /**
     * GET is blocking (acceptable with virtual threads).
     * QuorumRead itself is reactive internally, but we block here for Spring MVC.
     */
    @Observed(name = "gateway.get", contextualName = "gateway-get-operation")
    public CacheResponse get(String tenant, String key) {
        String tenantKey = tenant + ":" + key;

        if (logger.isDebugEnabled()) {
            logger.debug("LB-Service: Routing GET via quorum | tenant={} key={}", tenant, key);
        }
        // Block on Mono - acceptable with virtual threads enabled
        return quorumService.quorumRead(tenantKey).block();
    }

}
