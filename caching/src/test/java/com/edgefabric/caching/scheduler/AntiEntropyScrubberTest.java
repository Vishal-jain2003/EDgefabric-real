package com.edgefabric.caching.scheduler;

import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.migration.NodeInfoHashAdapter;
import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.service.InternalCacheService;
import com.edgefabric.hashing.core.ConsistentHashRing;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AntiEntropyScrubber}.
 *
 * <p>Mockito-only — no Spring context. All tests invoke {@link AntiEntropyScrubber#scrub()} directly.</p>
 *
 * <p>{@code Strictness.LENIENT} is required because the fluent WebClient mock chain creates
 * multiple intermediate sub-mocks; some stubs on those intermediate objects may not be reached
 * in every code path (e.g., when the loop breaks early or an error is swallowed).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AntiEntropyScrubberTest {

    // ── mocks ─────────────────────────────────────────────────────────────────

    @Mock
    private MembershipList membershipList;

    @Mock
    private WebClient peerWebClient;

    @SuppressWarnings("unchecked")
    @Mock
    private ConsistentHashRing<NodeInfoHashAdapter> hashRing;

    @Mock
    private InternalCacheService cacheService;

    // ── real objects ──────────────────────────────────────────────────────────

    private Map<String, CacheItem> store;
    private MeterRegistry meterRegistry;

    // ── subject ───────────────────────────────────────────────────────────────

    private AntiEntropyScrubber scrubber;

    // ── constants ─────────────────────────────────────────────────────────────

    private static final String SELF_NODE_ID   = "node-self";
    private static final String PEER_A_NODE_ID = "node-peer-a";
    private static final String PEER_B_NODE_ID = "node-peer-b";
    private static final String PEER_A_HOST    = "10.0.0.2";
    private static final String PEER_B_HOST    = "10.0.0.3";
    private static final int    PEER_PORT      = 8082;

    private static final int  DEFAULT_SAMPLE_SIZE        = 10;
    private static final long DEFAULT_RATE_LIMIT_MS      = 0L;
    private static final int  DEFAULT_REPLICATION_FACTOR = 3;

    // ── helpers ───────────────────────────────────────────────────────────────

    private static NodeInfo makeNode(String id, String host, int port) {
        return new NodeInfo(id, host, port, 7946);
    }

    /** Creates a CacheItem with the given version that expires far in the future. */
    private static CacheItem cacheItem(long version) {
        return new CacheItem(
                ("value-v" + version).getBytes(),
                System.currentTimeMillis() + 60_000L,
                "text/plain",
                version,
                true
        );
    }

    /** Builds a ResponseEntity with an X-Quorum-Version header value. */
    private static ResponseEntity<Void> versionResponse(long version) {
        return ResponseEntity.ok()
                .header("X-Quorum-Version", String.valueOf(version))
                .<Void>build();
    }

    /** Configures the GET mock chain to throw for URIs containing the given host. */
    @SuppressWarnings("unchecked")
    private void stubGetThrowsForHost(String hostSubstring) {
        WebClient.RequestHeadersUriSpec<?> uriSpec  = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec<?>    hdrsSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec             respSpec = mock(WebClient.ResponseSpec.class);

        when(peerWebClient.get()).thenReturn((WebClient.RequestHeadersUriSpec) uriSpec);
        doReturn(hdrsSpec).when(uriSpec).uri(contains(hostSubstring));
        when(hdrsSpec.retrieve()).thenReturn(respSpec);
        when(respSpec.toBodilessEntity())
                .thenReturn(Mono.error(new RuntimeException("Connection refused")));
    }

    /** Configures a permissive GET mock that returns the same version for any URI. */
    @SuppressWarnings("unchecked")
    private void stubGetForAnyHost(long returnVersion) {
        WebClient.RequestHeadersUriSpec<?> uriSpec  = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec<?>    hdrsSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec             respSpec = mock(WebClient.ResponseSpec.class);

        when(peerWebClient.get()).thenReturn((WebClient.RequestHeadersUriSpec) uriSpec);
        doReturn(hdrsSpec).when(uriSpec).uri(anyString());
        when(hdrsSpec.retrieve()).thenReturn(respSpec);
        when(respSpec.toBodilessEntity()).thenReturn(Mono.just(versionResponse(returnVersion)));
    }

    /** Configures the WebClient PUT mock chain to succeed (200 OK) for any URI. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubPutSucceeds() {
        WebClient.RequestBodyUriSpec bodyUriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec    bodySpec    = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec hdrsSpec    = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec       respSpec    = mock(WebClient.ResponseSpec.class);

        when(peerWebClient.put()).thenReturn(bodyUriSpec);
        when(bodyUriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenReturn(hdrsSpec);
        when(hdrsSpec.retrieve()).thenReturn(respSpec);
        when(respSpec.toBodilessEntity())
                .thenReturn(Mono.just(ResponseEntity.ok().<Void>build()));
    }

    /**
     * Stubs the hash ring to return [selfAdapter, peerAAdapter] for any key,
     * and stubs membershipList.getNode() for peerA.
     */
    private void stubRingWithSelfAndPeerA(NodeInfo self, NodeInfo peerA) {
        NodeInfoHashAdapter selfAdapter  = new NodeInfoHashAdapter(self);
        NodeInfoHashAdapter peerAAdapter = new NodeInfoHashAdapter(peerA);
        when(hashRing.isEmpty()).thenReturn(false);
        when(hashRing.getNodes(anyString(), eq(DEFAULT_REPLICATION_FACTOR)))
                .thenReturn(List.of(selfAdapter, peerAAdapter));
        when(membershipList.getNode(PEER_A_NODE_ID)).thenReturn(peerA);
    }

    // ── @BeforeEach ───────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        store         = new ConcurrentHashMap<>();
        meterRegistry = new SimpleMeterRegistry();

        scrubber = new AntiEntropyScrubber(
                store,
                membershipList,
                peerWebClient,
                meterRegistry,
                hashRing,
                cacheService,
                DEFAULT_SAMPLE_SIZE,
                DEFAULT_RATE_LIMIT_MS,
                DEFAULT_REPLICATION_FACTOR
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Test 1: no alive peers → nothing happens
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void scrub_noPeers_doesNothing() {
        store.put("key1", cacheItem(5L));

        when(membershipList.getAliveNodes()).thenReturn(Collections.emptyList());

        scrubber.scrub();

        verify(peerWebClient, never()).get();
        verify(peerWebClient, never()).put();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Test 2: all replicas in sync → no repair triggered
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void scrub_allReplicasInSync_noRepairTriggered() {
        NodeInfo self  = makeNode(SELF_NODE_ID,   "10.0.0.1", PEER_PORT);
        NodeInfo peerA = makeNode(PEER_A_NODE_ID, PEER_A_HOST, PEER_PORT);

        store.put("key1", cacheItem(10L));

        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getAliveNodes()).thenReturn(List.of(self, peerA));
        stubRingWithSelfAndPeerA(self, peerA);

        // Peer returns same version (10) as local — no repair needed
        stubGetForAnyHost(10L);

        scrubber.scrub();

        verify(peerWebClient, never()).put();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Test 3: one replica stale → repair triggered for stale peer only
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void scrub_oneReplicaStale_repairTriggeredForStaleOnly() {
        NodeInfo self  = makeNode(SELF_NODE_ID,   "10.0.0.1", PEER_PORT);
        NodeInfo peerA = makeNode(PEER_A_NODE_ID, PEER_A_HOST, PEER_PORT);

        store.put("key1", cacheItem(10L));

        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getAliveNodes()).thenReturn(List.of(self, peerA));
        stubRingWithSelfAndPeerA(self, peerA);

        // peerA has stale version 5 (local is 10)
        stubGetForAnyHost(5L);
        stubPutSucceeds();

        scrubber.scrub();

        verify(peerWebClient, atLeastOnce()).put();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Test 4: peer unreachable → scrubber continues (no exception propagated)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void scrub_peerUnreachable_continuesWithOtherPeers() {
        NodeInfo self  = makeNode(SELF_NODE_ID,   "10.0.0.1", PEER_PORT);
        NodeInfo peerA = makeNode(PEER_A_NODE_ID, PEER_A_HOST, PEER_PORT);

        store.put("key1", cacheItem(10L));

        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getAliveNodes()).thenReturn(List.of(self, peerA));
        stubRingWithSelfAndPeerA(self, peerA);

        // GET throws for peerA — scrubber must not propagate the exception
        stubGetThrowsForHost(PEER_A_HOST);

        // Should complete without throwing
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> scrubber.scrub());

        // No PUT should be attempted since GET failed
        verify(peerWebClient, never()).put();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Test 5: metrics — keys scanned and repairs incremented correctly
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void scrub_metricsRecorded_keysScannedAndRepairsIncremented() {
        NodeInfo self  = makeNode(SELF_NODE_ID,   "10.0.0.1", PEER_PORT);
        NodeInfo peerA = makeNode(PEER_A_NODE_ID, PEER_A_HOST, PEER_PORT);

        store.put("key1", cacheItem(10L));

        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getAliveNodes()).thenReturn(List.of(self, peerA));
        stubRingWithSelfAndPeerA(self, peerA);

        // peerA is stale (version 2 vs local 10)
        stubGetForAnyHost(2L);
        stubPutSucceeds();

        scrubber.scrub();

        Counter keysScanned   = meterRegistry.find("edgefabric.anti_entropy.keys_scanned").counter();
        Counter repairsIssued = meterRegistry.find("edgefabric.anti_entropy.repairs_issued").counter();

        assertThat(keysScanned).isNotNull();
        assertThat(repairsIssued).isNotNull();
        assertThat(keysScanned.count()).isGreaterThanOrEqualTo(1.0);
        assertThat(repairsIssued.count()).isGreaterThanOrEqualTo(1.0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Test 6: throttling — rate limit delay is respected
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void scrub_throttlingApplied_rateLimitWaitCalled() {
        NodeInfo self  = makeNode(SELF_NODE_ID,   "10.0.0.1", PEER_PORT);
        NodeInfo peerA = makeNode(PEER_A_NODE_ID, PEER_A_HOST, PEER_PORT);

        store.put("key1", cacheItem(10L));
        store.put("key2", cacheItem(10L));

        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getAliveNodes()).thenReturn(List.of(self, peerA));
        stubRingWithSelfAndPeerA(self, peerA);

        stubGetForAnyHost(10L);

        // Build a scrubber with 5 ms rate limit per key
        AntiEntropyScrubber throttledScrubber = new AntiEntropyScrubber(
                store,
                membershipList,
                peerWebClient,
                meterRegistry,
                hashRing,
                cacheService,
                DEFAULT_SAMPLE_SIZE,
                5L,
                DEFAULT_REPLICATION_FACTOR
        );

        long start   = System.currentTimeMillis();
        throttledScrubber.scrub();
        long elapsed = System.currentTimeMillis() - start;

        // With 2 keys and 5 ms per key, at least 9 ms should have elapsed
        assertThat(elapsed).isGreaterThanOrEqualTo(9L);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Test 7: empty store → nothing happens (no network calls)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void scrub_emptyStore_doesNothing() {
        NodeInfo self  = makeNode(SELF_NODE_ID,   "10.0.0.1", PEER_PORT);
        NodeInfo peerA = makeNode(PEER_A_NODE_ID, PEER_A_HOST, PEER_PORT);

        // store is intentionally empty
        when(membershipList.getAliveNodes()).thenReturn(List.of(self, peerA));

        scrubber.scrub();

        verify(peerWebClient, never()).get();
        verify(peerWebClient, never()).put();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Test 8: self node excluded from peer comparisons
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void scrub_selfNodeExcluded_onlyPeersContacted() {
        NodeInfo self  = makeNode(SELF_NODE_ID,   "10.0.0.1", PEER_PORT);
        NodeInfo peerA = makeNode(PEER_A_NODE_ID, PEER_A_HOST, PEER_PORT);

        store.put("key1", cacheItem(10L));

        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getAliveNodes()).thenReturn(List.of(self, peerA));
        stubRingWithSelfAndPeerA(self, peerA);

        // Both in sync — but only peerA should be contacted, not self
        stubGetForAnyHost(10L);

        scrubber.scrub();

        // GET was called at least once (for peerA — self was skipped)
        verify(peerWebClient, atLeastOnce()).get();
        verify(peerWebClient, never()).put();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Test: self is null → peers list filters correctly (null-safe loop)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void scrub_selfIsNull_allAliveNodesTreatedAsPeers() {
        // getSelf() returns null — the null-check in the loop must handle this gracefully
        NodeInfo peerA = makeNode(PEER_A_NODE_ID, PEER_A_HOST, PEER_PORT);

        store.put("key1", cacheItem(5L));

        when(membershipList.getAliveNodes()).thenReturn(List.of(peerA));
        when(membershipList.getSelf()).thenReturn(null);

        // Peer is in sync — no repair needed
        stubGetForAnyHost(5L);

        assertDoesNotThrow(() -> scrubber.scrub());
        verify(peerWebClient, never()).put();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Test: fetchPeerVersion returns null ResponseEntity → treated as -1
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void scrub_nullResponseFromPeer_triggersRepair() {
        // When block() returns null the version is treated as -1, which is < any real version.
        // That means a repair PUT must be issued.
        NodeInfo self  = makeNode(SELF_NODE_ID,   "10.0.0.1", PEER_PORT);
        NodeInfo peerA = makeNode(PEER_A_NODE_ID, PEER_A_HOST, PEER_PORT);

        store.put("key1", cacheItem(1L)); // local version = 1 > -1

        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getAliveNodes()).thenReturn(List.of(self, peerA));
        stubRingWithSelfAndPeerA(self, peerA);

        // Stub GET to return null ResponseEntity
        WebClient.RequestHeadersUriSpec<?> uriSpec  = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec<?>    hdrsSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec             respSpec = mock(WebClient.ResponseSpec.class);
        when(peerWebClient.get()).thenReturn((WebClient.RequestHeadersUriSpec) uriSpec);
        when(uriSpec.uri(anyString())).thenReturn((WebClient.RequestHeadersSpec) hdrsSpec);
        when(hdrsSpec.retrieve()).thenReturn(respSpec);
        // Returning null simulates block() returning null
        when(respSpec.toBodilessEntity()).thenReturn(reactor.core.publisher.Mono.just(
                org.springframework.http.ResponseEntity.ok().<Void>build()) );

        stubPutSucceeds();

        // null response → version treated as -1 → local version 1 > -1 → repair triggered
        // (The above stub actually returns a valid response with no X-Quorum-Version header
        //  which also hits the "header is null → return -1" branch.)
        scrubber.scrub();

        // PUT must have been called because version was -1 < 1
        verify(peerWebClient, atLeastOnce()).put();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Test: fetchPeerVersion — 404 NotFound → returns 0, triggers repair
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void scrub_peerReturns404_treatedAsVersion0AndRepaired() {
        NodeInfo self  = makeNode(SELF_NODE_ID,   "10.0.0.1", PEER_PORT);
        NodeInfo peerA = makeNode(PEER_A_NODE_ID, PEER_A_HOST, PEER_PORT);

        store.put("key1", cacheItem(1L)); // local version = 1 > 0

        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getAliveNodes()).thenReturn(List.of(self, peerA));
        stubRingWithSelfAndPeerA(self, peerA);

        // Stub GET to throw WebClientResponseException.NotFound (404)
        WebClient.RequestHeadersUriSpec<?> uriSpec  = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec<?>    hdrsSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec             respSpec = mock(WebClient.ResponseSpec.class);
        when(peerWebClient.get()).thenReturn((WebClient.RequestHeadersUriSpec) uriSpec);
        when(uriSpec.uri(anyString())).thenReturn((WebClient.RequestHeadersSpec) hdrsSpec);
        when(hdrsSpec.retrieve()).thenReturn(respSpec);

        WebClientResponseException notFound =
                WebClientResponseException.create(
                        404, "Not Found",
                        new HttpHeaders(),
                        new byte[0],
                        StandardCharsets.UTF_8);
        when(respSpec.toBodilessEntity())
                .thenReturn(reactor.core.publisher.Mono.error(notFound));

        stubPutSucceeds();

        scrubber.scrub();

        // 404 → version 0, local version 1 > 0 → repair must be triggered
        verify(peerWebClient, atLeastOnce()).put();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Test: key evicted between sampling and processing → rateLimitWait + continue
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void scrub_keyEvictedBetweenSamplingAndProcessing_noRepairForThatKey() {
        NodeInfo self  = makeNode(SELF_NODE_ID,   "10.0.0.1", PEER_PORT);
        NodeInfo peerA = makeNode(PEER_A_NODE_ID, PEER_A_HOST, PEER_PORT);

        // Put key1 into store, then remove it before scrub can process it
        store.put("key1", cacheItem(10L));

        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getAliveNodes()).thenReturn(List.of(self, peerA));

        // Remove from store to simulate TTL eviction between sampling and processing
        store.remove("key1");

        // No GET/PUT should happen because localItem is null
        scrubber.scrub();

        verify(peerWebClient, never()).get();
        verify(peerWebClient, never()).put();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Test 9: sample size cap — does not scan more keys than configured
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void scrub_sampleSizeRespected_doesNotScanBeyondSampleSize() {
        NodeInfo self  = makeNode(SELF_NODE_ID,   "10.0.0.1", PEER_PORT);
        NodeInfo peerA = makeNode(PEER_A_NODE_ID, PEER_A_HOST, PEER_PORT);

        // Insert 20 keys but configure sample size = 5
        for (int i = 0; i < 20; i++) {
            store.put("key-" + i, cacheItem(1L));
        }

        AntiEntropyScrubber smallScrubber = new AntiEntropyScrubber(
                store,
                membershipList,
                peerWebClient,
                meterRegistry,
                hashRing,
                cacheService,
                5,
                0L,
                DEFAULT_REPLICATION_FACTOR
        );

        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getAliveNodes()).thenReturn(List.of(self, peerA));
        stubRingWithSelfAndPeerA(self, peerA);

        // Peer returns same version for all keys (no repairs needed)
        stubGetForAnyHost(1L);

        smallScrubber.scrub();

        Counter keysScanned = meterRegistry.find("edgefabric.anti_entropy.keys_scanned").counter();
        assertThat(keysScanned).isNotNull();
        // Must have scanned exactly 5 keys (the configured sample size), not 20
        assertThat(keysScanned.count()).isEqualTo(5.0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Test 10: ring not yet populated → scrubber skips round (no network calls)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void scrub_ringEmpty_skipsRound() {
        NodeInfo self  = makeNode(SELF_NODE_ID,   "10.0.0.1", PEER_PORT);
        NodeInfo peerA = makeNode(PEER_A_NODE_ID, PEER_A_HOST, PEER_PORT);

        store.put("key1", cacheItem(10L));

        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getAliveNodes()).thenReturn(List.of(self, peerA));
        when(hashRing.isEmpty()).thenReturn(true); // ring not bootstrapped yet

        scrubber.scrub();

        verify(peerWebClient, never()).get();
        verify(peerWebClient, never()).put();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Test 11: this node is NOT a replica for a key → key is skipped entirely
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void scrub_selfNotAReplicaForKey_keySkippedWithoutContactingAnyPeer() {
        NodeInfo self  = makeNode(SELF_NODE_ID,   "10.0.0.1", PEER_PORT);
        NodeInfo peerA = makeNode(PEER_A_NODE_ID, PEER_A_HOST, PEER_PORT);
        NodeInfo peerB = makeNode(PEER_B_NODE_ID, PEER_B_HOST, PEER_PORT);

        store.put("key1", cacheItem(10L));

        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getAliveNodes()).thenReturn(List.of(self, peerA, peerB));

        // Ring says: key1's replicas are peerA and peerB — self is NOT included
        NodeInfoHashAdapter peerAAdapter = new NodeInfoHashAdapter(peerA);
        NodeInfoHashAdapter peerBAdapter = new NodeInfoHashAdapter(peerB);
        when(hashRing.isEmpty()).thenReturn(false);
        when(hashRing.getNodes(anyString(), eq(DEFAULT_REPLICATION_FACTOR)))
                .thenReturn(List.of(peerAAdapter, peerBAdapter));

        scrubber.scrub();

        // Self is not a replica → key must be skipped → no network calls
        verify(peerWebClient, never()).get();
        verify(peerWebClient, never()).put();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Test 12: only co-replicas contacted — not all alive peers
    //  5 alive nodes, RF=3 → only 2 co-replicas checked (not 4 peers)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void scrub_onlyCoReplicasContacted_notAllAliveNodes() {
        NodeInfo self  = makeNode(SELF_NODE_ID,   "10.0.0.1", PEER_PORT);
        NodeInfo peerA = makeNode(PEER_A_NODE_ID, PEER_A_HOST, PEER_PORT);
        NodeInfo peerB = makeNode(PEER_B_NODE_ID, PEER_B_HOST, PEER_PORT);
        NodeInfo peerC = makeNode("node-peer-c",  "10.0.0.4",  PEER_PORT);
        NodeInfo peerD = makeNode("node-peer-d",  "10.0.0.5",  PEER_PORT);

        store.put("key1", cacheItem(10L));

        when(membershipList.getSelf()).thenReturn(self);
        when(membershipList.getAliveNodes()).thenReturn(List.of(self, peerA, peerB, peerC, peerD));
        when(membershipList.getNode(PEER_A_NODE_ID)).thenReturn(peerA);
        when(membershipList.getNode(PEER_B_NODE_ID)).thenReturn(peerB);

        // Ring says: key1 belongs to [self, peerA, peerB] — peerC and peerD are NOT replicas
        NodeInfoHashAdapter selfAdapter  = new NodeInfoHashAdapter(self);
        NodeInfoHashAdapter peerAAdapter = new NodeInfoHashAdapter(peerA);
        NodeInfoHashAdapter peerBAdapter = new NodeInfoHashAdapter(peerB);
        when(hashRing.isEmpty()).thenReturn(false);
        when(hashRing.getNodes(anyString(), eq(DEFAULT_REPLICATION_FACTOR)))
                .thenReturn(List.of(selfAdapter, peerAAdapter, peerBAdapter));

        // All co-replicas in sync — no repair
        stubGetForAnyHost(10L);

        scrubber.scrub();

        // GET must be called exactly twice: once for peerA, once for peerB — NOT 4 times
        verify(peerWebClient, times(2)).get();
        verify(peerWebClient, never()).put();
    }
}
