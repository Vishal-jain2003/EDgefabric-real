package com.edgefabric.loadbalancer.controller;

import com.edgefabric.loadbalancer.wal.WalEntry;
import com.edgefabric.loadbalancer.wal.WalWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalControllerTest {

    @Mock
    private WalWriter walWriter;

    @Mock
    private com.edgefabric.loadbalancer.service.QuorumService quorumService;

    @InjectMocks
    private WalController walController;

    @Test
    void triggerReplay_returnsSuccessAndCountsEntries() {
        // Arrange — quorumWrite must return Mono.empty() so .block() does not NPE
        when(quorumService.quorumWrite(anyString(), any(), anyLong(), anyString()))
                .thenReturn(Mono.empty());
        doAnswer(invocation -> {
            Consumer<WalEntry> handler = invocation.getArgument(0);
            // Use far-future expiresAt so entries are NOT considered expired
            long future = System.currentTimeMillis() + 60_000L;
            handler.accept(WalEntry.forPut("k1", "data1".getBytes(), future, "text/plain"));
            handler.accept(WalEntry.forPut("k2", "data2".getBytes(), future, "application/json"));
            return null;
        }).when(walWriter).replay(any());

        // Act
        ResponseEntity<String> response = walController.triggerReplay();

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Total entries replayed: 2");
        verify(walWriter, times(1)).replay(any());
        verify(quorumService, times(1)).quorumWrite(eq("k1"), any(byte[].class), anyLong(), eq("text/plain"));
        verify(quorumService, times(1)).quorumWrite(eq("k2"), any(byte[].class), anyLong(), eq("application/json"));
    }

    @Test
    void triggerReplay_handlesExceptionsGracefully() {
        // Arrange
        doThrow(new RuntimeException("Simulated failure")).when(walWriter).replay(any());

        // Act
        ResponseEntity<String> response = walController.triggerReplay();

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).contains("Replay failed: Simulated failure");
    }

    @Test
    void triggerReplay_continuesWhenQuorumWriteFails() {
        // Arrange — k1 throws, k2 must still be attempted
        long future = System.currentTimeMillis() + 60_000L;
        doAnswer(invocation -> {
            Consumer<WalEntry> handler = invocation.getArgument(0);
            handler.accept(WalEntry.forPut("k1", "data1".getBytes(), future, "text/plain"));
            handler.accept(WalEntry.forPut("k2", "data2".getBytes(), future, "application/json"));
            return null;
        }).when(walWriter).replay(any());

        when(quorumService.quorumWrite(eq("k1"), any(), anyLong(), anyString()))
                .thenThrow(new RuntimeException("Simulated quorum failure"));
        when(quorumService.quorumWrite(eq("k2"), any(), anyLong(), anyString()))
                .thenReturn(Mono.empty());

        // Act
        ResponseEntity<String> response = walController.triggerReplay();

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Total entries replayed: 2");
        verify(quorumService, times(1)).quorumWrite(eq("k1"), any(), anyLong(), eq("text/plain"));
        verify(quorumService, times(1)).quorumWrite(eq("k2"), any(), anyLong(), eq("application/json"));
    }

    @Test
    void triggerReplay_handlesDeleteGracefully() {
        // Arrange
        doAnswer(invocation -> {
            Consumer<WalEntry> handler = invocation.getArgument(0);
            handler.accept(new WalEntry("k1", null, 0, null, 0L, com.edgefabric.loadbalancer.wal.OperationType.DELETE, System.currentTimeMillis(), java.util.Set.of(), java.util.Set.of()));
            return null;
        }).when(walWriter).replay(any());

        // Act
        ResponseEntity<String> response = walController.triggerReplay();

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Total entries replayed: 1");
        verify(walWriter, times(1)).replay(any());
        verify(quorumService, never()).quorumWrite(anyString(), any(), anyLong(), anyString());
    }

    // ─── NEW tests for EPMICMPHE-242 ────────────────────────────────────────

    /**
     * AC2: Expired entries must be silently skipped and NOT forwarded to quorumWrite.
     * The response must note the skipped count.
     */
    @Test
    void triggerReplay_skipsExpiredEntries() {
        // Arrange — expiresAt is 1 second in the past
        long expired = System.currentTimeMillis() - 1_000L;
        doAnswer(invocation -> {
            Consumer<WalEntry> handler = invocation.getArgument(0);
            handler.accept(WalEntry.forPut("expired-key", "data".getBytes(), expired, "text/plain"));
            return null;
        }).when(walWriter).replay(any());

        // Act
        ResponseEntity<String> response = walController.triggerReplay();

        // Assert — quorumWrite must NOT have been called for the expired entry
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(quorumService, never()).quorumWrite(anyString(), any(), anyLong(), anyString());
        assertThat(response.getBody()).contains("skipped expired: 1");
    }

    /**
     * AC1 + AC2: Mixed batch — 2 non-expired + 1 expired.
     * quorumWrite called exactly 2 times, response mentions both counts.
     */
    @Test
    void triggerReplay_countReplayedAndSkipped() {
        when(quorumService.quorumWrite(anyString(), any(), anyLong(), anyString()))
                .thenReturn(Mono.empty());

        long future  = System.currentTimeMillis() + 60_000L;
        long expired = System.currentTimeMillis() - 1_000L;

        doAnswer(invocation -> {
            Consumer<WalEntry> handler = invocation.getArgument(0);
            handler.accept(WalEntry.forPut("alive1", "d1".getBytes(), future,  "text/plain"));
            handler.accept(WalEntry.forPut("alive2", "d2".getBytes(), future,  "text/plain"));
            handler.accept(WalEntry.forPut("dead1",  "d3".getBytes(), expired, "text/plain"));
            return null;
        }).when(walWriter).replay(any());

        // Act
        ResponseEntity<String> response = walController.triggerReplay();

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(quorumService, times(2)).quorumWrite(anyString(), any(), anyLong(), anyString());
        assertThat(response.getBody()).contains("replayed: 2");
        assertThat(response.getBody()).contains("skipped");
    }

    /**
     * AC9: Empty WAL returns a clean "0 entries replayed" with no exception.
     */
    @Test
    void triggerReplay_zeroEntriesReturnsCleanResponse() {
        // Arrange — replay consumer is never invoked
        doAnswer(invocation -> null).when(walWriter).replay(any());

        // Act
        ResponseEntity<String> response = walController.triggerReplay();

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("replayed: 0");
        verify(quorumService, never()).quorumWrite(anyString(), any(), anyLong(), anyString());
    }

    // ──────────────────────────────── REPLAY STALE ENTRIES ────────────────────────────────

    @Test
    void replayStaleEntries_repairsOnlyFailedNodes() {
        com.edgefabric.loadbalancer.model.CacheNode failedNode =
                new com.edgefabric.loadbalancer.model.CacheNode("node-2", "10.0.0.2", 8082);
        when(quorumService.getCacheRouter()).thenReturn(mock(com.edgefabric.loadbalancer.service.CacheRouter.class));
        when(quorumService.getCacheRouter().getNodeById("node-2")).thenReturn(failedNode);
        when(quorumService.getClusterClient()).thenReturn(mock(com.edgefabric.loadbalancer.client.ClusterClient.class));
        when(quorumService.getClusterClient().forwardPutRequest(any(), anyString(), any(), anyLong(), anyString(), anyLong()))
                .thenReturn(Mono.empty());

        doAnswer(invocation -> {
            Consumer<WalEntry> handler = invocation.getArgument(0);
            long future = System.currentTimeMillis() + 60_000L;
            WalEntry staleEntry = new WalEntry("key1", "data".getBytes(), future, "text/plain", 1L,
                    com.edgefabric.loadbalancer.wal.OperationType.PUT, System.currentTimeMillis(),
                    java.util.Set.of("node-1"), java.util.Set.of("node-2"));
            handler.accept(staleEntry);
            return null;
        }).when(walWriter).replay(any());

        ResponseEntity<String> response = walController.replayStaleEntries();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Repaired:");
        // Give virtual threads time to complete
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        verify(quorumService.getClusterClient()).forwardPutRequest(eq(failedNode), eq("key1"), any(), anyLong(), eq("text/plain"), eq(1L));
    }

    @Test
    void replayStaleEntries_skipsHealthyEntries() {
        doAnswer(invocation -> {
            Consumer<WalEntry> handler = invocation.getArgument(0);
            long future = System.currentTimeMillis() + 60_000L;
            WalEntry healthyEntry = new WalEntry("key1", "data".getBytes(), future, "text/plain", 1L,
                    com.edgefabric.loadbalancer.wal.OperationType.PUT, System.currentTimeMillis(),
                    java.util.Set.of("node-1", "node-2"), java.util.Set.of());
            handler.accept(healthyEntry);
            return null;
        }).when(walWriter).replay(any());

        ResponseEntity<String> response = walController.replayStaleEntries();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Skipped:");
        verifyNoInteractions(quorumService);
    }

    @Test
    void replayStaleEntries_skipsExpiredEntries() {
        long expired = System.currentTimeMillis() - 1000;
        doAnswer(invocation -> {
            Consumer<WalEntry> handler = invocation.getArgument(0);
            WalEntry expiredEntry = new WalEntry("key1", "data".getBytes(), expired, "text/plain", 1L,
                    com.edgefabric.loadbalancer.wal.OperationType.PUT, System.currentTimeMillis(),
                    java.util.Set.of("node-1"), java.util.Set.of("node-2"));
            handler.accept(expiredEntry);
            return null;
        }).when(walWriter).replay(any());

        ResponseEntity<String> response = walController.replayStaleEntries();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Skipped:");
        verifyNoInteractions(quorumService);
    }

    @Test
    void replayStaleEntries_handlesNodeNotFound() {
        when(quorumService.getCacheRouter()).thenReturn(mock(com.edgefabric.loadbalancer.service.CacheRouter.class));
        when(quorumService.getCacheRouter().getNodeById("node-2")).thenReturn(null);

        doAnswer(invocation -> {
            Consumer<WalEntry> handler = invocation.getArgument(0);
            long future = System.currentTimeMillis() + 60_000L;
            WalEntry staleEntry = new WalEntry("key1", "data".getBytes(), future, "text/plain", 1L,
                    com.edgefabric.loadbalancer.wal.OperationType.PUT, System.currentTimeMillis(),
                    java.util.Set.of("node-1"), java.util.Set.of("node-2"));
            handler.accept(staleEntry);
            return null;
        }).when(walWriter).replay(any());

        ResponseEntity<String> response = walController.replayStaleEntries();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Failed:");
    }

    @Test
    void replayStaleEntries_handlesRepairFailure() {
        com.edgefabric.loadbalancer.model.CacheNode failedNode =
                new com.edgefabric.loadbalancer.model.CacheNode("node-2", "10.0.0.2", 8082);
        when(quorumService.getCacheRouter()).thenReturn(mock(com.edgefabric.loadbalancer.service.CacheRouter.class));
        when(quorumService.getCacheRouter().getNodeById("node-2")).thenReturn(failedNode);
        when(quorumService.getClusterClient()).thenReturn(mock(com.edgefabric.loadbalancer.client.ClusterClient.class));
        when(quorumService.getClusterClient().forwardPutRequest(any(), anyString(), any(), anyLong(), anyString(), anyLong()))
                .thenReturn(Mono.error(new RuntimeException("Connection refused")));

        doAnswer(invocation -> {
            Consumer<WalEntry> handler = invocation.getArgument(0);
            long future = System.currentTimeMillis() + 60_000L;
            WalEntry staleEntry = new WalEntry("key1", "data".getBytes(), future, "text/plain", 1L,
                    com.edgefabric.loadbalancer.wal.OperationType.PUT, System.currentTimeMillis(),
                    java.util.Set.of("node-1"), java.util.Set.of("node-2"));
            handler.accept(staleEntry);
            return null;
        }).when(walWriter).replay(any());

        ResponseEntity<String> response = walController.replayStaleEntries();

        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Failed:");
    }

    @Test
    void replayStaleEntries_handlesException() {
        doThrow(new RuntimeException("WAL replay failed")).when(walWriter).replay(any());

        ResponseEntity<String> response = walController.replayStaleEntries();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).contains("failed");
    }
}
