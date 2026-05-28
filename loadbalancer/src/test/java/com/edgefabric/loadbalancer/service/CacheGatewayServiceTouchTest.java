package com.edgefabric.loadbalancer.service;

import com.edgefabric.loadbalancer.dto.response.TouchCacheResponse;
import com.edgefabric.loadbalancer.exception.CacheKeyNotFoundException;
import com.edgefabric.loadbalancer.exception.QuorumNotMetException;
import com.edgefabric.loadbalancer.wal.WalWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheGatewayServiceTouchTest {

    @Mock
    private QuorumService quorumService;

    @Mock
    private WalWriter walWriter;

    private CacheGatewayService gatewayService;

    private static final String TENANT = "tenantA";
    private static final String KEY = "user:session:abc";
    private static final long TTL_MS = 3_600_000L;

    @BeforeEach
    void setUp() {
        gatewayService = new CacheGatewayService(quorumService, walWriter);
    }

    // ─── AC2: delegates to quorumTouch with correct tenantKey and computed expiresAt ───

    @Test
    @DisplayName("touch() delegates to quorumService.quorumTouch with tenant-prefixed key")
    void touch_delegatesToQuorumTouch_withCorrectTenantKey() {
        long beforeCall = System.currentTimeMillis();
        ArgumentCaptor<Long> expiresAtCaptor = ArgumentCaptor.forClass(Long.class);

        QuorumService.TouchResult touchResult = new QuorumService.TouchResult(
                TENANT + ":" + KEY, beforeCall + TTL_MS);
        when(quorumService.quorumTouch(eq(TENANT + ":" + KEY), expiresAtCaptor.capture()))
                .thenReturn(Mono.just(touchResult));

        TouchCacheResponse response = gatewayService.touch(TENANT, KEY, TTL_MS);

        long afterCall = System.currentTimeMillis();
        long capturedExpiresAt = expiresAtCaptor.getValue();

        assertThat(capturedExpiresAt).isGreaterThanOrEqualTo(beforeCall + TTL_MS);
        assertThat(capturedExpiresAt).isLessThanOrEqualTo(afterCall + TTL_MS);

        assertThat(response.getKey()).isEqualTo(KEY);
        assertThat(response.getTtlMs()).isEqualTo(TTL_MS);
        assertThat(response.getExpiresAt()).isEqualTo(touchResult.expiresAt());

        verify(quorumService).quorumTouch(eq(TENANT + ":" + KEY), anyLong());
    }

    // ─── AC5: different tenant produces different tenant-prefixed key ───

    @Test
    @DisplayName("AC5: touch() uses X-Tenant prefix in the key forwarded to quorum")
    void touch_usesTenantPrefix_forKeyRouting() {
        String otherTenant = "tenantB";
        long expectedExpiresAt = System.currentTimeMillis() + TTL_MS;

        QuorumService.TouchResult result = new QuorumService.TouchResult(
                otherTenant + ":" + KEY, expectedExpiresAt);
        when(quorumService.quorumTouch(eq(otherTenant + ":" + KEY), anyLong()))
                .thenReturn(Mono.just(result));

        gatewayService.touch(otherTenant, KEY, TTL_MS);

        verify(quorumService).quorumTouch(eq(otherTenant + ":" + KEY), anyLong());
        verify(quorumService, never()).quorumTouch(eq(TENANT + ":" + KEY), anyLong());
    }

    // ─── AC3: propagates CacheKeyNotFoundException from quorum ───

    @Test
    @DisplayName("AC3: touch() propagates CacheKeyNotFoundException when quorum returns not-found")
    void touch_propagatesNotFoundException() {
        when(quorumService.quorumTouch(anyString(), anyLong()))
                .thenReturn(Mono.error(new CacheKeyNotFoundException(KEY)));

        assertThrows(CacheKeyNotFoundException.class,
                () -> gatewayService.touch(TENANT, KEY, TTL_MS));
    }

    // ─── AC6: propagates QuorumNotMetException as-is ───

    @Test
    @DisplayName("AC6: touch() propagates QuorumNotMetException from quorum")
    void touch_propagatesQuorumNotMetException() {
        when(quorumService.quorumTouch(anyString(), anyLong()))
                .thenReturn(Mono.error(new QuorumNotMetException("TOUCH", 2, 0)));

        assertThrows(QuorumNotMetException.class,
                () -> gatewayService.touch(TENANT, KEY, TTL_MS));
    }

    // ─── WAL: touch does NOT write to WAL (TTL-only, value unchanged) ───

    @Test
    @DisplayName("touch() does not append to WAL (TTL-only mutation)")
    void touch_doesNotWriteToWal() {
        long expiresAt = System.currentTimeMillis() + TTL_MS;
        QuorumService.TouchResult result = new QuorumService.TouchResult(TENANT + ":" + KEY, expiresAt);
        when(quorumService.quorumTouch(anyString(), anyLong())).thenReturn(Mono.just(result));

        gatewayService.touch(TENANT, KEY, TTL_MS);

        verify(walWriter, never()).append(any());
    }
}
