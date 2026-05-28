package com.edgefabric.caching.controller;

import com.edgefabric.caching.exception.CacheExpiredException;
import com.edgefabric.caching.exception.CacheNotFoundException;
import com.edgefabric.caching.exception.GlobalExceptionHandler;
import com.edgefabric.caching.service.DrainService;
import com.edgefabric.caching.service.InternalCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class InternalCacheControllerTouchTest {

    private MockMvc mockMvc;

    @Mock
    private InternalCacheService cacheService;

    @Mock
    private DrainService drainService;

    @InjectMocks
    private InternalCacheController controller;

    private static final String KEY = "user:session:abc";
    private static final long EXPIRES_AT = System.currentTimeMillis() + 3_600_000L;
    private static final long VERSION = 99_999L;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ─── Happy path: valid headers → 200 with updated expiresAt ───

    @Test
    @DisplayName("PATCH /touch with valid headers returns 200 and updated expiresAt")
    void touch_happyPath_returns200() throws Exception {
        when(drainService.isDraining()).thenReturn(false);
        when(cacheService.touch(eq(KEY), eq(EXPIRES_AT), eq(VERSION))).thenReturn(EXPIRES_AT);

        mockMvc.perform(patch("/api/v1/internal/cache/{key}/touch", KEY)
                        .header("X-Expires-At", EXPIRES_AT)
                        .header("X-Quorum-Version", VERSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(KEY))
                .andExpect(jsonPath("$.expiresAt").value(EXPIRES_AT));

        verify(cacheService).touch(KEY, EXPIRES_AT, VERSION);
    }

    // ─── AC3: Key not found → 404 ───

    @Test
    @DisplayName("AC3: Returns 404 when key does not exist on this node")
    void touch_keyNotFound_returns404() throws Exception {
        when(drainService.isDraining()).thenReturn(false);
        when(cacheService.touch(eq(KEY), anyLong(), anyLong()))
                .thenThrow(new CacheNotFoundException("Cache not found for key: " + KEY));

        mockMvc.perform(patch("/api/v1/internal/cache/{key}/touch", KEY)
                        .header("X-Expires-At", EXPIRES_AT)
                        .header("X-Quorum-Version", VERSION))
                .andExpect(status().isNotFound());
    }

    // ─── AC3: Key present but expired → 410 (Gone) on node side ───

    @Test
    @DisplayName("AC3: Returns 410 when key is expired on this node")
    void touch_keyExpired_returns410() throws Exception {
        when(drainService.isDraining()).thenReturn(false);
        when(cacheService.touch(eq(KEY), anyLong(), anyLong()))
                .thenThrow(new CacheExpiredException("Cache expired for key: " + KEY));

        mockMvc.perform(patch("/api/v1/internal/cache/{key}/touch", KEY)
                        .header("X-Expires-At", EXPIRES_AT)
                        .header("X-Quorum-Version", VERSION))
                .andExpect(status().isGone());
    }

    // ─── Node draining (grace period active) → touch still succeeds ───

    @Test
    @DisplayName("Touch proceeds when node is draining within grace period")
    void touch_nodeInDrainingGracePeriod_returns200() throws Exception {
        when(drainService.isDraining()).thenReturn(true);
        when(drainService.isDrainingWithinGracePeriod()).thenReturn(true);
        when(cacheService.touch(eq(KEY), eq(EXPIRES_AT), eq(VERSION))).thenReturn(EXPIRES_AT);

        mockMvc.perform(patch("/api/v1/internal/cache/{key}/touch", KEY)
                        .header("X-Expires-At", EXPIRES_AT)
                        .header("X-Quorum-Version", VERSION))
                .andExpect(status().isOk());
    }

    // ─── Node draining (grace period expired) → 503 ───

    @Test
    @DisplayName("Touch returns 503 when node is draining with expired grace period")
    void touch_nodeInDrainingGracePeriodExpired_returns503() throws Exception {
        when(drainService.isDraining()).thenReturn(true);
        when(drainService.isDrainingWithinGracePeriod()).thenReturn(false);

        mockMvc.perform(patch("/api/v1/internal/cache/{key}/touch", KEY)
                        .header("X-Expires-At", EXPIRES_AT)
                        .header("X-Quorum-Version", VERSION))
                .andExpect(status().isServiceUnavailable());

        verify(cacheService, never()).touch(any(), anyLong(), anyLong());
    }
}
