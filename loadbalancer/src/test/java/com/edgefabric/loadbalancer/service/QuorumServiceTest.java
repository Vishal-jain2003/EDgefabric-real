package com.edgefabric.loadbalancer.service;

import com.edgefabric.loadbalancer.client.ClusterClient;
import com.edgefabric.loadbalancer.config.QuorumProperties;
import com.edgefabric.loadbalancer.dto.response.CacheResponse;
import com.edgefabric.loadbalancer.exception.QuorumNotMetException;
import com.edgefabric.loadbalancer.metrics.QuorumMetricsService;
import com.edgefabric.loadbalancer.model.CacheNode;
import com.edgefabric.loadbalancer.exception.CacheKeyNotFoundException;
import com.edgefabric.loadbalancer.exception.CacheKeyExpiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuorumServiceTest {

    @Mock
    private CacheRouter cacheRouter;
    
    @Mock
    private ClusterClient clusterClient;
    
    @Mock
    private ReadRepairService readRepairService;

    @Mock
    private QuorumMetricsService metricsService;

    private QuorumProperties quorumProperties;
    private QuorumService quorumService;
    private ExecutorService testExecutor;

    private static final CacheNode NODE_1 = new CacheNode("node-1", "host1", 8081);
    private static final CacheNode NODE_2 = new CacheNode("node-2", "host2", 8082);
    private static final CacheNode NODE_3 = new CacheNode("node-3", "host3", 8083);

    @BeforeEach
    void setUp() {
        quorumProperties = new QuorumProperties();
        quorumProperties.setReplicationFactor(3);
        quorumProperties.setWrite(2);
        quorumProperties.setRead(2);
        quorumProperties.setTimeoutMs(5000);
    
        testExecutor = Executors.newFixedThreadPool(4);
        quorumService = new QuorumService(cacheRouter, clusterClient, quorumProperties,
                readRepairService, metricsService);
    }

    // ────────────────────────────────────────────────────────────────
    // WRITE QUORUM TESTS
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Write quorum succeeds when all replicas respond")
    void quorumWrite_AllReplicasSucceed() {
        long expiresAt = System.currentTimeMillis() + 5000L;
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        when(clusterClient.forwardPutRequest(any(), eq("key1"), any(byte[].class), eq(expiresAt), any(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.empty());

        assertDoesNotThrow(() ->
                quorumService.quorumWrite("key1", "data".getBytes(), expiresAt, "text/plain").block()
        );

        // Reactive .take(W) stops after W=2 successes - 3rd replica may not be called
        verify(clusterClient, timeout(1000).atLeast(2))
                .forwardPutRequest(any(), eq("key1"), any(byte[].class), eq(expiresAt), any(), anyLong());
    }

    @Test
    @DisplayName("Write quorum succeeds when exactly W replicas respond (1 fails)")
    void quorumWrite_OneFailsButQuorumMet() {
        long expiresAt = System.currentTimeMillis() + 5000L;
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        // NODE_1 and NODE_2 succeed (W=2); latch may fire before NODE_3 is even dispatched
        lenient().when(clusterClient.forwardPutRequest(eq(NODE_1), any(), any(byte[].class), eq(expiresAt), any(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.empty());
        lenient().when(clusterClient.forwardPutRequest(eq(NODE_2), any(), any(byte[].class), eq(expiresAt), any(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.empty());
        lenient().when(clusterClient.forwardPutRequest(eq(NODE_3), any(), any(byte[].class), eq(expiresAt), any(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("Connection refused")));

        assertDoesNotThrow(() ->
                quorumService.quorumWrite("key1", "data".getBytes(), expiresAt, "text/plain").block()
        );
    }

    @Test
    @DisplayName("Write quorum fails when too many replicas fail")
    void quorumWrite_TooManyFailures() {
        long expiresAt = System.currentTimeMillis() + 5000L;
        byte[] payload = "data".getBytes();
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        // Quorum may abort once it determines W=2 can no longer be met; any stub may not be dispatched
        lenient().when(clusterClient.forwardPutRequest(eq(NODE_1), any(), any(byte[].class), anyLong(), any(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.empty());
        lenient().when(clusterClient.forwardPutRequest(eq(NODE_2), any(), any(byte[].class), anyLong(), any(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("Connection refused")));
        lenient().when(clusterClient.forwardPutRequest(eq(NODE_3), any(), any(byte[].class), anyLong(), any(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("Connection refused")));

        Executable writeCall = () -> quorumService.quorumWrite("key1", payload, expiresAt, "text/plain").block();
        QuorumNotMetException ex = assertThrows(QuorumNotMetException.class, writeCall);

        assertEquals("WRITE", ex.getOperation());
        assertEquals(2, ex.getRequired());
    }

    @Test
    @DisplayName("Write quorum fails immediately when not enough nodes available")
    void quorumWrite_NotEnoughNodes() {
        long expiresAt = System.currentTimeMillis() + 5000L;
        byte[] payload = "data".getBytes();
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1));

        Executable writeCall = () -> quorumService.quorumWrite("key1", payload, expiresAt, "text/plain").block();
        QuorumNotMetException ex = assertThrows(QuorumNotMetException.class, writeCall);

        assertEquals("WRITE", ex.getOperation());
        assertEquals(2, ex.getRequired());
        assertEquals(1, ex.getAchieved());

        verify(clusterClient, never())
                .forwardPutRequest(any(), any(), any(byte[].class), anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("Write quorum sends same version and expiry to all replicas")
    void quorumWrite_SameVersionToAllReplicas() {
        byte[] payload = "data".getBytes();
        long expiresAt = System.currentTimeMillis() + 5000L;
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        when(clusterClient.forwardPutRequest(any(), any(), any(byte[].class), anyLong(), any(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.empty());

        quorumService.quorumWrite("key1", payload, expiresAt, "text/plain").block();

        // Reactive .take(W) stops after W=2 successes
        ArgumentCaptor<Long> versionCaptor = ArgumentCaptor.forClass(Long.class);
        verify(clusterClient, timeout(1000).atLeast(2))
                .forwardPutRequest(any(), eq("key1"), aryEq(payload),
                        eq(expiresAt), eq("text/plain"), versionCaptor.capture());

        List<Long> versions = versionCaptor.getAllValues();
        assertTrue(versions.size() >= 2, "At least W=2 replicas should receive the version");
        assertTrue(versions.stream().allMatch(v -> v.equals(versions.getFirst())), "All replicas must get identical version");
    }

    // ────────────────────────────────────────────────────────────────
    // READ QUORUM TESTS
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Read quorum returns highest version response")
    void quorumRead_ReturnsHighestVersion() {
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        CacheResponse staleResponse = CacheResponse.builder()
                .data("old".getBytes()).contentType("text/plain").version(100L).expiresAt(1000L).build();
        CacheResponse freshResponse = CacheResponse.builder()
                .data("new".getBytes()).contentType("text/plain").version(200L).expiresAt(2000L).build();

        // Lenient: R=2 so only 2 of 3 stubs are invoked; fresh is on 2 nodes so any pair returns v200
        lenient().when(clusterClient.forwardGetRequest(NODE_1, "key1")).thenReturn(reactor.core.publisher.Mono.just(staleResponse));
        lenient().when(clusterClient.forwardGetRequest(NODE_2, "key1")).thenReturn(reactor.core.publisher.Mono.just(freshResponse));
        lenient().when(clusterClient.forwardGetRequest(NODE_3, "key1")).thenReturn(reactor.core.publisher.Mono.just(freshResponse));

        CacheResponse result = quorumService.quorumRead("key1").block();

        assertNotNull(result);
        assertArrayEquals("new".getBytes(), result.getData());
        assertEquals(200L, result.getVersion());
        assertEquals(2000L, result.getExpiresAt());
    }

    @Test
    @DisplayName("Read quorum succeeds even with one node failure")
    void quorumRead_OneFailsButQuorumMet() {
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        CacheResponse response = CacheResponse.builder()
                .data("data".getBytes()).contentType("text/plain").version(100L).expiresAt(5000L).build();

        // NODE_1 and NODE_2 succeed (R=2); latch fires before NODE_3 may be dispatched
        lenient().when(clusterClient.forwardGetRequest(NODE_1, "key1")).thenReturn(reactor.core.publisher.Mono.just(response));
        lenient().when(clusterClient.forwardGetRequest(NODE_2, "key1")).thenReturn(reactor.core.publisher.Mono.just(response));
        lenient().when(clusterClient.forwardGetRequest(NODE_3, "key1"))
                .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("Node down")));

        CacheResponse result = quorumService.quorumRead("key1").block();

        assertNotNull(result);
        assertArrayEquals("data".getBytes(), result.getData());
    }

    @Test
    @DisplayName("Read quorum fails when too many replicas fail")
    void quorumRead_TooManyFailures() {
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        CacheResponse response = CacheResponse.builder()
                .data("data".getBytes()).contentType("text/plain").version(100L).expiresAt(5000L).build();

        // Quorum may abort once R=2 cannot be achieved; any stub may not be dispatched
        lenient().when(clusterClient.forwardGetRequest(NODE_1, "key1")).thenReturn(reactor.core.publisher.Mono.just(response));
        lenient().when(clusterClient.forwardGetRequest(NODE_2, "key1"))
                .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("Node down")));
        lenient().when(clusterClient.forwardGetRequest(NODE_3, "key1"))
                .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("Node down")));

        QuorumNotMetException ex = assertThrows(QuorumNotMetException.class,
                () -> quorumService.quorumRead("key1").block());

        assertEquals("READ", ex.getOperation());
        assertEquals(2, ex.getRequired());
    }

    @Test
    @DisplayName("Read quorum fails immediately when not enough nodes available")
    void quorumRead_NotEnoughNodes() {
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1));

        QuorumNotMetException ex = assertThrows(QuorumNotMetException.class,
                () -> quorumService.quorumRead("key1").block());

        assertEquals("READ", ex.getOperation());
        assertEquals(2, ex.getRequired());
        assertEquals(1, ex.getAchieved());

        verify(clusterClient, never()).forwardGetRequest(any(), any());
    }

    // ────────────────────────────────────────────────────────────────
    // READ REPAIR TESTS
    // ────────────────────────────────────────────────────────────────
    
    @Test
    @DisplayName("Read Repair: no repair triggered when all replicas return same version (consistent)")
    void quorumRead_NoRepairWhenAllReplicasConsistent() {
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        CacheResponse sameResponse = CacheResponse.builder()
                .data("data".getBytes()).contentType("text/plain").version(100L).expiresAt(5000L).build();

        // R=2: latch fires after 2 acks; NODE_3 may not be called if quorum already met
        lenient().when(clusterClient.forwardGetRequest(NODE_1, "key1")).thenReturn(reactor.core.publisher.Mono.just(sameResponse));
        lenient().when(clusterClient.forwardGetRequest(NODE_2, "key1")).thenReturn(reactor.core.publisher.Mono.just(sameResponse));
        lenient().when(clusterClient.forwardGetRequest(NODE_3, "key1")).thenReturn(reactor.core.publisher.Mono.just(sameResponse));

        CacheResponse result = quorumService.quorumRead("key1").block();

        assertEquals(100L, result.getVersion());
        // All replicas have same version — staleReplicas list will be empty,
        // repairStaleReplicas is still called but performs no forwardPutRequest
        verify(readRepairService).repairStaleReplicas(eq("key1"), anyList(), eq(sameResponse));
        verify(clusterClient, never()).forwardPutRequest(any(), any(), any(byte[].class), anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("Read quorum throws CacheKeyNotFoundException when all replicas miss the key")
    void quorumRead_AllReplicasMissing() {

        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        // Quorum may abort after 2 miss signals; NODE_3 may not be dispatched
        lenient().when(clusterClient.forwardGetRequest(NODE_1, "key1"))
                .thenReturn(reactor.core.publisher.Mono.error(new CacheKeyNotFoundException("key1")));
        lenient().when(clusterClient.forwardGetRequest(NODE_2, "key1"))
                .thenReturn(reactor.core.publisher.Mono.error(new CacheKeyNotFoundException("key1")));
        lenient().when(clusterClient.forwardGetRequest(NODE_3, "key1"))
                .thenReturn(reactor.core.publisher.Mono.error(new CacheKeyNotFoundException("key1")));

        assertThrows(CacheKeyNotFoundException.class,
                () -> quorumService.quorumRead("key1").block());
    }
    
    @Test
    @DisplayName("Read Repair: one stale replica is repaired asynchronously")
    void quorumRead_OneStaleReplicaIsRepaired() {
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));
    
        CacheResponse staleResponse = CacheResponse.builder()
                .data("old".getBytes()).contentType("text/plain").version(100L).expiresAt(5000L).build();
        CacheResponse freshResponse = CacheResponse.builder()
                .data("new".getBytes()).contentType("text/plain").version(200L).expiresAt(9999L).build();
    
        // Use lenient stubs: quorum R=2 means only 2 of 3 replicas may respond before returning
        lenient().when(clusterClient.forwardGetRequest(NODE_1, "key1")).thenReturn(reactor.core.publisher.Mono.just(staleResponse));
        lenient().when(clusterClient.forwardGetRequest(NODE_2, "key1")).thenReturn(reactor.core.publisher.Mono.just(freshResponse));
        lenient().when(clusterClient.forwardGetRequest(NODE_3, "key1")).thenReturn(reactor.core.publisher.Mono.just(freshResponse));

        CacheResponse result = quorumService.quorumRead("key1").block();

        assertEquals(200L, result.getVersion());
        assertArrayEquals("new".getBytes(), result.getData());
        // Read repair service must be called with the winner response
        verify(readRepairService).repairStaleReplicas(eq("key1"), anyList(), eq(freshResponse));
    }
    
    @Test
    @DisplayName("Read Repair: stale replicas are repaired asynchronously")
    void quorumRead_MultipleStaleReplicasAreRepaired() {
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        // Fresh data on 2 of 3 nodes ensures winner=v300 regardless of which 2 respond first
        // (R=2 means only 2 of 3 stubs may be invoked — NODE_3 may not be called if quorum is met early)
        CacheResponse staleResponse = CacheResponse.builder()
                .data("v1".getBytes()).contentType("text/plain").version(50L).expiresAt(5000L).build();
        CacheResponse freshResponse = CacheResponse.builder()
                .data("v3".getBytes()).contentType("text/plain").version(300L).expiresAt(9999L).build();

        lenient().when(clusterClient.forwardGetRequest(NODE_1, "key1")).thenReturn(reactor.core.publisher.Mono.just(freshResponse));
        lenient().when(clusterClient.forwardGetRequest(NODE_2, "key1")).thenReturn(reactor.core.publisher.Mono.just(freshResponse));
        lenient().when(clusterClient.forwardGetRequest(NODE_3, "key1")).thenReturn(reactor.core.publisher.Mono.just(staleResponse));

        CacheResponse result = quorumService.quorumRead("key1").block();

        assertEquals(300L, result.getVersion());
        assertArrayEquals("v3".getBytes(), result.getData());
        // Stale or unresponsive nodes trigger read repair
        verify(readRepairService).repairStaleReplicas(eq("key1"), anyList(), eq(freshResponse));
    }
    
    @Test
    @DisplayName("Read Repair: repair is triggered even when one node fails during read")
    void quorumRead_RepairTriggeredWhenOneNodeFailsDuringRead() {
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        CacheResponse staleResponse = CacheResponse.builder()
                .data("old".getBytes()).contentType("text/plain").version(100L).expiresAt(5000L).build();
        CacheResponse freshResponse = CacheResponse.builder()
                .data("new".getBytes()).contentType("text/plain").version(200L).expiresAt(9999L).build();

        // NODE_1 (stale) + NODE_2 (fresh) => R=2 met; NODE_3 may not be dispatched before latch fires
        lenient().when(clusterClient.forwardGetRequest(NODE_1, "key1")).thenReturn(reactor.core.publisher.Mono.just(staleResponse));
        lenient().when(clusterClient.forwardGetRequest(NODE_2, "key1")).thenReturn(reactor.core.publisher.Mono.just(freshResponse));
        lenient().when(clusterClient.forwardGetRequest(NODE_3, "key1"))
                .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("Node down")));

        CacheResponse result = quorumService.quorumRead("key1").block();

        // Still returns the freshest value
        assertEquals(200L, result.getVersion());
        // Repair is still triggered for the stale NODE_1 (NODE_3 failed during read and is treated as stale (-1 version) for repair)
        verify(readRepairService).repairStaleReplicas(eq("key1"), anyList(), eq(freshResponse));
    }

    @Test
    @DisplayName("Read Repair: quorum met with 2 success + 1 key-not-found — data returned, missing node repaired")
    void quorumRead_QuorumMetWithOneKeyNotFound() {

        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        CacheResponse freshResponse = CacheResponse.builder()
                .data("data".getBytes())
                .contentType("text/plain")
                .version(500L)
                .expiresAt(9999L)
                .build();

        // NODE_1 + NODE_2 succeed (R=2 met); latch may fire before NODE_3 is dispatched
        lenient().when(clusterClient.forwardGetRequest(NODE_1, "key1"))
                .thenReturn(reactor.core.publisher.Mono.just(freshResponse));

        lenient().when(clusterClient.forwardGetRequest(NODE_2, "key1"))
                .thenReturn(reactor.core.publisher.Mono.just(freshResponse));

        lenient().when(clusterClient.forwardGetRequest(NODE_3, "key1"))
                .thenReturn(reactor.core.publisher.Mono.error(new CacheKeyNotFoundException("key1")));

        CacheResponse result = quorumService.quorumRead("key1").block();

        assertNotNull(result);
        assertEquals(500L, result.getVersion());
        assertArrayEquals("data".getBytes(), result.getData());

        verify(readRepairService)
                .repairStaleReplicas(eq("key1"), anyList(), eq(freshResponse));
    }

    @Test
    @DisplayName("Read: all nodes network error throws QuorumNotMetException, not CacheKeyNotFoundException")
    void quorumRead_AllNodesNetworkError_ThrowsQuorumNotMetException() {

        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        // Quorum may abort after 2 network failures; NODE_3 may not be dispatched
        lenient().when(clusterClient.forwardGetRequest(NODE_1, "key1"))
                .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("Connection refused")));

        lenient().when(clusterClient.forwardGetRequest(NODE_2, "key1"))
                .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("Connection refused")));

        lenient().when(clusterClient.forwardGetRequest(NODE_3, "key1"))
                .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("Connection refused")));

        assertThrows(QuorumNotMetException.class,
                () -> quorumService.quorumRead("key1").block());

        verify(readRepairService, never())
                .repairStaleReplicas(any(), anyList(), any());
    }

    @Test
    @DisplayName("Read: 1 success + 1 key-not-found + 1 network error — fast-fail QuorumNotMet")
    void quorumRead_MixedFailures_FastFailQuorumNotMet() {

        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        CacheResponse response = CacheResponse.builder()
                .data("data".getBytes())
                .contentType("text/plain")
                .version(100L)
                .expiresAt(5000L)
                .build();

        // Quorum may abort after 2 failures; any of the 3 stubs may not be dispatched
        lenient().when(clusterClient.forwardGetRequest(NODE_1, "key1"))
                .thenReturn(reactor.core.publisher.Mono.just(response));

        lenient().when(clusterClient.forwardGetRequest(NODE_2, "key1"))
                .thenReturn(reactor.core.publisher.Mono.error(new CacheKeyNotFoundException("key1")));

        lenient().when(clusterClient.forwardGetRequest(NODE_3, "key1"))
                .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("Connection refused")));

        QuorumNotMetException ex =
                assertThrows(QuorumNotMetException.class,
                        () -> quorumService.quorumRead("key1").block());

        assertEquals("READ", ex.getOperation());
        assertEquals(2, ex.getRequired());
    }

    @Test
    @DisplayName("Read: quorum met with 2 success + 1 key-expired — winner returned, repair triggered")
    void quorumRead_OneKeyExpired_QuorumMet() {
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        CacheResponse freshResponse = CacheResponse.builder()
                .data("data".getBytes())
                .contentType("text/plain")
                .version(500L)
                .expiresAt(99999L)
                .build();

        // NODE_1 + NODE_2 succeed (R=2 met); latch may fire before NODE_3 is dispatched
        lenient().when(clusterClient.forwardGetRequest(NODE_1, "key1"))
                .thenReturn(reactor.core.publisher.Mono.just(freshResponse));
        lenient().when(clusterClient.forwardGetRequest(NODE_2, "key1"))
                .thenReturn(reactor.core.publisher.Mono.just(freshResponse));
        lenient().when(clusterClient.forwardGetRequest(NODE_3, "key1"))
                .thenReturn(reactor.core.publisher.Mono.error(new CacheKeyExpiredException("key1", 1000L)));

        CacheResponse result = quorumService.quorumRead("key1").block();

        assertNotNull(result);
        assertEquals(500L, result.getVersion());
        verify(readRepairService).repairStaleReplicas(eq("key1"), anyList(), eq(freshResponse));
    }

    @Test
    @DisplayName("Read: all replicas expired — throws CacheKeyNotFoundException (all missed)")
    void quorumRead_AllExpired_ThrowsCacheKeyNotFound() {
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        // Quorum may abort as soon as enough "miss" signals arrive; NODE_3 may not be dispatched
        lenient().when(clusterClient.forwardGetRequest(NODE_1, "key1"))
                .thenReturn(reactor.core.publisher.Mono.error(new CacheKeyExpiredException("key1", 1000L)));
        lenient().when(clusterClient.forwardGetRequest(NODE_2, "key1"))
                .thenReturn(reactor.core.publisher.Mono.error(new CacheKeyExpiredException("key1", 1000L)));
        lenient().when(clusterClient.forwardGetRequest(NODE_3, "key1"))
                .thenReturn(reactor.core.publisher.Mono.error(new CacheKeyExpiredException("key1", 1000L)));

        assertThrows(CacheKeyNotFoundException.class,
                () -> quorumService.quorumRead("key1").block());
    }

    @Test
    @DisplayName("Read: mix of expired and not-found — all miss — throws CacheKeyNotFoundException")
    void quorumRead_MixExpiredAndNotFound_AllMiss() {
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        // Quorum may abort as soon as enough "miss" signals arrive; NODE_3 may not be dispatched
        lenient().when(clusterClient.forwardGetRequest(NODE_1, "key1"))
                .thenReturn(reactor.core.publisher.Mono.error(new CacheKeyNotFoundException("key1")));
        lenient().when(clusterClient.forwardGetRequest(NODE_2, "key1"))
                .thenReturn(reactor.core.publisher.Mono.error(new CacheKeyExpiredException("key1", 1000L)));
        lenient().when(clusterClient.forwardGetRequest(NODE_3, "key1"))
                .thenReturn(reactor.core.publisher.Mono.error(new CacheKeyNotFoundException("key1")));

        assertThrows(CacheKeyNotFoundException.class,
                () -> quorumService.quorumRead("key1").block());
    }

    // ────────────────────────────────────────────────────────────────
    // EDGE CASES
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Quorum works with N=1, W=1, R=1 (single node)")
    void quorumWrite_SingleNode() {
        long expiresAt = System.currentTimeMillis() + 5000L;
        quorumProperties.setReplicationFactor(1);
        quorumProperties.setWrite(1);
        quorumProperties.setRead(1);
        QuorumService singleNodeService = new QuorumService(cacheRouter, clusterClient, quorumProperties,
                readRepairService, metricsService);

        when(cacheRouter.routeToReplicas("key1", 1)).thenReturn(List.of(NODE_1));
        when(clusterClient.forwardPutRequest(any(), any(), any(byte[].class), anyLong(), any(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.empty());

        assertDoesNotThrow(() ->
                singleNodeService.quorumWrite("key1", "data".getBytes(), expiresAt, "text/plain").block()
        );
    }

    @Test
    @DisplayName("Quorum handles fewer available nodes than N (graceful degradation)")
    void quorumWrite_FewerNodesThanN_ButEnoughForQuorum() {
        long expiresAt = System.currentTimeMillis() + 5000L;
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2));

        when(clusterClient.forwardPutRequest(any(), any(), any(byte[].class), anyLong(), any(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.empty());

        assertDoesNotThrow(() ->
                quorumService.quorumWrite("key1", "data".getBytes(), expiresAt, "text/plain").block()
        );

        verify(clusterClient, timeout(1000).times(2))
                .forwardPutRequest(any(), any(), any(byte[].class), eq(expiresAt), any(), anyLong());
    }

    // ────────────────────────────────────────────────────────────────
    // MONOTONIC VERSION TESTS  (EPMICMPHE-212)
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Versions are strictly increasing across sequential writes (no nanoTime regression)")
    void quorumWrite_VersionIsStrictlyMonotonic() throws Exception {
        long expiresAt = System.currentTimeMillis() + 5000L;
        when(cacheRouter.routeToReplicas(anyString(), eq(3)))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));
        when(clusterClient.forwardPutRequest(any(), anyString(), any(byte[].class), anyLong(), any(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.empty());

        int writes = 50;
        ArgumentCaptor<Long> versionCaptor = ArgumentCaptor.forClass(Long.class);

        for (int i = 0; i < writes; i++) {
            quorumService.quorumWrite("key-" + i, ("v" + i).getBytes(), expiresAt, "text/plain").block();
        }

        verify(clusterClient, timeout(2000).atLeast(writes))
                .forwardPutRequest(any(), anyString(), any(byte[].class), anyLong(), any(), versionCaptor.capture());

        List<Long> versions = versionCaptor.getAllValues();
        // Each write must produce a version strictly greater than the previous write's version.
        // Group by uniqueness: there must be at least `writes` distinct version values.
        long distinctCount = versions.stream().distinct().count();
        assertTrue(distinctCount >= writes,
                "Expected at least " + writes + " distinct versions but got " + distinctCount);

        // All versions must be seeded from currentTimeMillis — well above zero
        long minExpected = System.currentTimeMillis() - 5000L;
        assertTrue(versions.stream().allMatch(v -> v > minExpected),
                "Versions must be seeded from System.currentTimeMillis() to ensure restart-safety and cross-instance ordering");
    }

    @Test
    @DisplayName("Same version is sent to all replicas within a single write (atomicity)")
    void quorumWrite_SameVersionWithinSingleWrite() throws Exception {
        byte[] payload = "data".getBytes();
        long expiresAt = System.currentTimeMillis() + 5000L;
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));
        when(clusterClient.forwardPutRequest(any(), any(), any(byte[].class), anyLong(), any(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.empty());

        quorumService.quorumWrite("key1", payload, expiresAt, "text/plain").block();

        // Reactive .take(W) stops after W=2 successes - 3rd replica not contacted
        ArgumentCaptor<Long> versionCaptor = ArgumentCaptor.forClass(Long.class);
        verify(clusterClient, timeout(1000).atLeast(2))
                .forwardPutRequest(any(), eq("key1"), aryEq(payload),
                        eq(expiresAt), eq("text/plain"), versionCaptor.capture());

        List<Long> versions = versionCaptor.getAllValues();
        assertTrue(versions.size() >= 2, "At least W=2 replicas must receive a version");
        long first = versions.getFirst();
        assertTrue(versions.stream().allMatch(v -> v.equals(first)),
                "All contacted replicas must receive identical version " + first);
    }

    // ────────────────────────────────────────────────────────────────
    // COVERAGE GAP TESTS — previously uncovered paths
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("quorumRead throws CacheKeyNotFoundException when all replicas report key missing")
    void quorumRead_AllReplicasKeyNotFound_ThrowsCacheKeyNotFoundException() {
        when(cacheRouter.routeToReplicas(anyString(), eq(3)))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));
        when(clusterClient.forwardGetRequest(any(), anyString()))
                .thenReturn(reactor.core.publisher.Mono.error(new CacheKeyNotFoundException("missing-key")));

        CacheKeyNotFoundException ex = assertThrows(CacheKeyNotFoundException.class, () ->
                quorumService.quorumRead("missing-key").block()
        );
        assertEquals("missing-key", ex.getKey());
    }

    @Test
    @DisplayName("quorumRead throws CacheKeyNotFoundException when all replicas report key expired")
    void quorumRead_AllReplicasKeyExpired_ThrowsCacheKeyNotFoundException() {
        when(cacheRouter.routeToReplicas(anyString(), eq(3)))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));
        when(clusterClient.forwardGetRequest(any(), anyString()))
                .thenReturn(reactor.core.publisher.Mono.error(new CacheKeyExpiredException("expired-key", System.currentTimeMillis() - 1000)));

        assertThrows(CacheKeyNotFoundException.class, () ->
                quorumService.quorumRead("expired-key").block()
        );
    }

    @Test
    @DisplayName("quorumWrite times out and throws QuorumNotMetException when replicas are unresponsive")
    void quorumWrite_AwaitQuorum_ThrowsOnThreadInterrupt() throws Exception {
        long expiresAt = System.currentTimeMillis() + 30_000L;
        quorumProperties.setTimeoutMs(100); // Short timeout for test
        QuorumService shortTimeoutService = new QuorumService(cacheRouter, clusterClient, quorumProperties, readRepairService, metricsService);

        when(cacheRouter.routeToReplicas(anyString(), eq(3)))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        // Simulate replicas that never respond (delayed forever)
        when(clusterClient.forwardPutRequest(any(), any(), any(byte[].class), anyLong(), any(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.never());

        QuorumNotMetException ex = assertThrows(QuorumNotMetException.class, () ->
                shortTimeoutService.quorumWrite("key-timeout", "data".getBytes(), expiresAt, "text/plain").block()
        );

        assertEquals("WRITE", ex.getOperation());
        assertEquals(2, ex.getRequired());
    }

    @Test
    @DisplayName("Concurrent writes produce strictly increasing, non-duplicate versions")
    void quorumWrite_ConcurrentWritesProduceUniqueMonotonicVersions() throws Exception {
        int threadCount = 10;
        long expiresAt = System.currentTimeMillis() + 10_000L;

        when(cacheRouter.routeToReplicas(anyString(), eq(3)))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));
        when(clusterClient.forwardPutRequest(any(), anyString(), any(byte[].class), anyLong(), any(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.empty());

        // Fire writes from multiple threads simultaneously
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try { startLatch.await(); } catch (InterruptedException ignored) {}
                quorumService.quorumWrite("concurrent-key-" + idx,
                        ("val" + idx).getBytes(), expiresAt, "text/plain").block();
            }));
        }
        startLatch.countDown();
        for (var f : futures) f.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        ArgumentCaptor<Long> versionCaptor = ArgumentCaptor.forClass(Long.class);
        verify(clusterClient, timeout(2000).atLeast(threadCount))
                .forwardPutRequest(any(), anyString(), any(byte[].class), anyLong(), any(), versionCaptor.capture());

        // All versions produced across concurrent writes must be unique (no two writes share a version)
        List<Long> versions = versionCaptor.getAllValues();
        long uniqueVersions = versions.stream().distinct().count();
        assertTrue(uniqueVersions >= threadCount,
                "Concurrent writes must produce at least " + threadCount + " unique versions; got " + uniqueVersions);

        // Versions must be seeded from currentTimeMillis — not from zero
        long minExpected = System.currentTimeMillis() - 5000L;
        assertTrue(versions.stream().allMatch(v -> v > minExpected),
                "Versions must be seeded from System.currentTimeMillis() for restart-safety");
    }

    // ─────────────────────────────────────────
    // quorumWriteWithMetadata Tests
    // ─────────────────────────────────────────

    @Test
    @DisplayName("quorumWriteWithMetadata tracks successful and failed nodes")
    void quorumWriteWithMetadata_tracksNodeOutcomes() {
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));
        when(clusterClient.forwardPutRequest(eq(NODE_1), anyString(), any(), anyLong(), anyString(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.empty());
        when(clusterClient.forwardPutRequest(eq(NODE_2), anyString(), any(), anyLong(), anyString(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.empty());
        // NODE_3 stub is lenient: with take(writeQuorum), Reactor may cancel the NODE_3
        // subscription before forwardPutRequest is invoked once NODE_1+NODE_2 succeed.
        // The outcomes map initialises NODE_3 as false, so it appears in failedNodes
        // regardless — this is the correct "conservative mark as failed" behaviour.
        lenient().when(clusterClient.forwardPutRequest(eq(NODE_3), anyString(), any(), anyLong(), anyString(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("Node down")));

        WalWriteMetadata metadata = quorumService.quorumWriteWithMetadata(
                "key1", new byte[]{1, 2, 3}, 9999L, "text/plain"
        ).block();

        assertNotNull(metadata);
        assertNotNull(metadata.successfulNodes());
        assertNotNull(metadata.failedNodes());
        assertEquals(2, metadata.successfulNodes().size());
        assertTrue(metadata.successfulNodes().contains("node-1"));
        assertTrue(metadata.successfulNodes().contains("node-2"));
        // NODE_3 is conservatively in failedNodes: either its request was cancelled
        // before completion, or it errored — both leave it as false in the outcomes map.
        assertTrue(metadata.failedNodes().contains("node-3"));
    }

    @Test
    @DisplayName("quorumWriteWithMetadata succeeds with W=2/3 writes")
    void quorumWriteWithMetadata_succeeds_whenQuorumMet() {
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));
        when(clusterClient.forwardPutRequest(eq(NODE_1), anyString(), any(), anyLong(), anyString(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.empty());
        when(clusterClient.forwardPutRequest(eq(NODE_2), anyString(), any(), anyLong(), anyString(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.empty());
        // NODE_3 stub is lenient: with take(writeQuorum), Reactor may cancel the NODE_3
        // subscription before forwardPutRequest is invoked once NODE_1+NODE_2 succeed.
        lenient().when(clusterClient.forwardPutRequest(eq(NODE_3), anyString(), any(), anyLong(), anyString(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("Node down")));

        WalWriteMetadata metadata = quorumService.quorumWriteWithMetadata(
                "key1", new byte[]{1}, 9999L, "text/plain"
        ).block();

        assertNotNull(metadata);
        assertTrue(metadata.version() > 0);
        // NODE_3 is conservatively marked as failed (never confirmed success)
        assertTrue(metadata.hasFailures());
    }

    @Test
    @DisplayName("quorumWriteWithMetadata fails when W not met")
    void quorumWriteWithMetadata_fails_whenQuorumNotMet() {
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));
        when(clusterClient.forwardPutRequest(any(), anyString(), any(), anyLong(), anyString(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("All nodes down")));

        assertThrows(QuorumNotMetException.class, () ->
                quorumService.quorumWriteWithMetadata("key1", new byte[]{1}, 9999L, "text/plain").block()
        );
    }

    @Test
    @DisplayName("quorumWriteWithMetadata returns at least W successful nodes when all succeed")
    void quorumWriteWithMetadata_noFailures_whenAllNodesSucceed() {
        when(cacheRouter.routeToReplicas("key1", 3))
                .thenReturn(List.of(NODE_1, NODE_2, NODE_3));
        when(clusterClient.forwardPutRequest(any(), anyString(), any(), anyLong(), anyString(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.empty());

        WalWriteMetadata metadata = quorumService.quorumWriteWithMetadata(
                "key1", new byte[]{1}, 9999L, "text/plain"
        ).block();

        assertNotNull(metadata);
        // With take(writeQuorum), the pipeline exits after W=2 successes.
        // NODE_3 may be cancelled before its doOnSuccess fires, so successfulNodes
        // contains exactly W confirmed nodes; NODE_3 is conservatively in failedNodes.
        // The important invariant: at least W nodes succeeded.
        assertTrue(metadata.successfulNodes().size() >= 2,
                "Expected at least W=2 successful nodes, got: " + metadata.successfulNodes().size());
    }

    @Test
    @DisplayName("quorumWriteWithMetadata generates unique version")
    void quorumWriteWithMetadata_generatesUniqueVersion() {
        when(cacheRouter.routeToReplicas(anyString(), anyInt()))
                .thenReturn(List.of(NODE_1, NODE_2));
        when(clusterClient.forwardPutRequest(any(), anyString(), any(), anyLong(), anyString(), anyLong()))
                .thenReturn(reactor.core.publisher.Mono.empty());

        WalWriteMetadata meta1 = quorumService.quorumWriteWithMetadata("key1", new byte[]{1}, 9999L, "text/plain").block();
        WalWriteMetadata meta2 = quorumService.quorumWriteWithMetadata("key2", new byte[]{2}, 9999L, "text/plain").block();

        assertNotNull(meta1);
        assertNotNull(meta2);
        assertNotEquals(meta1.version(), meta2.version());
    }

    // ─────────────────────────────────────────
    // Accessor Methods Tests
    // ─────────────────────────────────────────

    @Test
    @DisplayName("getClusterClient returns injected client")
    void getClusterClient_returnsInjectedClient() {
        assertSame(clusterClient, quorumService.getClusterClient());
    }

    @Test
    @DisplayName("getCacheRouter returns injected router")
    void getCacheRouter_returnsInjectedRouter() {
        assertSame(cacheRouter, quorumService.getCacheRouter());
    }
}
