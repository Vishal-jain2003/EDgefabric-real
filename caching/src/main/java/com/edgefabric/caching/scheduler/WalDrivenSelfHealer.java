package com.edgefabric.caching.scheduler;

import com.edgefabric.caching.antiEntropy.S3WalReader;
import com.edgefabric.caching.antiEntropy.S3WalReader.WalPendingEntry;
import com.edgefabric.caching.antiEntropy.S3WalReader.WalScanResult;
import com.edgefabric.caching.config.WalReaderProperties;
import com.edgefabric.caching.exception.CacheExpiredException;
import com.edgefabric.caching.exception.CacheNotFoundException;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.migration.NodeInfoHashAdapter;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import com.edgefabric.caching.service.InternalCacheService;
import com.edgefabric.hashing.core.ConsistentHashRing;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@ConditionalOnProperty(name = "cache.wal.enabled", havingValue = "true", matchIfMissing = false)
public class WalDrivenSelfHealer {

    private final S3WalReader s3WalReader;
    private final InternalCacheService cacheService;
    private final MembershipList membershipList;
    private final ConsistentHashRing<NodeInfoHashAdapter> migrationHashRing;
    private final WebClient peerWebClient;
    private final MeterRegistry meterRegistry;
    private final WalReaderProperties walReaderProperties;

    private final int replicationFactor;

    private Counter entriesScanned;
    private Counter repaired;
    private Counter failed;
    private Counter skipped;

    public WalDrivenSelfHealer(
            S3WalReader s3WalReader,
            InternalCacheService cacheService,
            MembershipList membershipList,
            ConsistentHashRing<NodeInfoHashAdapter> migrationHashRing,
            @Qualifier("peerWebClient") WebClient peerWebClient,
            MeterRegistry meterRegistry,
            WalReaderProperties walReaderProperties,
            @Value("${cache.replication.factor:3}") int replicationFactor
    ) {
        this.s3WalReader = s3WalReader;
        this.cacheService = cacheService;
        this.membershipList = membershipList;
        this.migrationHashRing = migrationHashRing;
        this.peerWebClient = peerWebClient;
        this.meterRegistry = meterRegistry;
        this.walReaderProperties = walReaderProperties;
        this.replicationFactor = replicationFactor;
    }

    @PostConstruct
    public void initMetrics() {
        entriesScanned = Counter.builder("edgefabric.wal_healer.entries_scanned")
                .description("Total WAL entries scanned by the cache-side self-healer")
                .register(meterRegistry);

        repaired = Counter.builder("edgefabric.wal_healer.repaired")
                .description("Keys successfully repaired from a healthy peer")
                .register(meterRegistry);

        failed = Counter.builder("edgefabric.wal_healer.failed")
                .description("Repair attempts that failed")
                .register(meterRegistry);

        skipped = Counter.builder("edgefabric.wal_healer.skipped")
                .description("Entries skipped because the local copy is already up-to-date or no peer is available")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${cache.wal.interval-ms:60000}")
    public void healFromWal() {
        String myNodeId = membershipList.getSelf().getCacheNodeId();
        log.info("WAL-driven self-heal round started nodeId={}", myNodeId);

        WalScanResult scanResult;
        try {
            scanResult = s3WalReader.getPendingEntries(myNodeId);
        } catch (Exception e) {
            log.warn("WAL-driven self-healer: failed to fetch pending entries nodeId={}: {}", myNodeId, e.getMessage());
            return;
        }

        List<WalPendingEntry> pending = scanResult.pending();
        long maxScannedLsn = scanResult.maxScannedLsn();

        int limit = walReaderProperties.getMaxEntriesPerCycle();
        long maxProcessedLsn = -1L;
        int repairedCount = 0;
        int failedCount = 0;
        int skippedCount = 0;

        for (int i = 0; i < Math.min(pending.size(), limit); i++) {
            WalPendingEntry entry = pending.get(i);
            entriesScanned.increment();

            try {
                boolean didRepair = healEntry(myNodeId, entry);
                if (didRepair) {
                    repaired.increment();
                    repairedCount++;
                } else {
                    skipped.increment();
                    skippedCount++;
                }
                if (entry.lsn() > maxProcessedLsn) {
                    maxProcessedLsn = entry.lsn();
                }
            } catch (Exception e) {
                log.warn("WAL-driven self-healer: repair failed key={} lsn={}: {}", entry.key(), entry.lsn(), e.getMessage());
                failed.increment();
                failedCount++;
                // Still advance LSN so we don't retry a permanently broken entry indefinitely
                if (entry.lsn() > maxProcessedLsn) {
                    maxProcessedLsn = entry.lsn();
                }
            }
        }

        // Persist checkpoint using the higher of: the last entry we processed, or the highest
        // LSN seen in any segment — even when pending is empty. This prevents re-scanning
        // already-seen segments on the next cycle (AC2/AC3 fix for EPMICMPHE-246).
        long checkpointLsn = Math.max(maxProcessedLsn, maxScannedLsn);
        if (checkpointLsn > 0) {
            s3WalReader.saveCheckpoint(myNodeId, checkpointLsn);
        }

        log.info("WAL-driven self-heal round complete nodeId={} scanned={} repaired={} failed={} skipped={} checkpointLsn={}",
                myNodeId, Math.min(pending.size(), limit), repairedCount, failedCount, skippedCount, checkpointLsn);
    }

    private boolean healEntry(String myNodeId, WalPendingEntry entry) {
        // Check if already have a sufficiently new version locally
        try {
            var localItem = cacheService.get(entry.key());
            if (localItem.getVersion() >= entry.version()) {
                return false;
            }
        } catch (CacheNotFoundException | CacheExpiredException ignored) {
            // Not present or expired — proceed to fetch from peer
        }

        List<NodeInfoHashAdapter> replicas = migrationHashRing.getNodes(entry.key(), replicationFactor);

        Optional<NodeInfoHashAdapter> peerOpt = replicas.stream()
                .filter(adapter -> adapter.getNodeInfo().getStatus() == Status.ALIVE)
                .filter(adapter -> !adapter.getNodeInfo().getCacheNodeId().equals(myNodeId))
                .findFirst();

        if (peerOpt.isEmpty()) {
            log.warn("WAL-driven self-healer: no ALIVE peer found for key={}", entry.key());
            return false;
        }

        NodeInfo peer = peerOpt.get().getNodeInfo();
        String url = String.format("http://%s:%d/api/v1/internal/cache/%s",
                peer.getHost(), peer.getServicePort(), entry.key());

        ResponseEntity<byte[]> response = peerWebClient.get()
                .uri(url)
                .retrieve()
                .toEntity(byte[].class)
                .block(Duration.ofMillis(walReaderProperties.getPeerTimeoutMs()));

        if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Peer returned non-2xx or empty body for key=" + entry.key()
                    + " peer=" + peer.getCacheNodeId());
        }

        String versionHeader = response.getHeaders().getFirst("X-Quorum-Version");
        String expiresAtHeader = response.getHeaders().getFirst("X-Expires-At");

        if (versionHeader == null || expiresAtHeader == null) {
            throw new RuntimeException("Peer response missing X-Quorum-Version or X-Expires-At headers for key=" + entry.key());
        }

        long peerVersion = Long.parseLong(versionHeader);
        if (peerVersion < entry.version()) {
            // Peer is also stale — skip to avoid writing a regressed version
            return false;
        }

        long expiresAt = Long.parseLong(expiresAtHeader);
        String contentType = response.getHeaders().getFirst("Content-Type");
        if (contentType == null) {
            contentType = "application/octet-stream";
        }
        byte[] data = response.getBody();

        cacheService.storeData(entry.key(), data, expiresAt, contentType, peerVersion);

        log.info("WAL-driven self-healer: repaired key={} from peer={} version={}",
                entry.key(), peer.getCacheNodeId(), peerVersion);
        return true;
    }
}
