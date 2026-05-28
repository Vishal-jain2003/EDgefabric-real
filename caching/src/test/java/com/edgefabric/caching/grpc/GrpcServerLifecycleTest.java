package com.edgefabric.caching.grpc;

import com.edgefabric.caching.service.DrainService;
import com.edgefabric.caching.service.InternalCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link GrpcServerLifecycle} — verifies the Netty gRPC server is
 * started in {@link GrpcServerLifecycle#start()} and gracefully torn down in
 * {@link GrpcServerLifecycle#stop()}.
 */
class GrpcServerLifecycleTest {

    private GrpcServerLifecycle lifecycle;

    @AfterEach
    void tearDown() {
        if (lifecycle != null) {
            lifecycle.stop();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void start_bindsToConfiguredPort_andStopReleasesIt() throws IOException {
        int port = freePort();
        CacheGrpcService grpcService = new CacheGrpcService(
                mock(InternalCacheService.class), mock(DrainService.class));

        lifecycle = new GrpcServerLifecycle(grpcService, port);
        lifecycle.start();

        // Port should now be in use — attempting to bind another ServerSocket on it should fail.
        assertThrows(IOException.class, () -> {
            try (ServerSocket s = new ServerSocket(port)) {
                fail("Expected port " + port + " to be in use, but it was free");
            }
        });

        lifecycle.stop();
        lifecycle = null; // prevent double-stop in @AfterEach

        // After stop(), the port should be releasable. Some platforms hold the
        // socket in TIME_WAIT briefly, so we use SO_REUSEADDR for the verification.
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new java.net.InetSocketAddress(port));
            assertTrue(socket.isBound());
        }
    }

    @Test
    void stop_isSafeToCall_whenServerWasNeverStarted() {
        lifecycle = new GrpcServerLifecycle(
                new CacheGrpcService(mock(InternalCacheService.class), mock(DrainService.class)),
                0);

        // Should be a no-op and not throw.
        assertDoesNotThrow(() -> lifecycle.stop());
        lifecycle = null;
    }
}

