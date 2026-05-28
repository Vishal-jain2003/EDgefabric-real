package com.edgefabric.loadbalancer.client;

import com.edgefabric.loadbalancer.dto.response.CacheResponse;
import com.edgefabric.loadbalancer.model.CacheNode;
import reactor.core.publisher.Mono;

public interface ClusterClient {
    //cluster client

    Mono<Void> forwardPutRequest(CacheNode node, String key, byte[] data, long expiresAt, String contentType, long version);

    Mono<CacheResponse> forwardGetRequest(CacheNode node, String key);

    /**
     * Forwards a touch (TTL-extend) request to a single cache node.
     * No value is transferred — only the new expiry time.
     *
     * @param node         the target cache node
     * @param key          the tenant-prefixed cache key
     * @param newExpiresAt new absolute expiry in epoch-milliseconds
     * @param version      monotonic quorum version for optimistic concurrency
     * @return Mono emitting the applied expiresAt on success
     */
    Mono<Long> forwardTouchRequest(CacheNode node, String key, long newExpiresAt, long version);
}
