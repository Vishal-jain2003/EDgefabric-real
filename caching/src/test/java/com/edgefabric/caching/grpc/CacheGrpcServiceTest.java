package com.edgefabric.caching.grpc;

import com.edgefabric.caching.exception.CacheExpiredException;
import com.edgefabric.caching.exception.CacheNotFoundException;
import com.edgefabric.caching.grpc.proto.CacheServiceGrpc;
import com.edgefabric.caching.grpc.proto.GetCacheRequest;
import com.edgefabric.caching.grpc.proto.GetCacheResponse;
import com.edgefabric.caching.grpc.proto.PutCacheRequest;
import com.edgefabric.caching.grpc.proto.PutCacheResponse;
import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.service.DrainService;
import com.edgefabric.caching.service.InternalCacheService;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CacheGrpcService}. Uses an in-process gRPC server for
 * full round-trip verification of the contract — no real sockets are opened.
 *
 * <p>Coverage targets every branch in {@code getCache}:
 * <ul>
 *   <li>Happy path with explicit content-type</li>
 *   <li>Null content-type → defaults to {@code application/octet-stream}</li>
 *   <li>Empty key → INVALID_ARGUMENT</li>
 *   <li>Drain past grace period → UNAVAILABLE</li>
 *   <li>Draining within grace period → request still served</li>
 *   <li>{@link CacheNotFoundException} → NOT_FOUND</li>
 *   <li>{@link CacheExpiredException} → FAILED_PRECONDITION</li>
 *   <li>Unexpected runtime exception → INTERNAL</li>
 * </ul>
 */
class CacheGrpcServiceTest {

    private Server server;
    private ManagedChannel channel;
    private CacheServiceGrpc.CacheServiceBlockingStub stub;
    private InternalCacheService cacheService;
    private DrainService drainService;

    @BeforeEach
    void setUp() throws IOException {
        cacheService = mock(InternalCacheService.class);
        drainService = mock(DrainService.class);
        when(drainService.isDraining()).thenReturn(false);

        String name = "grpc-server-test-" + UUID.randomUUID();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(new CacheGrpcService(cacheService, drainService))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub = CacheServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination();
    }

    @Test
    void getCache_returnsItem_whenPresent() {
        when(cacheService.get(eq("k1")))
                .thenReturn(new CacheItem("Hi".getBytes(), 999L, "text/plain", 5L, true));

        GetCacheResponse resp = stub.getCache(GetCacheRequest.newBuilder().setKey("k1").build());

        assertEquals("Hi", resp.getData().toStringUtf8());
        assertEquals("text/plain", resp.getContentType());
        assertEquals(5L, resp.getVersion());
        assertEquals(999L, resp.getExpiresAt());
    }

    @Test
    void getCache_returnsDefaultContentType_whenItemContentTypeIsNull() {
        when(cacheService.get(eq("k-null-ct")))
                .thenReturn(new CacheItem("data".getBytes(), 100L, null, 1L, true));

        GetCacheResponse resp = stub.getCache(GetCacheRequest.newBuilder().setKey("k-null-ct").build());

        assertEquals("application/octet-stream", resp.getContentType());
        assertEquals("data", resp.getData().toStringUtf8());
        assertEquals(1L, resp.getVersion());
    }

    @Test
    void getCache_returnsNotFoundStatus_whenKeyMissing() {
        when(cacheService.get(eq("missing")))
                .thenThrow(new CacheNotFoundException("not found"));

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.getCache(GetCacheRequest.newBuilder().setKey("missing").build()));
        assertEquals(Status.Code.NOT_FOUND, ex.getStatus().getCode());
    }

    @Test
    void getCache_returnsFailedPreconditionStatus_whenKeyExpired() {
        when(cacheService.get(eq("exp")))
                .thenThrow(new CacheExpiredException("expired"));

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.getCache(GetCacheRequest.newBuilder().setKey("exp").build()));
        assertEquals(Status.Code.FAILED_PRECONDITION, ex.getStatus().getCode());
    }

    @Test
    void getCache_returnsUnavailableStatus_whenDrainingPastGrace() {
        when(drainService.isDraining()).thenReturn(true);
        when(drainService.isDrainingWithinGracePeriod()).thenReturn(false);

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.getCache(GetCacheRequest.newBuilder().setKey("any").build()));
        assertEquals(Status.Code.UNAVAILABLE, ex.getStatus().getCode());
    }

    @Test
    void getCache_servesRequest_whenDrainingWithinGracePeriod() {
        when(drainService.isDraining()).thenReturn(true);
        when(drainService.isDrainingWithinGracePeriod()).thenReturn(true);
        when(cacheService.get(eq("k-grace")))
                .thenReturn(new CacheItem("x".getBytes(), 50L, "text/plain", 9L, true));

        GetCacheResponse resp = stub.getCache(GetCacheRequest.newBuilder().setKey("k-grace").build());

        assertEquals("x", resp.getData().toStringUtf8());
        assertEquals(9L, resp.getVersion());
    }

    @Test
    void getCache_returnsInvalidArgumentStatus_whenKeyEmpty() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.getCache(GetCacheRequest.newBuilder().setKey("").build()));
        assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
    }

    @Test
    void getCache_returnsInternalStatus_onUnexpectedException() {
        when(cacheService.get(eq("boom")))
                .thenThrow(new RuntimeException("disk on fire"));

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.getCache(GetCacheRequest.newBuilder().setKey("boom").build()));
        assertEquals(Status.Code.INTERNAL, ex.getStatus().getCode());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PutCache tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void putCache_returnsSuccess_whenValidRequest() {
        long expiresAt = System.currentTimeMillis() + 60_000L;
        when(cacheService.storeData(eq("validkey01"), any(byte[].class), eq(expiresAt),
                eq("application/octet-stream"), eq(1L)))
                .thenReturn(expiresAt);

        PutCacheRequest request = PutCacheRequest.newBuilder()
                .setKey("validkey01")
                .setData(ByteString.copyFromUtf8("hello"))
                .setContentType("application/octet-stream")
                .setExpiresAt(expiresAt)
                .setVersion(1L)
                .build();

        PutCacheResponse response = stub.putCache(request);

        assertTrue(response.getSuccess());
        assertEquals(expiresAt, response.getExpiresAt());
        verify(cacheService).storeData(eq("validkey01"), any(byte[].class), eq(expiresAt),
                eq("application/octet-stream"), eq(1L));
    }

    @Test
    void putCache_returnsInvalidArgument_whenKeyIsEmpty() {
        PutCacheRequest request = PutCacheRequest.newBuilder()
                .setKey("")
                .setData(ByteString.copyFromUtf8("hello"))
                .setContentType("application/octet-stream")
                .setExpiresAt(System.currentTimeMillis() + 60_000L)
                .setVersion(1L)
                .build();

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.putCache(request));
        assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
    }

    @Test
    void putCache_returnsUnavailable_whenDrainingPastGracePeriod() {
        when(drainService.isDraining()).thenReturn(true);
        when(drainService.isDrainingWithinGracePeriod()).thenReturn(false);

        PutCacheRequest request = PutCacheRequest.newBuilder()
                .setKey("validkey01")
                .setData(ByteString.copyFromUtf8("data"))
                .setContentType("application/octet-stream")
                .setExpiresAt(System.currentTimeMillis() + 60_000L)
                .setVersion(1L)
                .build();

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.putCache(request));
        assertEquals(Status.Code.UNAVAILABLE, ex.getStatus().getCode());
    }

    @Test
    void putCache_servesRequest_whenDrainingWithinGracePeriod() {
        when(drainService.isDraining()).thenReturn(true);
        when(drainService.isDrainingWithinGracePeriod()).thenReturn(true);

        long expiresAt = System.currentTimeMillis() + 60_000L;
        when(cacheService.storeData(anyString(), any(byte[].class), anyLong(), anyString(), anyLong()))
                .thenReturn(expiresAt);

        PutCacheRequest request = PutCacheRequest.newBuilder()
                .setKey("validkey01")
                .setData(ByteString.copyFromUtf8("data"))
                .setContentType("text/plain")
                .setExpiresAt(expiresAt)
                .setVersion(2L)
                .build();

        PutCacheResponse response = stub.putCache(request);
        assertTrue(response.getSuccess());
    }

    @Test
    void putCache_returnsInternal_onUnexpectedException() {
        when(cacheService.storeData(anyString(), any(byte[].class), anyLong(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("unexpected"));

        PutCacheRequest request = PutCacheRequest.newBuilder()
                .setKey("validkey01")
                .setData(ByteString.copyFromUtf8("data"))
                .setContentType("application/octet-stream")
                .setExpiresAt(System.currentTimeMillis() + 60_000L)
                .setVersion(1L)
                .build();

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.putCache(request));
        assertEquals(Status.Code.INTERNAL, ex.getStatus().getCode());
    }
}

