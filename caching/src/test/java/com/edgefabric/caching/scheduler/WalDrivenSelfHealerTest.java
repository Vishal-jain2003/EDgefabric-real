package com.edgefabric.caching.scheduler;

import com.edgefabric.caching.antiEntropy.S3WalReader;
import com.edgefabric.caching.antiEntropy.S3WalReader.WalPendingEntry;
import com.edgefabric.caching.antiEntropy.S3WalReader.WalScanResult;
import com.edgefabric.caching.config.WalReaderProperties;
import com.edgefabric.caching.exception.CacheNotFoundException;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.migration.NodeInfoHashAdapter;
import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import com.edgefabric.caching.service.InternalCacheService;
import com.edgefabric.hashing.core.ConsistentHashRing;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WalDrivenSelfHealer}.
 *
 * <p>Uses {@link MockWebServer} for the WebClient peer HTTP call so that the
 * real reactive pipeline is exercised without a running cache node. Dependencies
 * are constructed manually in {@link #setUp()} to avoid {@code @InjectMocks}
 * ordering issues with the large constructor.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WalDrivenSelfHealerTest {

    @Mock
    private S3WalReader s3WalReader;

    @Mock
    private InternalCacheService cacheService;

    @Mock
    private MembershipList membershipList;

    @SuppressWarnings("unchecked")
    @Mock
    private ConsistentHashRing<NodeInfoHashAdapter> migrationHashRing;

    private MockWebServer mockWebServer;
    private SimpleMeterRegistry meterRegistry;
    private WalDrivenSelfHealer healer;

    private static final String SELF_NODE_ID = "node-self";
    private static final int RF = 3;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        meterRegistry = new SimpleMeterRegistry();

        NodeInfo selfNode = new NodeInfo(SELF_NODE_ID, "127.0.0.1", 8082, 7946);
        when(membershipList.getSelf()).thenReturn(selfNode);

        WalReaderProperties walProps = new WalReaderProperties();
        walProps.setMaxEntriesPerCycle(500);
        walProps.setPeerTimeoutMs(5000);

        WebClient peerWebClient = WebClient.builder().build();

        healer = new WalDrivenSelfHealer(
                s3WalReader,
                cacheService,
                membershipList,
                migrationHashRing,
                peerWebClient,
                meterRegistry,
                walProps,
                RF
        );
        healer.initMetrics();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private WalPendingEntry pendingEntry(String key, long version, long lsn) {
        return new WalPendingEntry(key, version, lsn);
    }

    /**
     * Creates a NodeInfoHashAdapter whose host/port point at the MockWebServer,
     * with the given nodeId and ALIVE status.
     */
    private NodeInfoHashAdapter alivePeer(String nodeId) {
        NodeInfo peer = new NodeInfo(nodeId, mockWebServer.getHostName(),
                mockWebServer.getPort(), 7946);
        // NodeInfo starts ALIVE by default
        return new NodeInfoHashAdapter(peer);
    }

    private NodeInfoHashAdapter deadPeer(String nodeId) {
        NodeInfo peer = NodeInfo.getInstance(nodeId, "10.0.0.9", 8082, 7946,
                Status.DEAD, 0L, 0L);
        return new NodeInfoHashAdapter(peer);
    }

    private Counter counter(String name) {
        Counter c = meterRegistry.find(name).counter();
        assertThat(c).as("Counter %s must be registered", name).isNotNull();
        return c;
    }

    // ── Test Cases ─────────────────────────────────────────────────────────────

    @Test
    void healFromWal_doesNothingWhenNoPendingEntries() {
        // Arrange — maxScannedLsn=0 means no segments scanned
        when(s3WalReader.getPendingEntries(SELF_NODE_ID))
                .thenReturn(new WalScanResult(List.of(), 0L));

        // Act
        healer.healFromWal();

        // Assert — no peer call, no checkpoint written (maxScannedLsn==0)
        verify(migrationHashRing, never()).getNodes(anyString(), anyInt());
        verify(s3WalReader, never()).saveCheckpoint(anyString(), any(Long.class));
    }

    @Test
    void healFromWal_skipsEntryWhenLocalVersionIsCurrent() {
        // Arrange — local item has version 10 >= entry version 5
        WalPendingEntry entry = pendingEntry("cache:key1", 5L, 20L);
        when(s3WalReader.getPendingEntries(SELF_NODE_ID))
                .thenReturn(new WalScanResult(List.of(entry), 20L));

        CacheItem localItem = new CacheItem("data".getBytes(), System.currentTimeMillis() + 60_000L,
                "text/plain", 10L, true);
        when(cacheService.get("cache:key1")).thenReturn(localItem);

        // Act
        healer.healFromWal();

        // Assert — no peer HTTP call, skipped counter incremented, checkpoint saved with lsn=20
        verify(migrationHashRing, never()).getNodes(anyString(), anyInt());
        assertThat(counter("edgefabric.wal_healer.skipped").count()).isEqualTo(1.0);
        verify(s3WalReader).saveCheckpoint(SELF_NODE_ID, 20L);
    }

    @Test
    void healFromWal_skipsWhenNoAlivePeerFound() {
        // Arrange — hash ring returns only self (no other ALIVE node)
        WalPendingEntry entry = pendingEntry("cache:key1", 5L, 30L);
        when(s3WalReader.getPendingEntries(SELF_NODE_ID))
                .thenReturn(new WalScanResult(List.of(entry), 30L));
        when(cacheService.get("cache:key1")).thenThrow(new CacheNotFoundException("cache:key1"));

        NodeInfoHashAdapter selfAdapter = new NodeInfoHashAdapter(
                new NodeInfo(SELF_NODE_ID, "127.0.0.1", 8082, 7946));
        when(migrationHashRing.getNodes(eq("cache:key1"), eq(RF)))
                .thenReturn(List.of(selfAdapter));

        // Act
        healer.healFromWal();

        // Assert
        assertThat(counter("edgefabric.wal_healer.skipped").count()).isEqualTo(1.0);
        verify(s3WalReader).saveCheckpoint(SELF_NODE_ID, 30L);
    }

    @Test
    void healFromWal_repairsEntryFromPeer() {
        // Arrange
        WalPendingEntry entry = pendingEntry("cache:key1", 5L, 40L);
        when(s3WalReader.getPendingEntries(SELF_NODE_ID))
                .thenReturn(new WalScanResult(List.of(entry), 40L));
        when(cacheService.get("cache:key1")).thenThrow(new CacheNotFoundException("cache:key1"));

        NodeInfoHashAdapter peer = alivePeer("node-peer");
        when(migrationHashRing.getNodes(eq("cache:key1"), eq(RF)))
                .thenReturn(List.of(peer));

        long futureExpiry = System.currentTimeMillis() + 60_000L;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("X-Quorum-Version", "5")
                .addHeader("X-Expires-At", String.valueOf(futureExpiry))
                .addHeader("Content-Type", "application/json")
                .setBody("{\"v\":1}"));

        // Act
        healer.healFromWal();

        // Assert
        verify(cacheService).storeData(
                eq("cache:key1"),
                any(byte[].class),
                eq(futureExpiry),
                eq("application/json"),
                eq(5L));
        assertThat(counter("edgefabric.wal_healer.repaired").count()).isEqualTo(1.0);
        verify(s3WalReader).saveCheckpoint(SELF_NODE_ID, 40L);
    }

    @Test
    void healFromWal_skipsWhenPeerVersionIsLowerThanWalVersion() {
        // Arrange — WAL entry version=10, peer returns X-Quorum-Version=3 (stale peer)
        WalPendingEntry entry = pendingEntry("cache:key2", 10L, 50L);
        when(s3WalReader.getPendingEntries(SELF_NODE_ID))
                .thenReturn(new WalScanResult(List.of(entry), 50L));
        when(cacheService.get("cache:key2")).thenThrow(new CacheNotFoundException("cache:key2"));

        NodeInfoHashAdapter peer = alivePeer("node-peer");
        when(migrationHashRing.getNodes(eq("cache:key2"), eq(RF)))
                .thenReturn(List.of(peer));

        long futureExpiry = System.currentTimeMillis() + 60_000L;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("X-Quorum-Version", "3")   // < entry.version()=10
                .addHeader("X-Expires-At", String.valueOf(futureExpiry))
                .addHeader("Content-Type", "application/json")
                .setBody("{}"));

        // Act
        healer.healFromWal();

        // Assert — storeData must NOT be called; entry is skipped
        verify(cacheService, never()).storeData(anyString(), any(byte[].class),
                any(Long.class), anyString(), any(Long.class));
        assertThat(counter("edgefabric.wal_healer.skipped").count()).isEqualTo(1.0);
        verify(s3WalReader).saveCheckpoint(SELF_NODE_ID, 50L);
    }

    @Test
    void healFromWal_advancesLsnEvenOnException() {
        // Arrange — peer is alive but MockWebServer returns 500 → RuntimeException path
        WalPendingEntry entry = pendingEntry("cache:key3", 7L, 60L);
        when(s3WalReader.getPendingEntries(SELF_NODE_ID))
                .thenReturn(new WalScanResult(List.of(entry), 60L));
        when(cacheService.get("cache:key3")).thenThrow(new CacheNotFoundException("cache:key3"));

        NodeInfoHashAdapter peer = alivePeer("node-peer");
        when(migrationHashRing.getNodes(eq("cache:key3"), eq(RF)))
                .thenReturn(List.of(peer));

        // 500 response — healEntry will throw RuntimeException("Peer returned non-2xx …")
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));

        // Act
        healer.healFromWal();

        // Assert — failed counter incremented AND checkpoint saved (LSN advanced)
        assertThat(counter("edgefabric.wal_healer.failed").count()).isEqualTo(1.0);
        verify(s3WalReader).saveCheckpoint(SELF_NODE_ID, 60L);
        verify(cacheService, never()).storeData(anyString(), any(byte[].class),
                any(Long.class), anyString(), any(Long.class));
    }

    // ── Checkpoint-on-empty tests (EPMICMPHE-246) ─────────────────────────────

    /**
     * AC: saveCheckpoint IS called with maxScannedLsn when the pending list is empty
     * but segments were scanned (maxScannedLsn > 0).
     *
     * This is the core fix: the old code early-returned on pending.isEmpty() and
     * never persisted the checkpoint, causing repeated re-scans of already-seen LSNs.
     */
    @Test
    void healFromWal_saveCheckpointCalledWithMaxScannedLsn_whenPendingIsEmptyButSegmentsScanned() {
        // Arrange — no pending entries for this node, but segments exist with lsn=75
        when(s3WalReader.getPendingEntries(SELF_NODE_ID))
                .thenReturn(new WalScanResult(List.of(), 75L));

        // Act
        healer.healFromWal();

        // Assert — no repair attempted but checkpoint must still advance
        verify(migrationHashRing, never()).getNodes(anyString(), anyInt());
        verify(s3WalReader).saveCheckpoint(SELF_NODE_ID, 75L);
    }

    /**
     * AC: saveCheckpoint IS called even when all pending entries were skipped
     * (local version already current) — the scanned LSN must still advance.
     */
    @Test
    void healFromWal_saveCheckpointCalledEvenWhenAllEntriesSkipped() {
        // Arrange — one entry, but local version is already current (skip path)
        WalPendingEntry entry = pendingEntry("cache:key-skip", 3L, 88L);
        when(s3WalReader.getPendingEntries(SELF_NODE_ID))
                .thenReturn(new WalScanResult(List.of(entry), 88L));

        CacheItem localItem = new CacheItem(
                "data".getBytes(), System.currentTimeMillis() + 60_000L,
                "text/plain", 10L, true);   // version 10 >= entry version 3
        when(cacheService.get("cache:key-skip")).thenReturn(localItem);

        // Act
        healer.healFromWal();

        // Assert — entry was skipped, but checkpoint must be saved
        assertThat(counter("edgefabric.wal_healer.skipped").count()).isEqualTo(1.0);
        verify(s3WalReader).saveCheckpoint(SELF_NODE_ID, 88L);
    }

    /**
     * AC: saveCheckpoint is NOT called when maxScannedLsn == 0 (no segments in S3).
     * Persisting lsn=0 is meaningless and would overwrite a legitimate checkpoint.
     */
    @Test
    void healFromWal_saveCheckpointNotCalled_whenMaxScannedLsnIsZero() {
        // Arrange — no segments, so maxScannedLsn=0
        when(s3WalReader.getPendingEntries(SELF_NODE_ID))
                .thenReturn(new WalScanResult(List.of(), 0L));

        // Act
        healer.healFromWal();

        // Assert
        verify(s3WalReader, never()).saveCheckpoint(anyString(), any(Long.class));
    }
}
