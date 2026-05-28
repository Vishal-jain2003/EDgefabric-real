package com.edgefabric.caching.scheduler;

import com.edgefabric.caching.antiEntropy.StaleEntry;
import com.edgefabric.caching.antiEntropy.StaleKeyRegistry;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.migration.NodeInfoHashAdapter;
import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.service.InternalCacheService;
import com.edgefabric.hashing.core.ConsistentHashRing;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SelfHealingScheduler}.
 *
 * <p>Tests self-healing logic: draining stale keys, fetching from peers, storing locally, and metrics.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SelfHealingSchedulerTest {

    @Mock
    private StaleKeyRegistry staleKeyRegistry;

    @Mock
    private InternalCacheService cacheService;

    @Mock
    private MembershipList membershipList;

    @SuppressWarnings("unchecked")
    @Mock
    private ConsistentHashRing<NodeInfoHashAdapter> migrationHashRing;

    @Mock
    private WebClient peerWebClient;

    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private MeterRegistry meterRegistry;
    private SelfHealingScheduler scheduler;

    private static final String SELF_NODE_ID = "node-self";
    private static final int RF = 3;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();

        NodeInfo self = new NodeInfo(SELF_NODE_ID, "127.0.0.1", 8082, 7946);

        when(membershipList.getSelf()).thenReturn(self);

        scheduler = new SelfHealingScheduler(
                staleKeyRegistry,
                cacheService,
                membershipList,
                migrationHashRing,
                peerWebClient,
                meterRegistry,
                RF,
                100,  // batchSize
                3,    // retryMax
                5000  // timeoutMs
        );

        // Initialize metrics before running tests
        scheduler.initMetrics();
    }

    // ── Happy Path ────────────────────────────────────────────────────────────

    @Test
    void healStaleness_noStaleKeys_shouldDoNothing() {
        // given
        when(staleKeyRegistry.drainStaleKeys(100)).thenReturn(Collections.emptyList());

        // when
        scheduler.healStaleness();

        // then
        verify(staleKeyRegistry, times(1)).drainStaleKeys(100);
        verify(peerWebClient, never()).get();
    }

    @Test
    void healStaleness_oneStaleKey_shouldFetchFromPeerAndStoreLocally() {
        // given
        String key = "user:123:profile";
        long version = 100L;
        StaleEntry staleEntry = new StaleEntry(key, version, "local_write_failed");

        NodeInfo peer1 = new NodeInfo("node-peer1", "192.168.1.10", 8082, 7946);

        NodeInfo peer2 = new NodeInfo("node-peer2", "192.168.1.11", 8082, 7946);

        List<NodeInfoHashAdapter> replicas = List.of(
                new NodeInfoHashAdapter(new NodeInfo(SELF_NODE_ID, "127.0.0.1", 8082, 7946)),
                new NodeInfoHashAdapter(peer1),
                new NodeInfoHashAdapter(peer2)
        );

        byte[] freshData = "fresh-data".getBytes();
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Quorum-Version", "200");
        headers.add("X-Expires-At", String.valueOf(System.currentTimeMillis() + 60000));
        headers.add("Content-Type", "application/json");

        ResponseEntity<byte[]> response = new ResponseEntity<>(freshData, headers, HttpStatus.OK);

        when(staleKeyRegistry.drainStaleKeys(100)).thenReturn(List.of(staleEntry));
        when(migrationHashRing.getNodes(anyString(), eq(RF))).thenReturn(replicas);

        when(peerWebClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(byte[].class)).thenReturn(Mono.just(response));

        // when
        scheduler.healStaleness();

        // then
        verify(staleKeyRegistry, times(1)).drainStaleKeys(100);
        verify(cacheService, times(1)).storeData(eq(key), eq(freshData), anyLong(), eq("application/json"), eq(200L));

        Counter successCounter = meterRegistry.find("edgefabric.self_healing.successes").counter();
        assertThat(successCounter).isNotNull();
        assertThat(successCounter.count()).isEqualTo(1.0);
    }

    // ── Error Handling ────────────────────────────────────────────────────────

    @Test
    void healStaleness_peerFetchFails_shouldRemarkAsStale() {
        // given
        String key = "user:123:profile";
        long version = 100L;
        StaleEntry staleEntry = new StaleEntry(key, version, "local_write_failed");

        NodeInfo peer1 = new NodeInfo("node-peer1", "192.168.1.10", 8082, 7946);

        List<NodeInfoHashAdapter> replicas = List.of(
                new NodeInfoHashAdapter(new NodeInfo(SELF_NODE_ID, "127.0.0.1", 8082, 7946)),
                new NodeInfoHashAdapter(peer1)
        );

        when(staleKeyRegistry.drainStaleKeys(100)).thenReturn(List.of(staleEntry));
        when(migrationHashRing.getNodes(anyString(), eq(RF))).thenReturn(replicas);

        when(peerWebClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(byte[].class)).thenReturn(Mono.error(new RuntimeException("Connection timeout")));

        // when
        scheduler.healStaleness();

        // then
        verify(staleKeyRegistry, times(1)).markStale(eq(key), eq(version), contains("retry"));

        Counter failureCounter = meterRegistry.find("edgefabric.self_healing.failures").counter();
        assertThat(failureCounter).isNotNull();
        assertThat(failureCounter.count()).isEqualTo(1.0);
    }

    @Test
    void healStaleness_noPeersAvailable_shouldRemarkAsStale() {
        // given
        String key = "user:123:profile";
        long version = 100L;
        StaleEntry staleEntry = new StaleEntry(key, version, "local_write_failed");

        List<NodeInfoHashAdapter> replicas = List.of(
                new NodeInfoHashAdapter(new NodeInfo(SELF_NODE_ID, "127.0.0.1", 8082, 7946))
        );

        when(staleKeyRegistry.drainStaleKeys(100)).thenReturn(List.of(staleEntry));
        when(migrationHashRing.getNodes(anyString(), eq(RF))).thenReturn(replicas);

        // when
        scheduler.healStaleness();

        // then
        verify(staleKeyRegistry, times(1)).markStale(eq(key), eq(version), eq("no_peers_available"));
        verify(peerWebClient, never()).get();

        Counter failureCounter = meterRegistry.find("edgefabric.self_healing.failures").counter();
        assertThat(failureCounter).isNotNull();
        assertThat(failureCounter.count()).isEqualTo(1.0);
    }

    // ── Batch Processing ──────────────────────────────────────────────────────

    @Test
    void healStaleness_multipleStaleKeys_shouldProcessBatch() {
        // given
        StaleEntry entry1 = new StaleEntry("key1", 100L, "reason1");
        StaleEntry entry2 = new StaleEntry("key2", 200L, "reason2");
        StaleEntry entry3 = new StaleEntry("key3", 300L, "reason3");

        NodeInfo peer = new NodeInfo("node-peer", "192.168.1.10", 8082, 7946);

        List<NodeInfoHashAdapter> replicas = List.of(
                new NodeInfoHashAdapter(new NodeInfo(SELF_NODE_ID, "127.0.0.1", 8082, 7946)),
                new NodeInfoHashAdapter(peer)
        );

        byte[] data = "data".getBytes();
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Quorum-Version", "999");
        headers.add("X-Expires-At", String.valueOf(System.currentTimeMillis() + 60000));
        headers.add("Content-Type", "text/plain");

        ResponseEntity<byte[]> response = new ResponseEntity<>(data, headers, HttpStatus.OK);

        when(staleKeyRegistry.drainStaleKeys(100)).thenReturn(List.of(entry1, entry2, entry3));
        when(migrationHashRing.getNodes(anyString(), eq(RF))).thenReturn(replicas);

        when(peerWebClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(byte[].class)).thenReturn(Mono.just(response));

        // when
        scheduler.healStaleness();

        // then
        verify(cacheService, times(3)).storeData(anyString(), eq(data), anyLong(), eq("text/plain"), eq(999L));

        Counter successCounter = meterRegistry.find("edgefabric.self_healing.successes").counter();
        assertThat(successCounter).isNotNull();
        assertThat(successCounter.count()).isEqualTo(3.0);
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    @Test
    void initMetrics_shouldRegisterAllCountersAndTimers() {
        // when
        scheduler.initMetrics();

        // then
        assertThat(meterRegistry.find("edgefabric.self_healing.attempts").counter()).isNotNull();
        assertThat(meterRegistry.find("edgefabric.self_healing.successes").counter()).isNotNull();
        assertThat(meterRegistry.find("edgefabric.self_healing.failures").counter()).isNotNull();
        assertThat(meterRegistry.find("edgefabric.self_healing.latency").timer()).isNotNull();
    }
}
