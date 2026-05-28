package com.edgefabric.caching.scheduler;

import com.edgefabric.caching.antiEntropy.StaleEntry;
import com.edgefabric.caching.antiEntropy.StaleKeyRegistry;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.migration.NodeInfoHashAdapter;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.service.InternalCacheService;
import com.edgefabric.hashing.core.ConsistentHashRing;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.annotation.Observed;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Self-healing scheduler that repairs stale cache entries.
 *
 * <p>Drains stale keys from {@link StaleKeyRegistry} every 30 seconds (configurable),
 * fetches fresh data from co-replicas, and stores locally.
 *
 * <p>Feature-flagged via {@code cache.self-healing.enabled}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "cache.self-healing.enabled", havingValue = "true", matchIfMissing = true)
public class SelfHealingScheduler {

    private final StaleKeyRegistry staleKeyRegistry;
    private final InternalCacheService cacheService;
    private final MembershipList membershipList;
    private final ConsistentHashRing<NodeInfoHashAdapter> migrationHashRing;
    private final WebClient peerWebClient;
    private final MeterRegistry meterRegistry;

    private final int replicationFactor;
    private final int batchSize;
    private final int retryMax;
    private final int timeoutMs;

    // Metrics
    private Counter selfHealAttempts;
    private Counter selfHealSuccesses;
    private Counter selfHealFailures;
    private Timer selfHealLatency;

    public SelfHealingScheduler(
            StaleKeyRegistry staleKeyRegistry,
            InternalCacheService cacheService,
            MembershipList membershipList,
            ConsistentHashRing<NodeInfoHashAdapter> migrationHashRing,
            WebClient peerWebClient,
            MeterRegistry meterRegistry,
            @Value("${cache.replication.factor:3}") int replicationFactor,
            @Value("${cache.self-healing.batch-size:100}") int batchSize,
            @Value("${cache.self-healing.retry-max:3}") int retryMax,
            @Value("${cache.self-healing.timeout-ms:5000}") int timeoutMs
    ) {
        this.staleKeyRegistry = staleKeyRegistry;
        this.cacheService = cacheService;
        this.membershipList = membershipList;
        this.migrationHashRing = migrationHashRing;
        this.peerWebClient = peerWebClient;
        this.meterRegistry = meterRegistry;
        this.replicationFactor = replicationFactor;
        this.batchSize = batchSize;
        this.retryMax = retryMax;
        this.timeoutMs = timeoutMs;
    }

    @PostConstruct
    public void initMetrics() {
        selfHealAttempts = Counter.builder("edgefabric.self_healing.attempts")
                .description("Total self-healing attempts")
                .register(meterRegistry);

        selfHealSuccesses = Counter.builder("edgefabric.self_healing.successes")
                .description("Successful self-healing repairs")
                .register(meterRegistry);

        selfHealFailures = Counter.builder("edgefabric.self_healing.failures")
                .description("Failed self-healing repairs")
                .register(meterRegistry);

        selfHealLatency = Timer.builder("edgefabric.self_healing.latency")
                .description("Self-healing repair latency")
                .register(meterRegistry);
    }

    /**
     * Scheduled task that heals stale entries every 30 seconds (configurable).
     */
    @Observed(name = "self-healing.run", contextualName = "self-healing-staleness-repair")
    @Scheduled(fixedDelayString = "${cache.self-healing.interval-ms:30000}")
    public void healStaleness() {
        List<StaleEntry> staleEntries = staleKeyRegistry.drainStaleKeys(batchSize);

        if (staleEntries.isEmpty()) {
            return;
        }

        log.info("Starting self-healing round for {} stale keys", staleEntries.size());

        for (StaleEntry entry : staleEntries) {
            selfHealAttempts.increment();

            Timer.Sample sample = Timer.start(meterRegistry);

            try {
                healEntry(entry);
                selfHealSuccesses.increment();
                sample.stop(selfHealLatency);
            } catch (Exception e) {
                log.warn("Self-healing failed for key={} (reason={}): {}", entry.key(), entry.reason(), e.getMessage());
                selfHealFailures.increment();

                // Re-mark as stale for retry
                staleKeyRegistry.markStale(entry.key(), entry.version(), "retry_" + entry.reason());
            }
        }

        log.info("Self-healing round complete (attempted={}, success={}, failed={})",
                staleEntries.size(),
                (long) selfHealSuccesses.count() - ((long) selfHealSuccesses.count() - staleEntries.size()),
                (long) selfHealFailures.count() - ((long) selfHealFailures.count() - staleEntries.size()));
    }

    /**
     * Heals a single stale entry by fetching from a co-replica.
     */
    private void healEntry(StaleEntry entry) {
        String key = entry.key();
        String selfNodeId = membershipList.getSelf().getCacheNodeId();

        // Get co-replicas for this key
        List<NodeInfoHashAdapter> replicas = migrationHashRing.getNodes(key, replicationFactor);

        // Find a peer (not self)
        Optional<NodeInfoHashAdapter> peerOpt = replicas.stream()
                .filter(adapter -> !adapter.getNodeInfo().getCacheNodeId().equals(selfNodeId))
                .findFirst();

        if (peerOpt.isEmpty()) {
            log.warn("No peer available for self-healing key={}", key);
            selfHealFailures.increment();
            staleKeyRegistry.markStale(key, entry.version(), "no_peers_available");
            return;
        }

        NodeInfo peer = peerOpt.get().getNodeInfo();
        String url = buildUrl(peer, key);

        // Fetch fresh value from peer
        ResponseEntity<byte[]> response = peerWebClient.get()
                .uri(url)
                .retrieve()
                .toEntity(byte[].class)
                .block(Duration.ofMillis(timeoutMs));

        if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Failed to fetch from peer: " + peer.getCacheNodeId());
        }

        long peerVersion = Long.parseLong(response.getHeaders().getFirst("X-Quorum-Version"));
        long expiresAt = Long.parseLong(response.getHeaders().getFirst("X-Expires-At"));
        String contentType = response.getHeaders().getFirst("Content-Type");
        byte[] data = response.getBody();

        // Store locally
        cacheService.storeData(key, data, expiresAt, contentType, peerVersion);

        log.info("Self-healed key={} from peer={} (version={})", key, peer.getCacheNodeId(), peerVersion);
    }

    private String buildUrl(NodeInfo peer, String key) {
        return String.format("http://%s:%d/api/v1/internal/cache/%s", peer.getHost(), peer.getServicePort(), key);
    }
}
