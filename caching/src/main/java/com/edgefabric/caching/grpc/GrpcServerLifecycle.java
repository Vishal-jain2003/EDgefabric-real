package com.edgefabric.caching.grpc;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Spring-managed lifecycle wrapper around the Netty gRPC server.
 *
 * <p>Started after the bean container is ready and stopped on shutdown so that
 * graceful drain semantics still apply (Spring's {@code server.shutdown=graceful}
 * pauses the HTTP server first; the gRPC server is then closed via
 * {@link #stop()} during {@code @PreDestroy}).</p>
 *
 * <p>Disabled via {@code edgefabric.grpc.enabled=false} when the legacy HTTP-only
 * setup is desired (e.g. constrained dev environments).</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "edgefabric.grpc.enabled", havingValue = "true", matchIfMissing = true)
public class GrpcServerLifecycle {

    private final CacheGrpcService cacheGrpcService;
    private final int port;
    private Server server;

    public GrpcServerLifecycle(
            CacheGrpcService cacheGrpcService,
            @Value("${edgefabric.grpc.port:9091}") int port) {
        this.cacheGrpcService = cacheGrpcService;
        this.port = port;
    }

    @PostConstruct
    public void start() throws IOException {
        this.server = NettyServerBuilder.forPort(port)
                .addService(cacheGrpcService)
                .build()
                .start();
        log.info("gRPC server started on port {}", port);
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            log.info("Shutting down gRPC server on port {}", port);
            server.shutdown();
            try {
                if (!server.awaitTermination(10, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                server.shutdownNow();
            }
        }
    }
}

