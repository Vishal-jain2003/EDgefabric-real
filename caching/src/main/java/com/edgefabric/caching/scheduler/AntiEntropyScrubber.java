package com.edgefabric.caching.scheduler;

import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.migration.NodeInfoHashAdapter;
import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.service.InternalCacheService;
import com.edgefabric.hashing.core.ConsistentHashRing;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Anti-entropy background scrubber using Segment/Partition-Based Random Sampling
 * with Version Comparison (Strategy 2 from ADR EPMICMPHE-228).
 *
 * <p>Each scheduled round:
 * <ol>
 *   <li>Samples K random keys from the local cache store.</li>
 *   <li>For each sampled key, determines the responsible co-replica nodes via the
 *       consistent hash ring (bounded by {@code replicationFactor}).</li>
 *   <li>Skips the key if this node is not one of its replicas (orphan key — key
 *       migration will handle ownership transfer separately).</li>
 *   <li>Fetches the {@code X-Quorum-Version} header from each alive co-replica
 *       via {@code GET /api/v1/internal/cache/{key}}.</li>
 *   <li>If a co-replica's version is lower than the local version, repairs it via
 *       {@code PUT /api/v1/internal/cache/{key}}.</li>
 *   <li>Throttles between keys via a configurable {@code rateLimitMs} sleep.</li>
 * </ol>
 *
 * <p>Enabled at runtime via {@code cache.anti-entropy.enabled=true}.<br>
 * Metrics are recorded as Micrometer counters:
 * {@code edgefabric.anti_entropy.keys_scanned} and
 * {@code edgefabric.anti_entropy.repairs_issued}.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "cache.anti-entropy.enabled", havingValue = "true", matchIfMissing = false)
public class AntiEntropyScrubber {

    private static final String METRIC_KEYS_SCANNED   = "edgefabric.anti_entropy.keys_scanned";
    private static final String METRIC_REPAIRS_ISSUED = "edgefabric.anti_entropy.repairs_issued";

    private final Map<String, CacheItem> store;
    private final MembershipList membershipList;
    private final WebClient peerWebClient;
    private final ConsistentHashRing<NodeInfoHashAdapter> migrationHashRing;
    private final InternalCacheService cacheService;
    private final Counter keysScannedCounter;
    private final Counter repairsIssuedCounter;
    private final int sampleSize;
    private final long rateLimitMs;
    private final int replicationFactor;

    /**
     * Primary constructor used by Spring for autowired production beans.
     */
    public AntiEntropyScrubber(
            Map<String, CacheItem> store,
            MembershipList membershipList,
            WebClient peerWebClient,
            MeterRegistry meterRegistry,
            ConsistentHashRing<NodeInfoHashAdapter> migrationHashRing,
            InternalCacheService cacheService,
            @Value("${cache.anti-entropy.sample-size:20}") int sampleSize,
            @Value("${cache.anti-entropy.rate-limit-ms:10}") long rateLimitMs,
            @Value("${quorum.replication-factor:3}") int replicationFactor) {

        this.store             = store;
        this.membershipList    = membershipList;
        this.peerWebClient     = peerWebClient;
        this.migrationHashRing = migrationHashRing;
        this.cacheService      = cacheService;
        this.sampleSize        = sampleSize;
        this.rateLimitMs       = rateLimitMs;
        this.replicationFactor = replicationFactor;

        this.keysScannedCounter = Counter.builder(METRIC_KEYS_SCANNED)
                .description("Total number of cache keys sampled by anti-entropy scrubber")
                .register(meterRegistry);

        this.repairsIssuedCounter = Counter.builder(METRIC_REPAIRS_ISSUED)
                .description("Total number of repair PUTs issued by anti-entropy scrubber")
                .register(meterRegistry);
    }

    /**
     * Main scrub round. Triggered by the scheduler on a configurable interval.
     * <p>
     * Safe to call directly in tests — the {@code @Scheduled} annotation is
     * ignored when invoked directly.
     * </p>
     */
    @Observed(name = "anti-entropy.scrub", contextualName = "anti-entropy-scrub-round")
    @Scheduled(fixedDelayString = "${cache.anti-entropy.interval-ms:1800000}")
    public void scrub() {
        List<NodeInfo> allAlive = membershipList.getAliveNodes();

        if (allAlive == null || allAlive.isEmpty()) {
            log.debug("Anti-entropy: no alive nodes, skipping round");
            return;
        }

        NodeInfo self = membershipList.getSelf();
        if (self == null) {
            log.debug("Anti-entropy: self node not yet initialized, skipping round");
            return;
        }

        // Build peers list (all alive nodes except self) for early-exit guard only
        List<NodeInfo> peers = new ArrayList<>();
        for (NodeInfo node : allAlive) {
            if (!node.getCacheNodeId().equals(self.getCacheNodeId())) {
                peers.add(node);
            }
        }

        if (peers.isEmpty()) {
            log.debug("Anti-entropy: no peers, skipping round");
            return;
        }

        if (store.isEmpty()) {
            log.debug("Anti-entropy: local store is empty, skipping round");
            return;
        }

        if (migrationHashRing.isEmpty()) {
            log.debug("Anti-entropy: ring not yet populated, skipping round");
            return;
        }

        // Sample K random keys from the local store
        List<String> keys = new ArrayList<>(store.keySet());
        Collections.shuffle(keys);
        int limit = Math.min(keys.size(), sampleSize);
        List<String> sampledKeys = keys.subList(0, limit);

        log.debug("Anti-entropy round: sampling {} of {} keys, {} alive peers",
                sampledKeys.size(), store.size(), peers.size());

        for (String key : sampledKeys) {
            keysScannedCounter.increment();

            CacheItem localItem = store.get(key);
            if (localItem == null) {
                // Key was evicted between sampling and processing
                rateLimitWait();
                continue;
            }

            // Resolve the RF replica nodes for this key from the consistent hash ring
            List<NodeInfoHashAdapter> replicaAdapters = migrationHashRing.getNodes(key, replicationFactor);

            // If this node is not a replica for this key, skip it.
            // Orphan keys are handled by the key migration service.
            boolean iAmReplica = replicaAdapters.stream()
                    .anyMatch(a -> a.getNodeId().equals(self.getCacheNodeId()));
            if (!iAmReplica) {
                rateLimitWait();
                continue;
            }

            long localVersion = localItem.getVersion();

            // Check only co-replicas (RF - 1 nodes), not all alive peers
            for (NodeInfoHashAdapter replicaAdapter : replicaAdapters) {
                if (replicaAdapter.getNodeId().equals(self.getCacheNodeId())) {
                    continue; // skip self
                }

                // Resolve to live NodeInfo; null means node is dead or not yet in membership
                NodeInfo peer = membershipList.getNode(replicaAdapter.getNodeId());
                if (peer == null) {
                    log.debug("Anti-entropy: replica {} not in membership, skipping for key={}",
                            replicaAdapter.getNodeId(), key);
                    continue;
                }

                try {
                    long peerVersion = fetchPeerVersion(peer, key);
                    if (peerVersion < localVersion) {
                        repairPeer(peer, key, localItem);
                    } else if (peerVersion > localVersion) {
                        // NEW: Reverse repair — pull from peer when local is stale
                        repairLocal(peer, key);
                    }
                } catch (Exception ex) {
                    log.warn("Anti-entropy: error checking peer {} for key={}: {}",
                            peer.getCacheNodeId(), key, ex.getMessage());
                    // Continue with remaining peers — do not abort the round
                }
            }

            rateLimitWait();
        }

        log.debug("Anti-entropy round complete: scanned={}, repairs={}",
                (long) keysScannedCounter.count(), (long) repairsIssuedCounter.count());
    }

    /**
     * Fetches the {@code X-Quorum-Version} from a peer for the given key.
     *
     * @param peer the target peer
     * @param key  the cache key
     * @return the peer's stored version, or {@code -1} if the key is absent (404)
     */
    private long fetchPeerVersion(NodeInfo peer, String key) {
        String url = buildUrl(peer, key);

        try {
            ResponseEntity<Void> response = peerWebClient.get()
                    .uri(url)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(5));

            if (response == null || response.getHeaders().get("X-Quorum-Version") == null) {
                return -1L;
            }

            String versionHeader = response.getHeaders().getFirst("X-Quorum-Version");
            return versionHeader != null ? Long.parseLong(versionHeader) : -1L;

        } catch (WebClientResponseException.NotFound ex) {
            // 404 — peer does not have this key yet; treat as version 0 so repair is triggered
            return 0L;
        }
    }

    /**
     * Sends a repair PUT to the stale peer.
     *
     * @param peer      the stale peer
     * @param key       the cache key
     * @param localItem the authoritative local entry
     */
    private void repairPeer(NodeInfo peer, String key, CacheItem localItem) {
        String url = buildUrl(peer, key);
        log.debug("Anti-entropy repair: PUT key={} to peer={}", key, peer.getCacheNodeId());

        try {
            peerWebClient.put()
                    .uri(url)
                    .header("X-Quorum-Version", String.valueOf(localItem.getVersion()))
                    .header("X-Expires-At",     String.valueOf(localItem.getExpiryTime()))
                    .header("Content-Type",      localItem.getContentType())
                    .bodyValue(localItem.getData())
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(5));

            repairsIssuedCounter.increment();
            log.debug("Anti-entropy repair succeeded: key={} peer={}", key, peer.getCacheNodeId());

        } catch (Exception ex) {
            log.warn("Anti-entropy repair failed: key={} peer={}: {}",
                    key, peer.getCacheNodeId(), ex.getMessage());
        }
    }

    /**
     * Pulls fresh data from a peer when local node has stale version (reverse repair).
     *
     * @param peer the peer with fresher data
     * @param key  the cache key
     */
    private void repairLocal(NodeInfo peer, String key) {
        String url = buildUrl(peer, key);
        log.debug("Anti-entropy reverse repair: GET key={} from peer={}", key, peer.getCacheNodeId());

        try {
            ResponseEntity<byte[]> response = peerWebClient.get()
                    .uri(url)
                    .retrieve()
                    .toEntity(byte[].class)
                    .block(Duration.ofSeconds(5));

            if (response != null && response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String versionHeader = response.getHeaders().getFirst("X-Quorum-Version");
                String expiresHeader = response.getHeaders().getFirst("X-Expires-At");
                String contentType = response.getHeaders().getFirst("Content-Type");

                if (versionHeader == null || expiresHeader == null) {
                    log.warn("Reverse repair failed: missing headers for key={} peer={}", key, peer.getCacheNodeId());
                    return;
                }

                long peerVersion = Long.parseLong(versionHeader);
                long expiresAt = Long.parseLong(expiresHeader);

                cacheService.storeData(key, response.getBody(), expiresAt, contentType, peerVersion);
                log.debug("Reverse repair succeeded: pulled key={} from peer={} (v={})",
                        key, peer.getCacheNodeId(), peerVersion);
            }

        } catch (Exception ex) {
            log.warn("Reverse repair failed: key={} peer={}: {}",
                    key, peer.getCacheNodeId(), ex.getMessage());
        }
    }

    /**
     * Builds the internal cache endpoint URL for a given peer and key.
     */
    private String buildUrl(NodeInfo peer, String key) {
        return String.format("http://%s:%d/api/v1/internal/cache/%s",
                peer.getHost(), peer.getServicePort(), key);
    }

    /**
     * Throttle between key scans to avoid overwhelming peers.
     * Mirrors the busy-wait pattern in {@code MigrationWorker.rateLimitWait()}.
     */
    private void rateLimitWait() {
        if (rateLimitMs > 0) {
            try {
                Thread.sleep(rateLimitMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
