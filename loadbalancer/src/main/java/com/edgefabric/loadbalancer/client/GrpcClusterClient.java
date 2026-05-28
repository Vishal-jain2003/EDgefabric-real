package com.edgefabric.loadbalancer.client;

import com.edgefabric.caching.grpc.proto.CacheServiceGrpc;
import com.edgefabric.caching.grpc.proto.GetCacheRequest;
import com.edgefabric.caching.grpc.proto.GetCacheResponse;
import com.edgefabric.caching.grpc.proto.PutCacheRequest;
import com.edgefabric.loadbalancer.dto.response.CacheResponse;
import com.google.protobuf.ByteString;
import com.edgefabric.loadbalancer.exception.CacheKeyExpiredException;
import com.edgefabric.loadbalancer.exception.CacheKeyNotFoundException;
import com.edgefabric.loadbalancer.model.CacheNode;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * gRPC implementation of {@link ClusterClient}.
 *
 * <p>Replaces the HTTP {@code GET /api/v1/internal/cache/{key}} call with a
 * binary gRPC {@code GetCache} RPC for lower latency on quorum reads (3 parallel
 * GETs per client request). PUT is also forwarded via gRPC using the
 * {@code PutCache} RPC, removing the HTTP delegate for the write path.
 *
 * <p>Channels are cached per cache-node so that a single HTTP/2 connection is
 * reused across requests (multiplexed streams). Channels are shut down on
 * application stop via {@link #shutdown()}.
 *
 * <p>Activated when {@code edgefabric.cluster.get.transport=grpc}; otherwise
 * the bean is not registered and the legacy {@link HttpClusterClient} handles
 * both GET and PUT — preserving the default behaviour of the platform.
 */
public class GrpcClusterClient implements ClusterClient {

    private static final Logger logger = LoggerFactory.getLogger(GrpcClusterClient.class);

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    private static final long KEEPALIVE_SECONDS = 30L;
    private static final long CHANNEL_SHUTDOWN_WAIT_SECONDS = 5L;

    private final HttpClusterClient httpClusterClient; // PUT delegate
    private final int grpcPort;
    private final long deadlineMs;
    private final Map<String, ManagedChannel> channelCache = new ConcurrentHashMap<>();
    private final ChannelFactory channelFactory;

    public GrpcClusterClient(
            HttpClusterClient httpClusterClient,
            @Value("${edgefabric.cluster.grpc.port:9091}") int grpcPort,
            @Value("${edgefabric.cluster.sync.timeout-ms:5000}") long deadlineMs) {
        this(httpClusterClient, grpcPort, deadlineMs, GrpcClusterClient::defaultChannel);
    }

    /** Test seam — allows in-process channels to be supplied. */
    public GrpcClusterClient(
            HttpClusterClient httpClusterClient,
            int grpcPort,
            long deadlineMs,
            ChannelFactory channelFactory) {
        this.httpClusterClient = httpClusterClient;
        this.grpcPort = grpcPort;
        this.deadlineMs = deadlineMs;
        this.channelFactory = channelFactory;
    }

    private static ManagedChannel defaultChannel(String host, int port) {
        return NettyChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .keepAliveTime(KEEPALIVE_SECONDS, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .build();
    }

    @Override
    public Mono<Void> forwardPutRequest(CacheNode node, String key, byte[] data,
                                        long expiresAt, String contentType, long version) {
        if (logger.isDebugEnabled()) {
            logger.debug("gRPC PUT forwarding | node={} host={}:{} key={}",
                    node.getNodeId(), node.getHost(), grpcPort, key);
        }
        return Mono.fromRunnable(() -> doPut(node, key, data, expiresAt, contentType, version))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private void doPut(CacheNode node, String key, byte[] data,
                       long expiresAt, String contentType, long version) {
        ManagedChannel channel = channelCache.computeIfAbsent(
                node.getHost() + ":" + grpcPort,
                k -> channelFactory.create(node.getHost(), grpcPort));

        CacheServiceGrpc.CacheServiceBlockingStub stub = CacheServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);

        PutCacheRequest request = PutCacheRequest.newBuilder()
                .setKey(key)
                .setData(data != null ? ByteString.copyFrom(data) : ByteString.EMPTY)
                .setContentType(contentType != null ? contentType : "application/octet-stream")
                .setExpiresAt(expiresAt)
                .setVersion(version)
                .build();

        stub.putCache(request);

        if (logger.isDebugEnabled()) {
            logger.debug("gRPC PUT success | node={} key={} expiresAt={}",
                    node.getNodeId(), key, expiresAt);
        }
    }

    @Override
    public Mono<Long> forwardTouchRequest(CacheNode node, String key, long newExpiresAt, long version) {
        // TOUCH is a lightweight PATCH — delegate to HTTP client (no gRPC proto defined for TOUCH).
        return httpClusterClient.forwardTouchRequest(node, key, newExpiresAt, version);
    }

    @Override
    public Mono<CacheResponse> forwardGetRequest(CacheNode node, String key) {
        if (logger.isDebugEnabled()) {
            logger.debug("gRPC GET forwarding | node={} host={}:{} key={}",
                    node.getNodeId(), node.getHost(), grpcPort, key);
        }

        // Wrap blocking gRPC stub call in a bounded-elastic scheduler so the
        // QuorumService event loop is never blocked. The reactive contract
        // matches HttpClusterClient exactly.
        return Mono.fromCallable(() -> doGet(node, key))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private CacheResponse doGet(CacheNode node, String key) {
        ManagedChannel channel = channelCache.computeIfAbsent(
                node.getHost() + ":" + grpcPort,
                k -> channelFactory.create(node.getHost(), grpcPort));

        CacheServiceGrpc.CacheServiceBlockingStub stub = CacheServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);

        try {
            GetCacheResponse resp = stub.getCache(GetCacheRequest.newBuilder().setKey(key).build());

            if (logger.isDebugEnabled()) {
                logger.debug("gRPC GET success | node={} key={} version={} expiresAt={}",
                        node.getNodeId(), key, resp.getVersion(), resp.getExpiresAt());
            }

            return CacheResponse.builder()
                    .data(resp.getData().toByteArray())
                    .contentType(resp.getContentType().isEmpty()
                            ? DEFAULT_CONTENT_TYPE
                            : resp.getContentType())
                    .version(resp.getVersion())
                    .expiresAt(resp.getExpiresAt())
                    .build();

        } catch (StatusRuntimeException ex) {
            Status.Code code = ex.getStatus().getCode();
            if (code == Status.Code.NOT_FOUND) {
                throw new CacheKeyNotFoundException(key);
            }
            if (code == Status.Code.FAILED_PRECONDITION) {
                // Expired — caching side does not stream expiresAt back through gRPC trailers
                // (matches HTTP 410 behaviour where header may be -1L if missing).
                throw new CacheKeyExpiredException(key, -1L);
            }
            throw ex;
        }
    }

    @PreDestroy
    public void shutdown() {
        channelCache.values().forEach(ch -> {
            try {
                ch.shutdown();
                if (!ch.awaitTermination(CHANNEL_SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    ch.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ch.shutdownNow();
            }
        });
        channelCache.clear();
    }

    /** Factory for {@link ManagedChannel}s — overridable in tests. */
    @FunctionalInterface
    public interface ChannelFactory {
        ManagedChannel create(String host, int port);
    }
}

