package com.edgefabric.caching.scheduler;

import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.migration.NodeInfoHashAdapter;
import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import com.edgefabric.caching.service.InternalCacheService;
import com.edgefabric.hashing.core.ConsistentHashRing;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for reverse repair in {@link AntiEntropyScrubber}.
 *
 * <p>Verifies that when local version is stale, the scrubber pulls fresh data from peers (AC4).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AntiEntropyReverseRepairTest {

    @Mock
    private MembershipList membershipList;

    @Mock
    private WebClient peerWebClient;

    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    @Mock
    private InternalCacheService cacheService;

    @SuppressWarnings("unchecked")
    @Mock
    private ConsistentHashRing<NodeInfoHashAdapter> hashRing;

    private Map<String, CacheItem> store;
    private MeterRegistry meterRegistry;
    private AntiEntropyScrubber scrubber;

    private static final String SELF_NODE_ID = "node-self";
    private static final int RF = 3;

    @BeforeEach
    void setUp() {
        store = new ConcurrentHashMap<>();
        meterRegistry = new SimpleMeterRegistry();

        NodeInfo self = new NodeInfo(SELF_NODE_ID, "127.0.0.1", 8082, 7946);

        when(membershipList.getSelf()).thenReturn(self);

        scrubber = new AntiEntropyScrubber(
                store,
                membershipList,
                peerWebClient,
                meterRegistry,
                hashRing,
                cacheService,
                20,     // sampleSize
                10,     // rateLimitMs
                RF      // replicationFactor
        );
    }

    // ── Reverse Repair ────────────────────────────────────────────────────────

    @Test
    void scrub_whenLocalVersionIsStale_shouldPullFromPeer() {
        // given
        String key = "user:123:profile";
        byte[] staleData = "stale-data".getBytes();
        byte[] freshData = "fresh-data".getBytes();

        // Local has version 100 (stale)
        store.put(key, new CacheItem(
                staleData,
                System.currentTimeMillis() + 60000,
                "text/plain",
                100L,
                true
        ));

        NodeInfo self = new NodeInfo(SELF_NODE_ID, "127.0.0.1", 8082, 7946);
        NodeInfo peer = NodeInfo.getInstance("node-peer", "192.168.1.10", 8082, 7946, Status.ALIVE, 0, 0);

        List<NodeInfoHashAdapter> replicas = List.of(
                new NodeInfoHashAdapter(self),
                new NodeInfoHashAdapter(peer)
        );

        // Mock membership list methods for anti-entropy scrubber
        when(membershipList.getAliveNodes()).thenReturn(List.of(self, peer));
        when(membershipList.getNode("node-peer")).thenReturn(peer);

        when(hashRing.getNodes(eq(key), eq(RF))).thenReturn(replicas);

        // Peer has version 200 (fresh)
        HttpHeaders versionHeaders = new HttpHeaders();
        versionHeaders.add("X-Quorum-Version", "200");

        HttpHeaders dataHeaders = new HttpHeaders();
        dataHeaders.add("X-Quorum-Version", "200");
        dataHeaders.add("X-Expires-At", String.valueOf(System.currentTimeMillis() + 60000));
        dataHeaders.add("Content-Type", "text/plain");

        ResponseEntity<Void> versionResponse = new ResponseEntity<>(versionHeaders, HttpStatus.OK);
        ResponseEntity<byte[]> dataResponse = new ResponseEntity<>(freshData, dataHeaders, HttpStatus.OK);

        when(peerWebClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        // First call: fetchPeerVersion uses toBodilessEntity
        // Second call: repairLocal uses toEntity
        when(responseSpec.toBodilessEntity()).thenReturn(Mono.just(versionResponse));
        when(responseSpec.toEntity(byte[].class)).thenReturn(Mono.just(dataResponse));

        // when
        scrubber.scrub();

        // then
        verify(cacheService, times(1)).storeData(eq(key), eq(freshData), anyLong(), eq("text/plain"), eq(200L));
    }

    @Test
    void scrub_whenLocalVersionIsFresh_shouldRepairPeer() {
        // given
        String key = "user:456:settings";
        byte[] freshData = "fresh-data".getBytes();

        // Local has version 300 (fresh)
        store.put(key, new CacheItem(
                freshData,
                System.currentTimeMillis() + 60000,
                "application/json",
                300L,
                true
        ));

        NodeInfo self = new NodeInfo(SELF_NODE_ID, "127.0.0.1", 8082, 7946);
        NodeInfo peer = NodeInfo.getInstance("node-peer", "192.168.1.10", 8082, 7946, Status.ALIVE, 0, 0);

        List<NodeInfoHashAdapter> replicas = List.of(
                new NodeInfoHashAdapter(self),
                new NodeInfoHashAdapter(peer)
        );

        // Mock membership list methods for anti-entropy scrubber
        when(membershipList.getAliveNodes()).thenReturn(List.of(self, peer));
        when(membershipList.getNode("node-peer")).thenReturn(peer);

        when(hashRing.getNodes(eq(key), eq(RF))).thenReturn(replicas);

        // Peer has version 100 (stale)
        HttpHeaders versionHeaders = new HttpHeaders();
        versionHeaders.add("X-Quorum-Version", "100");

        ResponseEntity<Void> versionResponse = new ResponseEntity<>(versionHeaders, HttpStatus.OK);

        when(peerWebClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(Mono.just(versionResponse));

        WebClient.RequestBodyUriSpec requestBodyUriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec requestBodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec putHeadersSpec = mock(WebClient.RequestHeadersSpec.class);

        when(peerWebClient.put()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn(putHeadersSpec);
        when(putHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(Mono.just(ResponseEntity.ok().build()));

        // when
        scrubber.scrub();

        // then
        verify(peerWebClient, times(1)).put();
        verify(cacheService, never()).storeData(anyString(), any(byte[].class), anyLong(), anyString(), anyLong());
    }

    @Test
    void scrub_whenPeerVersionEqualToLocal_shouldNotRepair() {
        // given
        String key = "user:789:profile";
        byte[] data = "same-data".getBytes();

        // Local has version 150
        store.put(key, new CacheItem(
                data,
                System.currentTimeMillis() + 60000,
                "text/plain",
                150L,
                true
        ));

        NodeInfo peer = NodeInfo.getInstance("node-peer", "192.168.1.10", 8082, 7946, Status.ALIVE, 0, 0);

        List<NodeInfoHashAdapter> replicas = List.of(
                new NodeInfoHashAdapter(new NodeInfo(SELF_NODE_ID, "127.0.0.1", 8082, 7946)),
                new NodeInfoHashAdapter(peer)
        );

        when(hashRing.getNodes(eq(key), eq(RF))).thenReturn(replicas);

        // Peer has version 150 (same)
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Quorum-Version", "150");
        headers.add("X-Expires-At", String.valueOf(System.currentTimeMillis() + 60000));
        headers.add("Content-Type", "text/plain");

        ResponseEntity<byte[]> response = new ResponseEntity<>(data, headers, HttpStatus.OK);

        when(peerWebClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(byte[].class)).thenReturn(Mono.just(response));

        // when
        scrubber.scrub();

        // then
        verify(cacheService, never()).storeData(anyString(), any(byte[].class), anyLong(), anyString(), anyLong());
        verify(peerWebClient, never()).put();
    }

    @Test
    void scrub_whenPeerIsDeadOrSuspect_shouldSkipNode() {
        // given
        String key = "user:999:data";
        byte[] data = "data".getBytes();

        store.put(key, new CacheItem(
                data,
                System.currentTimeMillis() + 60000,
                "text/plain",
                100L,
                true
        ));

        NodeInfo deadPeer = NodeInfo.getInstance("node-dead", "192.168.1.20", 8082, 7946, Status.DEAD, 0, 0);

        NodeInfo suspectPeer = NodeInfo.getInstance("node-suspect", "192.168.1.21", 8082, 7946, Status.SUSPECT, 0, 0);

        List<NodeInfoHashAdapter> replicas = List.of(
                new NodeInfoHashAdapter(new NodeInfo(SELF_NODE_ID, "127.0.0.1", 8082, 7946)),
                new NodeInfoHashAdapter(deadPeer),
                new NodeInfoHashAdapter(suspectPeer)
        );

        when(hashRing.getNodes(eq(key), eq(RF))).thenReturn(replicas);

        // when
        scrubber.scrub();

        // then
        verify(peerWebClient, never()).get();
    }
}
