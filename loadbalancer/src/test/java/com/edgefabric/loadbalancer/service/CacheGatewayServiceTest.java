package com.edgefabric.loadbalancer.service;

import com.edgefabric.loadbalancer.dto.response.CacheResponse;
import com.edgefabric.loadbalancer.exception.QuorumNotMetException;
import com.edgefabric.loadbalancer.wal.WalEntry;
import com.edgefabric.loadbalancer.wal.WalWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheGatewayServiceTest {

    @Mock
    private QuorumService quorumService;
    @Mock
    private WalWriter walWriter;

    private static final String TENANT = "tenantA";

    // ──────────────────────────────── PUT ──────────────────────────────────────

    @Test
    void put_delegatesToQuorumWrite() {
        var service = new CacheGatewayService(quorumService, walWriter);
        byte[] data = {1, 2, 3};
        long expiresAt = System.currentTimeMillis() + 5000L;

        WalWriteMetadata mockMetadata = new WalWriteMetadata(1L, java.util.Set.of(), java.util.Set.of());
        when(quorumService.quorumWriteWithMetadata(any(), any(), anyLong(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(mockMetadata));

        service.put(TENANT, "test.png", data, expiresAt, "image/png");

        verify(quorumService).quorumWriteWithMetadata(TENANT + ":test.png", data, expiresAt, "image/png");
    }

    @Test
    void put_appendsToWal_afterSuccessfulQuorumWrite() {
        var service = new CacheGatewayService(quorumService, walWriter);
        byte[] data = "hello".getBytes();
        long expiresAt = System.currentTimeMillis() + 5000L;
        ArgumentCaptor<WalEntry> captor = ArgumentCaptor.forClass(WalEntry.class);

        WalWriteMetadata mockMetadata = new WalWriteMetadata(1L, java.util.Set.of("node1"), java.util.Set.of());
        when(quorumService.quorumWriteWithMetadata(any(), any(), anyLong(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(mockMetadata));

        service.put(TENANT, "myKey", data, expiresAt, "text/plain");

        verify(walWriter).append(captor.capture());
        WalEntry entry = captor.getValue();
        assertArrayEquals(data, entry.data());
        assertEquals(expiresAt, entry.expiresAt());
        assertEquals("text/plain", entry.contentType());
        assertEquals(TENANT + ":myKey", entry.key());
    }

    @Test
    void put_doesNotAppendToWal_whenQuorumFails() {
        when(quorumService.quorumWriteWithMetadata(any(), any(), anyLong(), any()))
                .thenReturn(reactor.core.publisher.Mono.error(new QuorumNotMetException("WRITE", 2, 0)));
        var service = new CacheGatewayService(quorumService, walWriter);

        assertThrows(QuorumNotMetException.class,
                () -> service.put(TENANT, "k", new byte[1], System.currentTimeMillis() + 1000L, "text/plain"));

        verify(walWriter, never()).append(any());
    }

    @Test
    void put_failsWithQuorumException_whenQuorumNotMet() {
        when(quorumService.quorumWriteWithMetadata(any(), any(), anyLong(), any()))
                .thenReturn(reactor.core.publisher.Mono.error(new QuorumNotMetException("WRITE", 2, 0)));

        assertThrows(QuorumNotMetException.class,
                () -> new CacheGatewayService(quorumService, walWriter)
                        .put(TENANT, "k", new byte[1], System.currentTimeMillis() + 1000L, "text/plain"));
    }

    @Test
    void put_worksCorrectly_withoutWalWriter() {
        // walWriter = null (bean absent when wal.enabled=false)
        var service = new CacheGatewayService(quorumService, null);

        WalWriteMetadata mockMetadata = new WalWriteMetadata(1L, java.util.Set.of(), java.util.Set.of());
        when(quorumService.quorumWriteWithMetadata(any(), any(), anyLong(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(mockMetadata));

        assertDoesNotThrow(() ->
                service.put(TENANT, "k", "v".getBytes(), System.currentTimeMillis() + 1000L, "text/plain"));

        verify(quorumService).quorumWriteWithMetadata(eq(TENANT + ":k"), any(), anyLong(), any());
        verifyNoInteractions(walWriter);
    }

    @Test
    void put_differentTenants_sameBaseKey_appendDistinctWalEntries() {
        var service = new CacheGatewayService(quorumService, walWriter);
        byte[] data = "v".getBytes();
        long expiresAt = System.currentTimeMillis() + 5000L;
        ArgumentCaptor<WalEntry> captor = ArgumentCaptor.forClass(WalEntry.class);

        WalWriteMetadata mockMetadata = new WalWriteMetadata(1L, java.util.Set.of(), java.util.Set.of());
        when(quorumService.quorumWriteWithMetadata(any(), any(), anyLong(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(mockMetadata));

        service.put("tenantA", "shared-key", data, expiresAt, "text/plain");
        service.put("tenantB", "shared-key", data, expiresAt, "text/plain");

        verify(walWriter, times(2)).append(captor.capture());
        var keys = captor.getAllValues().stream().map(WalEntry::key).toList();
        // Keys must be the composite tenant:baseKey — same base key, different tenants are distinct.
        assertThat(keys).containsExactlyInAnyOrder("tenantA:shared-key", "tenantB:shared-key");
    }

    @Test
    void put_tenantKeyFormat_usesColonSeparator() {
        var service = new CacheGatewayService(quorumService, walWriter);
        ArgumentCaptor<WalEntry> captor = ArgumentCaptor.forClass(WalEntry.class);

        WalWriteMetadata mockMetadata = new WalWriteMetadata(1L, java.util.Set.of(), java.util.Set.of());
        when(quorumService.quorumWriteWithMetadata(any(), any(), anyLong(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(mockMetadata));

        service.put("acme-corp", "img/logo.png", new byte[1], 9_999_999L, "image/png");

        verify(walWriter).append(captor.capture());
        // Confirm the colon separator and exact tenant prefix.
        assertThat(captor.getValue().key()).isEqualTo("acme-corp:img/logo.png");
        assertThat(captor.getValue().key()).startsWith("acme-corp:");
    }

    @Test
    void put_quorumRoutes_usingTenantCompositeKey() {
        var service = new CacheGatewayService(quorumService, walWriter);

        WalWriteMetadata mockMetadata = new WalWriteMetadata(1L, java.util.Set.of(), java.util.Set.of());
        when(quorumService.quorumWriteWithMetadata(any(), any(), anyLong(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(mockMetadata));

        service.put("corp", "data", new byte[1], 9_999_999L, "application/json");

        // The exact same composite key must be passed to both quorum and WAL.
        ArgumentCaptor<String> quorumKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(quorumService).quorumWriteWithMetadata(quorumKeyCaptor.capture(), any(), anyLong(), any());
        ArgumentCaptor<WalEntry> walCaptor = ArgumentCaptor.forClass(WalEntry.class);
        verify(walWriter).append(walCaptor.capture());

        assertThat(quorumKeyCaptor.getValue()).isEqualTo(walCaptor.getValue().key());
    }

    // ──────────────────────────────── GET ──────────────────────────────────────

    @Test
    void get_delegatesToQuorumRead() {
        CacheResponse mock = CacheResponse.builder()
                .data("Hello".getBytes()).contentType("text/plain")
                .version(12345L).expiresAt(System.currentTimeMillis() + 5000L).build();
        when(quorumService.quorumRead(TENANT + ":user_123")).thenReturn(reactor.core.publisher.Mono.just(mock));

        CacheResponse result = new CacheGatewayService(quorumService, walWriter).get(TENANT, "user_123");

        assertEquals("text/plain", result.getContentType());
        assertArrayEquals("Hello".getBytes(), result.getData());
        assertEquals(12345L, result.getVersion());
    }

    @Test
    void get_failsWithQuorumException_whenQuorumNotMet() {
        when(quorumService.quorumRead(any())).thenReturn(reactor.core.publisher.Mono.error(new QuorumNotMetException("READ", 2, 0)));

        assertThrows(QuorumNotMetException.class,
                () -> new CacheGatewayService(quorumService, walWriter).get(TENANT, "k"));
    }

    // ─── NEW test for EPMICMPHE-242: AC10 ───────────────────────────────────

    /**
     * AC10: A WAL append failure must NOT cause the PUT to fail.
     * quorumWrite is still invoked and the method returns normally.
     */
    @Test
    void put_succeedsEvenWhenWalAppendThrows() {
        var service = new CacheGatewayService(quorumService, walWriter);
        byte[] data = "payload".getBytes();
        long expiresAt = System.currentTimeMillis() + 5_000L;

        WalWriteMetadata mockMetadata = new WalWriteMetadata(1L, java.util.Set.of(), java.util.Set.of());
        when(quorumService.quorumWriteWithMetadata(any(), any(), anyLong(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(mockMetadata));
        doThrow(new RuntimeException("WAL disk full")).when(walWriter).append(any());

        // Must not throw — WAL failure is non-fatal
        assertDoesNotThrow(() -> service.put(TENANT, "fault-key", data, expiresAt, "text/plain"));

        // quorumWrite must still have been called (PUT succeeded)
        verify(quorumService).quorumWriteWithMetadata(eq(TENANT + ":fault-key"), eq(data), eq(expiresAt), eq("text/plain"));
    }
}
