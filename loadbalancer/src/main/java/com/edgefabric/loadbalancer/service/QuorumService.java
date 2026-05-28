package com.edgefabric.loadbalancer.service;

import com.edgefabric.loadbalancer.client.ClusterClient;
import com.edgefabric.loadbalancer.config.QuorumProperties;
import com.edgefabric.loadbalancer.dto.response.CacheResponse;
import com.edgefabric.loadbalancer.exception.CacheKeyExpiredException;
import com.edgefabric.loadbalancer.exception.CacheKeyNotFoundException;
import com.edgefabric.loadbalancer.exception.QuorumNotMetException;
import com.edgefabric.loadbalancer.metrics.QuorumMetricsService;
import com.edgefabric.loadbalancer.model.CacheNode;
import com.edgefabric.loadbalancer.service.ReadRepairService.StaleReplica;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;


@Service
public class QuorumService {

    private static final Logger logger = LoggerFactory.getLogger(QuorumService.class);
    private static final String WRITE_OPERATION = "WRITE";

    /**
     * Monotonic version counter seeded from wall-clock time so that:
     * (1) different LB instances start at different ranges — no cross-instance collisions,
     * (2) a JVM restart produces versions higher than any written before the restart,
     * (3) each {@code nextVersion()} call returns a value strictly greater than the last
     *     via {@link AtomicLong#incrementAndGet()} — eliminating the non-monotonic
     *     {@code System.nanoTime()} bug (EPMICMPHE-212).
     */
    private final AtomicLong versionCounter = new AtomicLong(System.currentTimeMillis());

    private final CacheRouter cacheRouter;
    private final ClusterClient clusterClient;
    private final QuorumProperties quorumProperties;
    private final ReadRepairService readRepairService;
    private final QuorumMetricsService metricsService;

    public QuorumService(CacheRouter cacheRouter,
                         ClusterClient clusterClient,
                         QuorumProperties quorumProperties,
                         ReadRepairService readRepairService,
                         QuorumMetricsService metricsService) {
        this.cacheRouter = cacheRouter;
        this.clusterClient = clusterClient;
        this.quorumProperties = quorumProperties;
        this.readRepairService = readRepairService;
        this.metricsService = metricsService;
    }

    /**
     * Returns the next monotonic version number by incrementing the counter.
     */
    private long nextVersion() {
        return versionCounter.incrementAndGet();
    }

    // ─────────────────────────────────────────
    // WRITE QUORUM (Reactive)
    // ─────────────────────────────────────────

    @Observed(name = "quorum.write", contextualName = "quorum-write-operation")
    public Mono<Void> quorumWrite(String tenantKey, byte[] data, long expiresAt, String contentType) {

        int replicaCount = quorumProperties.getReplicationFactor();
        int writeQuorum = quorumProperties.getWrite();
        long timeoutMs = quorumProperties.getTimeoutMs();

        List<CacheNode> replicas = cacheRouter.routeToReplicas(tenantKey, replicaCount);
        int available = replicas.size();

        if (available < writeQuorum) {
            logger.error("Quorum WRITE impossible: available nodes ({}) < W ({})", available, writeQuorum);
            metricsService.recordWrite("quorum_not_met");
            return Mono.error(new QuorumNotMetException(WRITE_OPERATION, writeQuorum, available));
        }

        long version = nextVersion();

        if (logger.isDebugEnabled()) {
            logger.debug("Quorum WRITE starting | key={} N={} W={} available={} version={} expiresAt={}",
                    tenantKey, replicaCount, writeQuorum, available, version, expiresAt);
        }

        // Send PUT requests to all replicas in parallel
        return Flux.fromIterable(replicas)
                .flatMap(node ->
                    clusterClient.forwardPutRequest(node, tenantKey, data, expiresAt, contentType, version)
                        .then(Mono.just(1))  // Convert success to emission of 1
                        .doOnSuccess(v -> {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Quorum WRITE success on node {}", node.getNodeId());
                            }
                        })
                        .doOnError(e -> {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Quorum WRITE failed on node {}: {}", node.getNodeId(), e.getMessage());
                            }
                        })
                        .onErrorResume(e -> Mono.empty())  // Convert errors to empty (no emission)
                )
                .take(writeQuorum)  // Wait for W successful writes
                .collectList()
                .timeout(Duration.ofMillis(timeoutMs))
                .flatMap(successList -> {
                    int successes = successList.size();
                    if (successes < writeQuorum) {
                        logger.error("Quorum WRITE failed | key={} required={} achieved={}",
                                tenantKey, writeQuorum, successes);
                        metricsService.recordWrite("quorum_not_met");
                        return Mono.error(new QuorumNotMetException(WRITE_OPERATION, writeQuorum, successes));
                    }
                    if (logger.isDebugEnabled()) {
                        logger.debug("Quorum WRITE succeeded | key={} W={} achieved={}",
                                tenantKey, writeQuorum, successes);
                    }
                    metricsService.recordWrite("success");
                    return Mono.<Void>empty();  // Explicit type parameter for Mono<Void>
                })
                .onErrorResume(java.util.concurrent.TimeoutException.class, e -> {
                    logger.error("Quorum WRITE timeout | key={} after {}ms", tenantKey, timeoutMs);
                    metricsService.recordWrite("timeout");
                    return Mono.error(new QuorumNotMetException(WRITE_OPERATION, writeQuorum, 0));
                });
    }

    /**
     * Quorum write with enhanced outcome tracking for WAL-based anti-entropy.
     * Returns metadata containing version, successful nodes, and failed nodes.
     *
     * <p>This variant enables targeted repair by recording exactly which nodes
     * missed a write during partial quorum success (e.g., W=2/3 with 1 failure).
     */
    @Observed(name = "quorum.write.metadata", contextualName = "quorum-write-metadata-operation")
    public Mono<WalWriteMetadata> quorumWriteWithMetadata(String tenantKey, byte[] data,
                                                           long expiresAt, String contentType) {
        int replicaCount = quorumProperties.getReplicationFactor();
        int writeQuorum = quorumProperties.getWrite();
        long timeoutMs = quorumProperties.getTimeoutMs();

        List<CacheNode> replicas = cacheRouter.routeToReplicas(tenantKey, replicaCount);
        int available = replicas.size();

        if (available < writeQuorum) {
            logger.error("Quorum WRITE impossible: available nodes ({}) < W ({})", available, writeQuorum);
            metricsService.recordWrite("quorum_not_met");
            return Mono.error(new QuorumNotMetException(WRITE_OPERATION, writeQuorum, available));
        }

        long version = nextVersion();

        if (logger.isDebugEnabled()) {
            logger.debug("Quorum WRITE (with metadata) starting | key={} N={} W={} available={} version={} expiresAt={}",
                    tenantKey, replicaCount, writeQuorum, available, version, expiresAt);
        }

        // Track which nodes successfully stored the entry and which actively failed.
        // Two separate concurrent sets:
        //   successfulNodes — populated by doOnSuccess when the node responds OK
        //   failedNodes     — populated by doOnError when the node returns an error
        // Nodes cancelled by .take() (because quorum was already met) are added to
        // neither set: they were not attempted and are not treated as failures in the WAL.
        Set<String> successfulNodes = ConcurrentHashMap.newKeySet();
        Set<String> failedNodes = ConcurrentHashMap.newKeySet();

        // Unlike quorumWrite (which uses .take(W) for fast-exit), this variant collects
        // all N node outcomes so the WAL can accurately track which nodes succeeded and
        // which actively failed.  Each per-node Mono emits:
        //   - the nodeId String on success (after recording it in successfulNodes)
        //   - nothing (empty Mono) on failure (after recording it in failedNodes)
        // collectList() therefore receives only the successful nodeId emissions.
        return Flux.fromIterable(replicas)
                .flatMap(node ->
                    clusterClient.forwardPutRequest(node, tenantKey, data, expiresAt, contentType, version)
                        .then(Mono.just(node.getNodeId()))
                        .doOnSuccess(nodeId -> {
                            successfulNodes.add(nodeId);
                            if (logger.isDebugEnabled()) {
                                logger.debug("Quorum WRITE success on node {}", nodeId);
                            }
                        })
                        .doOnError(e -> {
                            failedNodes.add(node.getNodeId());
                            if (logger.isDebugEnabled()) {
                                logger.debug("Quorum WRITE failed on node {}: {}", node.getNodeId(), e.getMessage());
                            }
                        })
                        .onErrorResume(e -> Mono.empty())
                )
                .collectList()
                .timeout(Duration.ofMillis(timeoutMs))
                .flatMap(successList -> {
                    int successes = successList.size();

                    if (successes < writeQuorum) {
                        logger.error("Quorum WRITE failed | key={} required={} achieved={}",
                                tenantKey, writeQuorum, successes);
                        metricsService.recordWrite("quorum_not_met");
                        return Mono.error(new QuorumNotMetException(WRITE_OPERATION, writeQuorum, successes));
                    }

                    if (logger.isDebugEnabled()) {
                        logger.debug("Quorum WRITE succeeded | key={} W={} achieved={} successful={} failed={}",
                                tenantKey, writeQuorum, successes, successfulNodes, failedNodes);
                    }

                    if (!failedNodes.isEmpty()) {
                        logger.info("Partial write success for key={} | successful={} failed={} (will be tracked in WAL)",
                                tenantKey, successfulNodes, failedNodes);
                    }

                    metricsService.recordWrite("success");
                    return Mono.just(new WalWriteMetadata(version, successfulNodes, failedNodes));
                })
                .onErrorResume(java.util.concurrent.TimeoutException.class, e -> {
                    logger.error("Quorum WRITE timeout | key={} after {}ms", tenantKey, timeoutMs);
                    metricsService.recordWrite("timeout");
                    return Mono.error(new QuorumNotMetException(WRITE_OPERATION, writeQuorum, 0));
                });
    }

    // ─────────────────────────────────────────
    // READ QUORUM (Reactive)
    // ─────────────────────────────────────────

    @Observed(name = "quorum.read", contextualName = "quorum-read-operation")
    public Mono<CacheResponse> quorumRead(String tenantKey) {

        int replicaCount = quorumProperties.getReplicationFactor();
        int readQuorum = quorumProperties.getRead();
        long timeoutMs = quorumProperties.getTimeoutMs();

        List<CacheNode> replicas = cacheRouter.routeToReplicas(tenantKey, replicaCount);
        int available = replicas.size();

        if (available < readQuorum) {
            logger.error("Quorum READ impossible: available nodes ({}) < R ({})", available, readQuorum);
            metricsService.recordRead("quorum_not_met");
            return Mono.error(new QuorumNotMetException("READ", readQuorum, available));
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Quorum READ starting | key={} N={} R={} available={}",
                    tenantKey, replicaCount, readQuorum, available);
        }

        // Send GET requests to all replicas in parallel
        return Flux.fromIterable(replicas)
                .flatMap(node ->
                    clusterClient.forwardGetRequest(node, tenantKey)
                        .map(response -> new NodeResponse(node, response, null))
                        .doOnSuccess(nr -> {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Quorum READ success on node {} (version={}, expiresAt={})",
                                        node.getNodeId(),
                                        nr.response().getVersion(),
                                        nr.response().getExpiresAt());
                            }
                        })
                        .onErrorResume(CacheKeyNotFoundException.class, e -> {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Key {} not found on node {}", tenantKey, node.getNodeId());
                            }
                            return Mono.just(new NodeResponse(node, null, e));
                        })
                        .onErrorResume(CacheKeyExpiredException.class, e -> {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Key {} expired on node {}", tenantKey, node.getNodeId());
                            }
                            return Mono.just(new NodeResponse(node, null, e));
                        })
                        .onErrorResume(throwable -> {
                            logger.warn("Quorum READ failed on node {}: {}", node.getNodeId(), throwable.getMessage());
                            return Mono.just(new NodeResponse(node, null, (Exception) throwable));
                        })
                )
                .collectList()
                .timeout(Duration.ofMillis(timeoutMs))
                .flatMap(allResponses -> {
                    // Count successful responses
                    List<NodeResponse> successfulResponses = allResponses.stream()
                            .filter(nr -> nr.response() != null)
                            .toList();

                    int successes = successfulResponses.size();

                    // Check if all responses are cache misses
                    if (successes == 0) {
                        boolean allMissed = allResponses.stream()
                                .allMatch(nr -> nr.error() instanceof CacheKeyNotFoundException
                                        || nr.error() instanceof CacheKeyExpiredException);
                        if (allMissed) {
                            return Mono.error(new CacheKeyNotFoundException(tenantKey));
                        }
                    }

                    if (successes < readQuorum) {
                        logger.error("Quorum READ failed | key={} required={} achieved={}",
                                tenantKey, readQuorum, successes);
                        metricsService.recordRead("quorum_not_met");
                        return Mono.error(new QuorumNotMetException("READ", readQuorum, successes));
                    }

                    // Find the response with the highest version (winner)
                    NodeResponse winner = successfulResponses.stream()
                            .max(Comparator.comparingLong(nr -> nr.response().getVersion()))
                            .orElseThrow(() -> new QuorumNotMetException("READ", readQuorum, 0));

                    // Detect version conflicts: if we have multiple distinct versions in the quorum
                    long distinctVersions = successfulResponses.stream()
                            .map(nr -> nr.response().getVersion())
                            .distinct()
                            .count();
                    if (distinctVersions > 1) {
                        metricsService.recordVersionConflict();
                        if (logger.isDebugEnabled()) {
                            logger.debug("Version conflict detected for key={}: {} distinct versions in quorum",
                                    tenantKey, distinctVersions);
                        }
                    }

                    if (logger.isDebugEnabled()) {
                        logger.debug("Quorum READ succeeded | key={} R={} achieved={} winnerVersion={} winnerExpiresAt={} from={}",
                                tenantKey,
                                readQuorum,
                                successes,
                                winner.response().getVersion(),
                                winner.response().getExpiresAt(),
                                winner.node().getNodeId());
                    }

                    // Trigger async read repair
                    triggerReadRepair(tenantKey, replicas, allResponses, winner.response());

                    metricsService.recordRead("success");
                    return Mono.just(winner.response());
                })
                .onErrorResume(java.util.concurrent.TimeoutException.class, e -> {
                    logger.error("Quorum READ timeout | key={} after {}ms", tenantKey, timeoutMs);
                    return Mono.error(new QuorumNotMetException("READ", readQuorum, 0));
                });
    }

    /**
     * Builds the stale-replica list from all collected responses and delegates
     * async repair to {@link ReadRepairService}.
     */
    private void triggerReadRepair(String tenantKey,
            List<CacheNode> replicas,
            List<NodeResponse> collectedResponses,
            CacheResponse winner) {

        List<StaleReplica> staleReplicas = new ArrayList<>();

        for (CacheNode node : replicas) {

            NodeResponse match = collectedResponses.stream()
                    .filter(nr -> nr.node().equals(node))
                    .findFirst()
                    .orElse(null);

            long version = (match == null || match.response() == null)
                    ? -1L
                    : match.response().getVersion();

            staleReplicas.add(new StaleReplica(node, version));
        }

        // Record repair metric before triggering async repair
        long staleCount = staleReplicas.stream()
                .filter(sr -> sr.version() < winner.getVersion())
                .count();
        if (staleCount > 0) {
            metricsService.recordRepair();
        }

        readRepairService.repairStaleReplicas(tenantKey, staleReplicas, winner);
    }

    // ─────────────────────────────────────────
    // TOUCH QUORUM (Reactive)
    // ─────────────────────────────────────────

    /**
     * Extends the TTL of an existing entry on W quorum nodes without transferring the value.
     * Fan-out pattern is identical to {@link #quorumWrite} — PATCH is sent to all N replicas
     * in parallel and the first W successful responses satisfy the quorum.
     *
     * @param tenantKey    the tenant-prefixed cache key
     * @param newExpiresAt the new absolute expiry epoch-milliseconds
     * @return Mono emitting a {@link TouchResult} with the applied expiresAt
     */
    @Observed(name = "quorum.touch", contextualName = "quorum-touch-operation")
    public Mono<TouchResult> quorumTouch(String tenantKey, long newExpiresAt) {
        int replicaCount = quorumProperties.getReplicationFactor();
        int writeQuorum  = quorumProperties.getWrite();
        long timeoutMs   = quorumProperties.getTimeoutMs();

        List<CacheNode> replicas = cacheRouter.routeToReplicas(tenantKey, replicaCount);
        int available = replicas.size();

        if (available < writeQuorum) {
            logger.error("Quorum TOUCH impossible: available nodes ({}) < W ({})", available, writeQuorum);
            metricsService.recordWrite("quorum_not_met");
            return Mono.error(new QuorumNotMetException("TOUCH", writeQuorum, available));
        }

        long version = nextVersion();

        if (logger.isDebugEnabled()) {
            logger.debug("Quorum TOUCH starting | key={} N={} W={} available={} version={} newExpiresAt={}",
                    tenantKey, replicaCount, writeQuorum, available, version, newExpiresAt);
        }

        // Collect all node outcomes so we can detect the all-miss case (identical pattern to quorumRead).
        // NodeTouchResponse: appliedExpiresAt >= 0 = success; -1 = transient failure; error != null = key miss
        return Flux.fromIterable(replicas)
                .flatMap(node ->
                    clusterClient.forwardTouchRequest(node, tenantKey, newExpiresAt, version)
                        .map(applied -> new NodeTouchResponse(node, applied, null))
                        .doOnSuccess(r -> {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Quorum TOUCH success on node {} appliedExpiresAt={}",
                                        node.getNodeId(), r.appliedExpiresAt());
                            }
                        })
                        .onErrorResume(CacheKeyNotFoundException.class,
                                e -> Mono.just(new NodeTouchResponse(node, -1L, e)))
                        .onErrorResume(CacheKeyExpiredException.class,
                                e -> Mono.just(new NodeTouchResponse(node, -1L, e)))
                        .onErrorResume(e -> {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Quorum TOUCH failed on node {}: {}", node.getNodeId(), e.getMessage());
                            }
                            return Mono.just(new NodeTouchResponse(node, -1L, (Exception) e));
                        })
                )
                .collectList()
                .timeout(Duration.ofMillis(timeoutMs))
                .flatMap(allResponses -> {
                    List<NodeTouchResponse> successes = allResponses.stream()
                            .filter(r -> r.error() == null)
                            .toList();

                    int successCount = successes.size();

                    // All nodes returned key-miss → propagate as 404
                    if (successCount == 0) {
                        boolean allMissed = allResponses.stream()
                                .allMatch(r -> r.error() instanceof CacheKeyNotFoundException
                                        || r.error() instanceof CacheKeyExpiredException);
                        if (allMissed) {
                            return Mono.error(new CacheKeyNotFoundException(tenantKey));
                        }
                    }

                    if (successCount < writeQuorum) {
                        logger.error("Quorum TOUCH failed | key={} required={} achieved={}",
                                tenantKey, writeQuorum, successCount);
                        metricsService.recordWrite("quorum_not_met");
                        return Mono.error(new QuorumNotMetException("TOUCH", writeQuorum, successCount));
                    }

                    long appliedExpiresAt = successes.get(0).appliedExpiresAt();
                    if (logger.isDebugEnabled()) {
                        logger.debug("Quorum TOUCH succeeded | key={} W={} achieved={}",
                                tenantKey, writeQuorum, successCount);
                    }
                    metricsService.recordWrite("success");
                    return Mono.just(new TouchResult(tenantKey, appliedExpiresAt));
                })
                .onErrorResume(java.util.concurrent.TimeoutException.class, e -> {
                    logger.error("Quorum TOUCH timeout | key={} after {}ms", tenantKey, timeoutMs);
                    metricsService.recordWrite("timeout");
                    return Mono.error(new QuorumNotMetException("TOUCH", writeQuorum, 0));
                });
    }

    /**
     * Result of a successful quorum touch operation.
     *
     * @param key       the tenant-prefixed cache key
     * @param expiresAt the applied absolute expiry epoch-milliseconds
     */
    public record TouchResult(String key, long expiresAt) {}

    /** Record for TOUCH fan-out: tracks node, applied expiresAt (-1 = failure), and any error. */
    private record NodeTouchResponse(CacheNode node, long appliedExpiresAt, Exception error) {}

    /**
     * Record holding node, response, and potential error for quorum operations.
     */
    private record NodeResponse(CacheNode node, CacheResponse response, Exception error) {}

    // ─────────────────────────────────────────
    // PUBLIC ACCESSORS (for WAL-based anti-entropy)
    // ─────────────────────────────────────────

    /**
     * Returns the ClusterClient for direct node communication.
     * Used by WAL replay to send targeted repair PUTs to specific nodes.
     */
    public ClusterClient getClusterClient() {
        return clusterClient;
    }

    /**
     * Returns the CacheRouter to resolve node IDs to CacheNode instances.
     * Used by WAL replay to find failed nodes for targeted repair.
     */
    public CacheRouter getCacheRouter() {
        return cacheRouter;
    }
}
