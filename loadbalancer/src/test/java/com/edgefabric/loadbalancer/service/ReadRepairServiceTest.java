package com.edgefabric.loadbalancer.service;

import com.edgefabric.loadbalancer.client.ClusterClient;
import com.edgefabric.loadbalancer.dto.response.CacheResponse;
import com.edgefabric.loadbalancer.model.CacheNode;
import com.edgefabric.loadbalancer.service.ReadRepairService.StaleReplica;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReadRepairServiceTest {

    @Mock
    private ClusterClient clusterClient;

    private ReadRepairService readRepairService;
    private ExecutorService testExecutor;

    private static final CacheNode NODE_1 = new CacheNode("node-1", "host1", 8081);
    private static final CacheNode NODE_2 = new CacheNode("node-2", "host2", 8082);
    private static final CacheNode NODE_3 = new CacheNode("node-3", "host3", 8083);

    @BeforeEach
    void setUp() {
        // Use a direct (synchronous) executor so async repairs complete before assertions
        testExecutor = Executors.newFixedThreadPool(4);
        readRepairService = new ReadRepairService(clusterClient, testExecutor);
    }

    // ────────────────────────────────────────────────────────────────
    // SCENARIO 1: Consistent replicas — no repair needed
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("No repair issued when all replicas have the same version as the winner")
    void repairStaleReplicas_NoRepairWhenAllConsistent() throws InterruptedException {
        CacheResponse winner = buildResponse("data", 200L, 9999L);

        List<StaleReplica> replicas = List.of(
                new StaleReplica(NODE_1, 200L),
                new StaleReplica(NODE_2, 200L),
                new StaleReplica(NODE_3, 200L)
        );

        readRepairService.repairStaleReplicas("tenant:key", replicas, winner);

        // Allow async tasks to finish
        awaitExecutor();

        verify(clusterClient, never())
                .forwardPutRequest(any(), any(), any(byte[].class), anyLong(), any(), anyLong());
    }

    // ────────────────────────────────────────────────────────────────
    // SCENARIO 2: One stale replica
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Exactly one stale replica is repaired when one node has an older version")
    void repairStaleReplicas_OneStaleReplica() throws InterruptedException {
        CacheResponse winner = buildResponse("fresh", 300L, 9999L);

        List<StaleReplica> replicas = List.of(
                new StaleReplica(NODE_1, 100L),  // stale
                new StaleReplica(NODE_2, 300L),  // up-to-date
                new StaleReplica(NODE_3, 300L)   // up-to-date
        );

        readRepairService.repairStaleReplicas("tenant:key", replicas, winner);

        awaitExecutor();

        // Only NODE_1 should receive a repair write
        verify(clusterClient, times(1))
                .forwardPutRequest(NODE_1, "tenant:key",
                        winner.getData(), winner.getExpiresAt(),
                        winner.getContentType(), winner.getVersion());
        
        verify(clusterClient, never())
                .forwardPutRequest(eq(NODE_2), any(), any(byte[].class), anyLong(), any(), anyLong());
        verify(clusterClient, never())
                .forwardPutRequest(eq(NODE_3), any(), any(byte[].class), anyLong(), any(), anyLong());
    }

    // ────────────────────────────────────────────────────────────────
    // SCENARIO 3: Multiple stale replicas
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("All stale replicas are repaired when multiple nodes have older versions")
    void repairStaleReplicas_MultipleStaleReplicas() throws InterruptedException {
        CacheResponse winner = buildResponse("latest", 500L, 9999L);

        List<StaleReplica> replicas = List.of(
                new StaleReplica(NODE_1, 100L),  // stale
                new StaleReplica(NODE_2, 200L),  // stale
                new StaleReplica(NODE_3, 500L)   // up-to-date (winner)
        );

        readRepairService.repairStaleReplicas("tenant:key", replicas, winner);

        awaitExecutor();

        // NODE_1 and NODE_2 must both be repaired
        verify(clusterClient, times(1))
                .forwardPutRequest(NODE_1, "tenant:key",
                        winner.getData(), winner.getExpiresAt(),
                        winner.getContentType(), winner.getVersion());
        
        verify(clusterClient, times(1))
                .forwardPutRequest(NODE_2, "tenant:key",
                        winner.getData(), winner.getExpiresAt(),
                        winner.getContentType(), winner.getVersion());
        
        verify(clusterClient, never())
                .forwardPutRequest(eq(NODE_3), any(), any(byte[].class), anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("Missing node with version -1 is repaired with winner data")
    void repairStaleReplicas_MissingNodeIsRepaired() throws InterruptedException {

        CacheResponse winner = buildResponse("latest", 500L, 9999L);

        List<StaleReplica> replicas = List.of(
                new StaleReplica(NODE_1, 500L),
                new StaleReplica(NODE_2, 500L),
                new StaleReplica(NODE_3, -1L) // missing replica
        );

        readRepairService.repairStaleReplicas("tenant:key", replicas, winner);

        awaitExecutor();

        verify(clusterClient, times(1))
                .forwardPutRequest(NODE_3,
                        "tenant:key",
                        winner.getData(),
                        winner.getExpiresAt(),
                        winner.getContentType(),
                        winner.getVersion());

        verify(clusterClient, never())
                .forwardPutRequest(eq(NODE_1), any(), any(byte[].class), anyLong(), any(), anyLong());

        verify(clusterClient, never())
                .forwardPutRequest(eq(NODE_2), any(), any(byte[].class), anyLong(), any(), anyLong());
    }

    // ────────────────────────────────────────────────────────────────
    // SCENARIO 4: Repair failure is non-fatal
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Repair failure on one node does not prevent repair of other stale nodes")
    void repairStaleReplicas_RepairFailureIsNonFatal() throws InterruptedException {
        CacheResponse winner = buildResponse("latest", 500L, 9999L);

        List<StaleReplica> replicas = List.of(
                new StaleReplica(NODE_1, 100L),  // stale, will fail
                new StaleReplica(NODE_2, 100L),  // stale, will succeed
                new StaleReplica(NODE_3, 500L)   // up-to-date
        );

        // NODE_1 repair throws an exception
        doThrow(new RuntimeException("Connection refused"))
                .when(clusterClient).forwardPutRequest(eq(NODE_1), any(), any(byte[].class), anyLong(), any(), anyLong());

        // Should not throw — repair failures are fire-and-forget
        readRepairService.repairStaleReplicas("tenant:key", replicas, winner);

        awaitExecutor();

        // Both stale nodes should have been attempted
        verify(clusterClient, times(1))
                .forwardPutRequest(eq(NODE_1), any(), any(byte[].class), anyLong(), any(), anyLong());
        verify(clusterClient, times(1))
                .forwardPutRequest(eq(NODE_2), any(), any(byte[].class), anyLong(), any(), anyLong());
    }

    // ────────────────────────────────────────────────────────────────
    // SCENARIO 5: Empty replica list
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("No repair issued when replica list is empty")
    void repairStaleReplicas_EmptyReplicaList() throws InterruptedException {
        CacheResponse winner = buildResponse("data", 100L, 9999L);

        readRepairService.repairStaleReplicas("tenant:key", List.of(), winner);

        awaitExecutor();

        verify(clusterClient, never())
                .forwardPutRequest(any(), any(), any(byte[].class), anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("Repair is skipped when replica list is null")
    void repairStaleReplicas_NullReplicaListSkipsRepair() throws InterruptedException {

        CacheResponse winner = buildResponse("data", 100L, 9999L);

        readRepairService.repairStaleReplicas("tenant:key", null, winner);

        awaitExecutor();

        verify(clusterClient, never())
                .forwardPutRequest(any(), any(), any(byte[].class), anyLong(), any(), anyLong());
    }



    @Test
    @DisplayName("No repair issued when winner response is null")
    void repairStaleReplicas_WinnerNull() throws InterruptedException {

        readRepairService.repairStaleReplicas("tenant:key", List.of(), null);

        awaitExecutor();

        verify(clusterClient, never())
                .forwardPutRequest(any(), any(), any(byte[].class), anyLong(), any(), anyLong());
    }

    // ────────────────────────────────────────────────────────────────
    // HELPERS
    // ────────────────────────────────────────────────────────────────

    private CacheResponse buildResponse(String data, long version, long expiresAt) {
        return CacheResponse.builder()
                .data(data.getBytes())
                .contentType("text/plain")
                .version(version)
                .expiresAt(expiresAt)
                .build();
    }

    /**
     * Waits for all async repair tasks submitted to the executor to complete
     * before assertions are made.
     */
    private void awaitExecutor() throws InterruptedException {
        testExecutor.shutdown();
        testExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }

}
