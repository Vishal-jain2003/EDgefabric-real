package com.edgefabric.loadbalancer.client;

import com.edgefabric.loadbalancer.dto.response.CacheResponse;
import com.edgefabric.loadbalancer.exception.CacheKeyExpiredException;
import com.edgefabric.loadbalancer.exception.CacheKeyNotFoundException;
import com.edgefabric.loadbalancer.model.CacheNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class HttpClusterClient implements ClusterClient {

    private static final Logger logger = LoggerFactory.getLogger(HttpClusterClient.class);

    private static final String HEADER_EXPIRES_AT = "X-Expires-At";
    private static final String HEADER_QUORUM_VERSION = "X-Quorum-Version";

    private final WebClient webClient;

    // Cache URL base paths per node to reduce GC pressure from String.format()
    private final Map<String, String> urlCache = new ConcurrentHashMap<>();

    @Value("${edgefabric.cluster.sync.timeout-ms:5000}")
    private long timeoutMs;

    public HttpClusterClient() { this.webClient = null; }
    public HttpClusterClient(WebClient webClient, @Value("${edgefabric.cluster.sync.timeout-ms:5000}") long timeoutMs) {
        this.webClient = webClient;
        this.timeoutMs = timeoutMs;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public HttpClusterClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    @Override
    public Mono<Void> forwardPutRequest(CacheNode node, String key, byte[] data,
                                  long expiresAt, String contentType, long version) {
        String url = buildUrl(node, key);
        if (logger.isDebugEnabled()) {
            logger.debug("Client: PUT url '{}' ", url);
        }

        return webClient.put()
                .uri(url)
                .headers(h -> {
                    h.set(HEADER_EXPIRES_AT, String.valueOf(expiresAt));
                    h.set("Content-Type", contentType);
                    if (version > 0) {
                        h.set(HEADER_QUORUM_VERSION, String.valueOf(version));
                    }
                })
                .bodyValue(data)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofMillis(timeoutMs))
                .doOnSuccess(v -> {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Client: Successfully uploaded '{}' to {}", key, node.getNodeId());
                    }
                })
                .then();
    }

    @Override
    public Mono<CacheResponse> forwardGetRequest(CacheNode node, String key) {
        String url = buildUrl(node, key);
        if (logger.isDebugEnabled()) {
            logger.debug("Client: Forwarding GET to URL: {}", url);
        }

        return webClient.get()
                .uri(url)
                .retrieve()
                .onStatus(status -> status.value() == 404,
                        resp -> Mono.error(new CacheKeyNotFoundException(key)))
                .onStatus(status -> status.value() == 410,
                        resp -> {
                            String expiresAtHeader = resp.headers().asHttpHeaders().getFirst(HEADER_EXPIRES_AT);
                            long expiresAt = -1L;
                            if (expiresAtHeader != null) {
                                try {
                                    expiresAt = Long.parseLong(expiresAtHeader);
                                } catch (NumberFormatException ignored) {
                                    // Use -1 if header is invalid
                                }
                            }
                            return Mono.error(new CacheKeyExpiredException(key, expiresAt));
                        })
                .toEntity(byte[].class)
                .map(response -> {
                    String respContentType = response.getHeaders().getContentType() != null
                            ? response.getHeaders().getContentType().toString()
                            : "application/octet-stream";
                    long ver = parseHeaderAsLong(response.getHeaders().getFirst(HEADER_QUORUM_VERSION),
                            HEADER_QUORUM_VERSION, node.getNodeId());
                    long exp = parseHeaderAsLong(response.getHeaders().getFirst(HEADER_EXPIRES_AT),
                            HEADER_EXPIRES_AT, node.getNodeId());

                    if (logger.isDebugEnabled()) {
                        logger.debug("Client: GET success | key={} node={} version={} expiresAt={}",
                                key, node.getNodeId(), ver, exp);
                    }

                    return CacheResponse.builder()
                            .data(response.getBody())
                            .contentType(respContentType)
                            .version(ver)
                            .expiresAt(exp)
                            .build();
                })
                .timeout(Duration.ofMillis(timeoutMs));
    }

    @Override
    public Mono<Long> forwardTouchRequest(CacheNode node, String key, long newExpiresAt, long version) {
        String url = buildUrl(node, key) + "/touch";
        if (logger.isDebugEnabled()) {
            logger.debug("Client: PATCH touch url '{}' newExpiresAt={} version={}", url, newExpiresAt, version);
        }

        return webClient.patch()
                .uri(url)
                .headers(h -> {
                    h.set(HEADER_EXPIRES_AT, String.valueOf(newExpiresAt));
                    h.set(HEADER_QUORUM_VERSION, String.valueOf(version));
                })
                .retrieve()
                .onStatus(status -> status.value() == 404,
                        resp -> Mono.error(new CacheKeyNotFoundException(key)))
                .onStatus(status -> status.value() == 410,
                        resp -> {
                            String expiresAtHeader = resp.headers().asHttpHeaders().getFirst(HEADER_EXPIRES_AT);
                            long expiresAt = -1L;
                            if (expiresAtHeader != null) {
                                try { expiresAt = Long.parseLong(expiresAtHeader); }
                                catch (NumberFormatException ignored) { }
                            }
                            return Mono.error(new CacheKeyExpiredException(key, expiresAt));
                        })
                .toEntity(Void.class)
                .map(response -> {
                    String appliedHeader = response.getHeaders().getFirst(HEADER_EXPIRES_AT);
                    long applied = parseHeaderAsLong(appliedHeader, HEADER_EXPIRES_AT, node.getNodeId());
                    if (logger.isDebugEnabled()) {
                        logger.debug("Client: touch success | key={} node={} appliedExpiresAt={}",
                                key, node.getNodeId(), applied);
                    }
                    return applied > 0 ? applied : newExpiresAt;
                })
                .timeout(Duration.ofMillis(timeoutMs));
    }

    /**
     * Build URL with cached base paths to reduce String allocation and GC pressure.
     * Caches "http://host:port/api/v1/internal/cache/" per node, then appends key.
     */
    private String buildUrl(CacheNode node, String key) {
        String nodeKey = node.getHost() + ":" + node.getPort();
        String basePath = urlCache.computeIfAbsent(nodeKey,
            k -> "http://" + node.getHost() + ":" + node.getPort() + "/api/v1/internal/cache/");
        return basePath + key;
    }

    private long parseHeaderAsLong(String headerValue, String headerName, String nodeId) {
        if (headerValue == null) {
            logger.warn("Client: Missing header '{}' from node {}", headerName, nodeId);
            return -1L;
        }
        try {
            return Long.parseLong(headerValue);
        } catch (NumberFormatException e) {
            logger.warn("Client: Invalid header '{}' value '{}' from node {}", headerName, headerValue, nodeId);
            return -1L;
        }
    }
}
