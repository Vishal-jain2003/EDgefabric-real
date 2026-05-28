package com.edgefabric.loadbalancer.client;

import com.edgefabric.caching.grpc.proto.CacheServiceGrpc;
import com.edgefabric.caching.grpc.proto.GetCacheRequest;
import com.edgefabric.caching.grpc.proto.GetCacheResponse;
import com.edgefabric.caching.grpc.proto.PutCacheRequest;
import com.edgefabric.caching.grpc.proto.PutCacheResponse;
import com.edgefabric.loadbalancer.dto.response.CacheResponse;
import com.edgefabric.loadbalancer.exception.CacheKeyExpiredException;
import com.edgefabric.loadbalancer.exception.CacheKeyNotFoundException;
import com.edgefabric.loadbalancer.model.CacheNode;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GrpcClusterClient} backed by an in-process gRPC server.
 *
 * <p>Coverage includes:
 * <ul>
 *   <li>Successful GET response mapping to {@link CacheResponse}</li>
 *   <li>Default content-type fallback when server omits it</li>
 *   <li>Status mapping: NOT_FOUND, FAILED_PRECONDITION, generic INTERNAL</li>
 *   <li>Channel reuse — single channel per host across multiple calls</li>
 *   <li>{@code shutdown()} clears the channel cache</li>
 *   <li>PUT delegation to {@link HttpClusterClient}</li>
 * </ul>
 */
class GrpcClusterClientTest {

    private Server inProcessServer;
    private String serverName;
    private GrpcClusterClient client;
    private HttpClusterClient httpDelegate;
    private ManagedChannel testChannel;
    private final AtomicInteger channelFactoryCalls = new AtomicInteger();
    private final CacheNode node = new CacheNode("node-1", "localhost", 8082);

    @BeforeEach
    void setUp() {
        serverName = "grpc-test-" + UUID.randomUUID();
        httpDelegate = mock(HttpClusterClient.class);
        channelFactoryCalls.set(0);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (client != null) {
            client.shutdown();
        }
        if (testChannel != null && !testChannel.isShutdown()) {
            testChannel.shutdownNow();
        }
        if (inProcessServer != null) {
            inProcessServer.shutdownNow().awaitTermination();
        }
    }

    private void startServer(CacheServiceGrpc.CacheServiceImplBase impl) throws IOException {
        inProcessServer = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(impl)
                .build()
                .start();
        client = new GrpcClusterClient(httpDelegate, 9091, 5000L,
                (host, port) -> {
                    channelFactoryCalls.incrementAndGet();
                    testChannel = InProcessChannelBuilder.forName(serverName)
                            .directExecutor()
                            .build();
                    return testChannel;
                });
    }

    @Test
    void forwardGetRequest_returnsCacheResponse_onSuccess() throws IOException {
        AtomicReference<String> capturedKey = new AtomicReference<>();
        startServer(new CacheServiceGrpc.CacheServiceImplBase() {
            @Override
            public void getCache(GetCacheRequest request, StreamObserver<GetCacheResponse> responseObserver) {
                capturedKey.set(request.getKey());
                responseObserver.onNext(GetCacheResponse.newBuilder()
                        .setData(ByteString.copyFromUtf8("Hello Rayyan"))
                        .setContentType("text/plain")
                        .setVersion(777L)
                        .setExpiresAt(123456789L)
                        .build());
                responseObserver.onCompleted();
            }
        });

        CacheResponse result = client.forwardGetRequest(node, "user_123").block();

        assertNotNull(result);
        assertEquals("user_123", capturedKey.get());
        assertEquals("Hello Rayyan", new String(result.getData(), StandardCharsets.UTF_8));
        assertEquals("text/plain", result.getContentType());
        assertEquals(777L, result.getVersion());
        assertEquals(123456789L, result.getExpiresAt());
    }

    @Test
    void forwardGetRequest_throwsCacheKeyNotFoundException_onNotFoundStatus() throws IOException {
        startServer(new CacheServiceGrpc.CacheServiceImplBase() {
            @Override
            public void getCache(GetCacheRequest request, StreamObserver<GetCacheResponse> responseObserver) {
                responseObserver.onError(Status.NOT_FOUND.withDescription("missing").asRuntimeException());
            }
        });

        assertThrows(CacheKeyNotFoundException.class,
                () -> client.forwardGetRequest(node, "missing_key").block());
    }

    @Test
    void forwardGetRequest_throwsCacheKeyExpiredException_onFailedPreconditionStatus() throws IOException {
        startServer(new CacheServiceGrpc.CacheServiceImplBase() {
            @Override
            public void getCache(GetCacheRequest request, StreamObserver<GetCacheResponse> responseObserver) {
                responseObserver.onError(Status.FAILED_PRECONDITION.withDescription("expired").asRuntimeException());
            }
        });

        CacheKeyExpiredException ex = assertThrows(CacheKeyExpiredException.class,
                () -> client.forwardGetRequest(node, "expired_key").block());
        assertEquals("expired_key", ex.getKey());
        // expiresAt is unknown over gRPC trailers, surfaced as -1L (matches HTTP 410 missing-header behaviour).
        assertEquals(-1L, ex.getExpiresAt());
    }

    @Test
    void forwardGetRequest_returnsDefaultContentType_whenServerOmitsIt() throws IOException {
        startServer(new CacheServiceGrpc.CacheServiceImplBase() {
            @Override
            public void getCache(GetCacheRequest request, StreamObserver<GetCacheResponse> responseObserver) {
                responseObserver.onNext(GetCacheResponse.newBuilder()
                        .setData(ByteString.copyFromUtf8("payload"))
                        .setVersion(1L)
                        .setExpiresAt(2L)
                        .build());
                responseObserver.onCompleted();
            }
        });

        CacheResponse result = client.forwardGetRequest(node, "noct").block();

        assertNotNull(result);
        assertEquals("application/octet-stream", result.getContentType());
        assertEquals(1L, result.getVersion());
        assertEquals(2L, result.getExpiresAt());
    }

    @Test
    void forwardGetRequest_propagatesGenericGrpcError_asStatusRuntimeException() throws IOException {
        startServer(new CacheServiceGrpc.CacheServiceImplBase() {
            @Override
            public void getCache(GetCacheRequest request, StreamObserver<GetCacheResponse> responseObserver) {
                responseObserver.onError(Status.INTERNAL.withDescription("boom").asRuntimeException());
            }
        });

        Throwable thrown = assertThrows(RuntimeException.class,
                () -> client.forwardGetRequest(node, "kaboom").block());
        // Reactor wraps blocking exceptions; unwrap to find the gRPC StatusRuntimeException.
        Throwable cause = thrown;
        while (cause != null && !(cause instanceof StatusRuntimeException)) {
            cause = cause.getCause();
        }
        assertNotNull(cause, "Expected StatusRuntimeException in cause chain");
        assertEquals(Status.Code.INTERNAL, ((StatusRuntimeException) cause).getStatus().getCode());
    }

    @Test
    void forwardGetRequest_reusesChannelAcrossMultipleCalls() throws IOException {
        startServer(new CacheServiceGrpc.CacheServiceImplBase() {
            @Override
            public void getCache(GetCacheRequest request, StreamObserver<GetCacheResponse> responseObserver) {
                responseObserver.onNext(GetCacheResponse.newBuilder()
                        .setData(ByteString.copyFromUtf8("ok"))
                        .setVersion(1L)
                        .setExpiresAt(0L)
                        .build());
                responseObserver.onCompleted();
            }
        });

        client.forwardGetRequest(node, "k1").block();
        client.forwardGetRequest(node, "k2").block();
        client.forwardGetRequest(node, "k3").block();

        // 3 RPC calls, but only one channel created — proves caching works.
        assertEquals(1, channelFactoryCalls.get());
    }

    @Test
    void shutdown_clearsChannelCache_andTerminatesAllChannels() throws IOException {
        startServer(new CacheServiceGrpc.CacheServiceImplBase() {
            @Override
            public void getCache(GetCacheRequest request, StreamObserver<GetCacheResponse> responseObserver) {
                responseObserver.onNext(GetCacheResponse.newBuilder()
                        .setData(ByteString.copyFromUtf8("ok"))
                        .setVersion(1L)
                        .setExpiresAt(0L)
                        .build());
                responseObserver.onCompleted();
            }
        });

        client.forwardGetRequest(node, "k1").block();
        assertNotNull(testChannel);
        assertFalse(testChannel.isShutdown(), "channel should be live before shutdown");

        client.shutdown();

        assertTrue(testChannel.isShutdown(), "channel should be shut down after shutdown()");
        // Idempotent — calling again must be a no-op.
        assertDoesNotThrow(() -> client.shutdown());
        client = null; // prevent re-shutdown in @AfterEach
    }

    @Test
    void forwardPutRequest_usesGrpcStub_onSuccess() throws IOException {
        long expiresAt = System.currentTimeMillis() + 60_000L;
        AtomicReference<PutCacheRequest> captured = new AtomicReference<>();

        startServer(new CacheServiceGrpc.CacheServiceImplBase() {
            @Override
            public void putCache(PutCacheRequest request,
                                 io.grpc.stub.StreamObserver<PutCacheResponse> responseObserver) {
                captured.set(request);
                responseObserver.onNext(PutCacheResponse.newBuilder()
                        .setSuccess(true)
                        .setExpiresAt(expiresAt)
                        .build());
                responseObserver.onCompleted();
            }
        });

        byte[] payload = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        client.forwardPutRequest(node, "validkey01", payload, expiresAt, "text/plain", 5L).block();

        assertNotNull(captured.get());
        assertEquals("validkey01", captured.get().getKey());
        assertEquals("text/plain", captured.get().getContentType());
        assertEquals(expiresAt, captured.get().getExpiresAt());
        assertEquals(5L, captured.get().getVersion());
        assertArrayEquals(payload, captured.get().getData().toByteArray());
    }

    @Test
    void forwardPutRequest_propagatesUnavailable_asStatusRuntimeException() throws IOException {
        startServer(new CacheServiceGrpc.CacheServiceImplBase() {
            @Override
            public void putCache(PutCacheRequest request,
                                 io.grpc.stub.StreamObserver<PutCacheResponse> responseObserver) {
                responseObserver.onError(
                        Status.UNAVAILABLE.withDescription("node draining").asRuntimeException());
            }
        });

        byte[] payload = {1, 2, 3};
        Throwable thrown = assertThrows(RuntimeException.class,
                () -> client.forwardPutRequest(node, "validkey01", payload,
                        System.currentTimeMillis() + 60_000L, "application/octet-stream", 1L).block());

        Throwable cause = thrown;
        while (cause != null && !(cause instanceof StatusRuntimeException)) {
            cause = cause.getCause();
        }
        assertNotNull(cause, "Expected StatusRuntimeException in cause chain");
        assertEquals(Status.Code.UNAVAILABLE, ((StatusRuntimeException) cause).getStatus().getCode());
    }

    @Test
    void forwardPutRequest_propagatesInternalError_asRuntimeException() throws IOException {
        startServer(new CacheServiceGrpc.CacheServiceImplBase() {
            @Override
            public void putCache(PutCacheRequest request,
                                 io.grpc.stub.StreamObserver<PutCacheResponse> responseObserver) {
                responseObserver.onError(
                        Status.INTERNAL.withDescription("server error").asRuntimeException());
            }
        });

        byte[] payload = {1, 2, 3};
        assertThrows(RuntimeException.class,
                () -> client.forwardPutRequest(node, "validkey01", payload,
                        System.currentTimeMillis() + 60_000L, "application/octet-stream", 1L).block());
    }

    /**
     * End-to-end test that exercises the real {@code defaultChannel()} factory
     * (Netty over a real TCP socket). Confirms the production constructor wires
     * up correctly and the channel keep-alive/plaintext settings parse without
     * error.
     */
    @Test
    void productionConstructor_endToEnd_completesGetCacheCallOverRealNetty() throws IOException {
        // Use port 0 so the OS atomically binds a free port; read it back via getPort()
        // after start(). The old ServerSocket(0) trick releases the port before Netty
        // binds, causing a TOCTOU race on shared CI agents (DEADLINE_EXCEEDED flakiness).
        io.grpc.Server realServer = io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
                .forPort(0)
                .addService(new CacheServiceGrpc.CacheServiceImplBase() {
                    @Override
                    public void getCache(GetCacheRequest request, StreamObserver<GetCacheResponse> responseObserver) {
                        responseObserver.onNext(GetCacheResponse.newBuilder()
                                .setData(ByteString.copyFromUtf8("netty"))
                                .setContentType("text/plain")
                                .setVersion(42L)
                                .setExpiresAt(99L)
                                .build());
                        responseObserver.onCompleted();
                    }
                })
                .build()
                .start();
        int port = realServer.getPort();

        GrpcClusterClient prod = new GrpcClusterClient(httpDelegate, port, 10000L);
        try {
            CacheNode realNode = new CacheNode("real-node", "localhost", 1234);
            CacheResponse result = prod.forwardGetRequest(realNode, "k").block();

            assertNotNull(result);
            assertEquals("netty", new String(result.getData(), StandardCharsets.UTF_8));
            assertEquals(42L, result.getVersion());
            assertEquals(99L, result.getExpiresAt());
        } finally {
            prod.shutdown();
            realServer.shutdownNow();
            try {
                realServer.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Verifies that {@link GrpcClusterClient#shutdown()} handles thread
     * interruption while waiting for channel termination. The thread's
     * interrupt status must be preserved per Java threading conventions.
     */
    @Test
    void shutdown_handlesInterruptedException_andPreservesInterruptFlag() throws IOException {
        startServer(new CacheServiceGrpc.CacheServiceImplBase() {
            @Override
            public void getCache(GetCacheRequest request, StreamObserver<GetCacheResponse> responseObserver) {
                responseObserver.onNext(GetCacheResponse.newBuilder()
                        .setData(ByteString.copyFromUtf8("ok"))
                        .setVersion(1L)
                        .setExpiresAt(0L)
                        .build());
                responseObserver.onCompleted();
            }
        });

        // Populate the channel cache with a real channel.
        client.forwardGetRequest(node, "k").block();

        // Interrupt the current thread BEFORE shutdown — the awaitTermination call
        // inside shutdown() will throw InterruptedException, exercising the catch path.
        Thread.currentThread().interrupt();
        try {
            assertDoesNotThrow(() -> client.shutdown());
            // The catch block must re-set the interrupt flag.
            assertTrue(Thread.currentThread().isInterrupted(),
                    "interrupt flag must be preserved after catching InterruptedException");
        } finally {
            // Clear the interrupt status so subsequent tests/teardown are clean.
            Thread.interrupted();
        }
        client = null; // already shut down
    }
}

