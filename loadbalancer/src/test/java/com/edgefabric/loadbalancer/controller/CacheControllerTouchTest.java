package com.edgefabric.loadbalancer.controller;

import com.edgefabric.loadbalancer.config.CacheProperties;
import com.edgefabric.loadbalancer.dto.response.TouchCacheResponse;
import com.edgefabric.loadbalancer.exception.CacheKeyNotFoundException;
import com.edgefabric.loadbalancer.exception.GlobalExceptionHandler;
import com.edgefabric.loadbalancer.exception.QuorumNotMetException;
import com.edgefabric.loadbalancer.service.CacheGatewayService;
import com.edgefabric.loadbalancer.validation.CacheEntryValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CacheController.class)
@Import(GlobalExceptionHandler.class)
class CacheControllerTouchTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CacheGatewayService gatewayService;

    @MockitoBean
    private CacheEntryValidator cacheEntryValidator;

    @MockitoBean
    private CacheProperties cacheProperties;

    private static final String KEY = "user:session:abc123";
    private static final String TENANT = "tenantA";
    private static final long TTL_MS = 3_600_000L;

    // ─── AC1 + AC7: Happy path returns 200 with correct response body ───

    @Test
    @DisplayName("AC1+AC7: POST /touch with valid ttl returns 200 and TouchCacheResponse")
    void touch_happyPath_returns200() throws Exception {
        long expectedExpiresAt = System.currentTimeMillis() + TTL_MS;
        TouchCacheResponse response = TouchCacheResponse.builder()
                .key(KEY)
                .expiresAt(expectedExpiresAt)
                .ttlMs(TTL_MS)
                .build();

        when(gatewayService.touch(eq(TENANT), eq(KEY), eq(TTL_MS))).thenReturn(response);

        mockMvc.perform(post("/api/v1/cache/{key}/touch", KEY)
                        .param("ttl", String.valueOf(TTL_MS))
                        .header("X-Tenant", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(KEY))
                .andExpect(jsonPath("$.expiresAt").value(expectedExpiresAt))
                .andExpect(jsonPath("$.ttlMs").value(TTL_MS));

        verify(gatewayService).touch(TENANT, KEY, TTL_MS);
    }

    // ─── AC5: X-Tenant defaults to "default" when header absent ───

    @Test
    @DisplayName("AC5: Uses default tenant when X-Tenant header is absent")
    void touch_defaultTenant_whenHeaderAbsent() throws Exception {
        long expectedExpiresAt = System.currentTimeMillis() + TTL_MS;
        TouchCacheResponse response = TouchCacheResponse.builder()
                .key(KEY)
                .expiresAt(expectedExpiresAt)
                .ttlMs(TTL_MS)
                .build();

        when(gatewayService.touch(eq("default"), eq(KEY), eq(TTL_MS))).thenReturn(response);

        mockMvc.perform(post("/api/v1/cache/{key}/touch", KEY)
                        .param("ttl", String.valueOf(TTL_MS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(KEY));

        verify(gatewayService).touch("default", KEY, TTL_MS);
    }

    // ─── AC4: ttl = 0 → 400 ───

    @Test
    @DisplayName("AC4: ttl=0 returns 400 Bad Request")
    void touch_zeroTtl_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/cache/{key}/touch", KEY)
                        .param("ttl", "0")
                        .header("X-Tenant", TENANT))
                .andExpect(status().isBadRequest());
    }

    // ─── AC4: ttl < 0 → 400 ───

    @Test
    @DisplayName("AC4: negative ttl returns 400 Bad Request")
    void touch_negativeTtl_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/cache/{key}/touch", KEY)
                        .param("ttl", "-1")
                        .header("X-Tenant", TENANT))
                .andExpect(status().isBadRequest());
    }

    // ─── AC4: Large TTL values are now accepted (24h limit removed) ───

    @Test
    @DisplayName("AC4: Large TTL values (>24h) are accepted")
    void touch_largeTtl_returns200() throws Exception {
        long largeTtl = 172_800_000L; // 48 hours
        long expectedExpiresAt = System.currentTimeMillis() + largeTtl;
        TouchCacheResponse response = TouchCacheResponse.builder()
                .key(KEY)
                .expiresAt(expectedExpiresAt)
                .ttlMs(largeTtl)
                .build();

        when(gatewayService.touch(any(), eq(KEY), eq(largeTtl))).thenReturn(response);

        mockMvc.perform(post("/api/v1/cache/{key}/touch", KEY)
                        .param("ttl", String.valueOf(largeTtl))
                        .header("X-Tenant", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttlMs").value(largeTtl));
    }

    // ─── AC3: Key not found → 404 ───

    @Test
    @DisplayName("AC3: Returns 404 when key does not exist")
    void touch_keyNotFound_returns404() throws Exception {
        when(gatewayService.touch(any(), eq(KEY), eq(TTL_MS)))
                .thenThrow(new CacheKeyNotFoundException(KEY));

        mockMvc.perform(post("/api/v1/cache/{key}/touch", KEY)
                        .param("ttl", String.valueOf(TTL_MS))
                        .header("X-Tenant", TENANT))
                .andExpect(status().isNotFound());
    }

    // ─── AC6: Quorum not reachable → 503 ───

    @Test
    @DisplayName("AC6: Returns 503 when quorum not reachable")
    void touch_quorumNotMet_returns503() throws Exception {
        when(gatewayService.touch(any(), eq(KEY), eq(TTL_MS)))
                .thenThrow(new QuorumNotMetException("TOUCH", 2, 0));

        mockMvc.perform(post("/api/v1/cache/{key}/touch", KEY)
                        .param("ttl", String.valueOf(TTL_MS))
                        .header("X-Tenant", TENANT))
                .andExpect(status().isServiceUnavailable());
    }

    // ─── AC3: Key expired → 404 (expired treated same as not-found per AC3) ───

    @Test
    @DisplayName("AC3: Returns 404 when key has expired")
    void touch_keyExpired_returns404() throws Exception {
        when(gatewayService.touch(any(), eq(KEY), eq(TTL_MS)))
                .thenThrow(new CacheKeyNotFoundException(KEY + " (expired)"));

        mockMvc.perform(post("/api/v1/cache/{key}/touch", KEY)
                        .param("ttl", String.valueOf(TTL_MS))
                        .header("X-Tenant", TENANT))
                .andExpect(status().isNotFound());
    }

    // ─── AC4: ttl of 24h (previously max boundary) still works ───

    @Test
    @DisplayName("AC4: ttl of 86400000 (24h) is accepted")
    void touch_ttl24h_returns200() throws Exception {
        long ttl24h = 86_400_000L;
        long expectedExpiresAt = System.currentTimeMillis() + ttl24h;
        TouchCacheResponse response = TouchCacheResponse.builder()
                .key(KEY)
                .expiresAt(expectedExpiresAt)
                .ttlMs(ttl24h)
                .build();

        when(gatewayService.touch(any(), eq(KEY), eq(ttl24h))).thenReturn(response);

        mockMvc.perform(post("/api/v1/cache/{key}/touch", KEY)
                        .param("ttl", String.valueOf(ttl24h))
                        .header("X-Tenant", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttlMs").value(ttl24h));
    }
}
