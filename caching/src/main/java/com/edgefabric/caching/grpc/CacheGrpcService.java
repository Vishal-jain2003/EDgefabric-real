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
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * gRPC server-side implementation of {@code CacheService.GetCache} and
 * {@code CacheService.PutCache}.
 *
 * <p>Mirrors the semantics of the internal HTTP cache endpoints, including
 * the drain check and delegating to {@link InternalCacheService}.
 *
 * <p>HTTP → gRPC status mapping:
 * <ul>
 *   <li>404 (key absent)        → {@link Status#NOT_FOUND}</li>
 *   <li>410 (key expired)       → {@link Status#FAILED_PRECONDITION}</li>
 *   <li>503 (node draining)     → {@link Status#UNAVAILABLE}</li>
 *   <li>400 (invalid input)     → {@link Status#INVALID_ARGUMENT}</li>
 * </ul>
 *
 * <p>The legacy HTTP endpoints remain active and unchanged — anti-entropy,
 * peer reads and existing tests are unaffected.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheGrpcService extends CacheServiceGrpc.CacheServiceImplBase {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final InternalCacheService cacheService;
    private final DrainService drainService;

    @Override
    public void getCache(GetCacheRequest request, StreamObserver<GetCacheResponse> responseObserver) {
        String key = request.getKey();

        if (key == null || key.isEmpty()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("key must not be empty")
                    .asRuntimeException());
            return;
        }

        if (drainService.isDraining() && !drainService.isDrainingWithinGracePeriod()) {
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("Node is draining - grace period expired")
                    .asRuntimeException());
            return;
        }

        try {
            CacheItem item = cacheService.get(key);

            GetCacheResponse response = GetCacheResponse.newBuilder()
                    .setData(ByteString.copyFrom(item.getData()))
                    .setContentType(item.getContentType() != null
                            ? item.getContentType()
                            : DEFAULT_CONTENT_TYPE)
                    .setVersion(item.getVersion())
                    .setExpiresAt(item.getExpiryTime())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (CacheNotFoundException ex) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(ex.getMessage())
                    .asRuntimeException());
        } catch (CacheExpiredException ex) {
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(ex.getMessage())
                    .asRuntimeException());
        } catch (Exception ex) {
            log.error("gRPC GetCache failed for key={}: {}", key, ex.getMessage(), ex);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("internal error")
                    .asRuntimeException());
        }
    }

    @Override
    public void putCache(PutCacheRequest request, StreamObserver<PutCacheResponse> responseObserver) {
        String key = request.getKey();

        if (key == null || key.isEmpty()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("key must not be empty")
                    .asRuntimeException());
            return;
        }

        if (drainService.isDraining() && !drainService.isDrainingWithinGracePeriod()) {
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("Node is draining - grace period expired")
                    .asRuntimeException());
            return;
        }

        try {
            long appliedExpiresAt = cacheService.storeData(
                    key,
                    request.getData().toByteArray(),
                    request.getExpiresAt(),
                    request.getContentType().isEmpty() ? DEFAULT_CONTENT_TYPE : request.getContentType(),
                    request.getVersion());

            responseObserver.onNext(PutCacheResponse.newBuilder()
                    .setSuccess(true)
                    .setExpiresAt(appliedExpiresAt)
                    .build());
            responseObserver.onCompleted();

        } catch (Exception ex) {
            log.error("gRPC PutCache failed for key={}: {}", key, ex.getMessage(), ex);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("internal error")
                    .asRuntimeException());
        }
    }
}

