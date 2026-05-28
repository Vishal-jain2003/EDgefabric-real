package com.edgefabric.caching.controller;

import com.edgefabric.caching.antiEntropy.StaleKeyRegistry;
import com.edgefabric.caching.exception.GlobalExceptionHandler;
import com.edgefabric.caching.service.DrainService;
import com.edgefabric.caching.service.InternalCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for stale key tracking in {@link InternalCacheController}.
 *
 * <p>Verifies that when a PUT fails, the controller marks the key as stale in the registry.</p>
 */
@ExtendWith(MockitoExtension.class)
class InternalCacheControllerStaleTrackingTest {

    private MockMvc mockMvc;

    @Mock
    private InternalCacheService cacheService;

    @Mock
    private DrainService drainService;

    @Mock
    private StaleKeyRegistry staleKeyRegistry;

    @InjectMocks
    private InternalCacheController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── Stale Tracking ────────────────────────────────────────────────────────

    @Test
    void receiveData_whenWriteFails_shouldMarkKeyAsStale() throws Exception {
        // given
        String key = "user:123:profile";
        long version = 100L;
        byte[] data = "test-data".getBytes();

        when(cacheService.storeData(eq(key), any(byte[].class), anyLong(), anyString(), eq(version)))
                .thenThrow(new RuntimeException("Disk full"));

        // when / then
        mockMvc.perform(put("/api/v1/internal/cache/{key}", key)
                        .header("X-Quorum-Version", version)
                        .header("X-Expires-At", System.currentTimeMillis() + 60000)
                        .contentType("application/json")
                        .content(data))
                .andExpect(status().is5xxServerError());

        // verify stale tracking
        verify(staleKeyRegistry, times(1)).markStale(eq(key), eq(version), eq("local_write_failed"));
    }

    @Test
    void receiveData_whenWriteSucceeds_shouldNotMarkAsStale() throws Exception {
        // given
        String key = "user:123:profile";
        long version = 100L;
        byte[] data = "test-data".getBytes();
        long expiresAt = System.currentTimeMillis() + 60000;

        when(cacheService.storeData(eq(key), any(byte[].class), anyLong(), anyString(), eq(version)))
                .thenReturn(expiresAt);

        // when / then
        mockMvc.perform(put("/api/v1/internal/cache/{key}", key)
                        .header("X-Quorum-Version", version)
                        .header("X-Expires-At", expiresAt)
                        .contentType("application/json")
                        .content(data))
                .andExpect(status().isOk());

        // verify no stale tracking
        verify(staleKeyRegistry, never()).markStale(anyString(), anyLong(), anyString());
    }

    @Test
    void receiveData_whenVersionHeaderMissing_shouldNotMarkAsStale() throws Exception {
        // given
        String key = "user:123:profile";
        byte[] data = "test-data".getBytes();

        // when / then
        // Spring returns 500 for MissingRequestHeaderException when caught by GlobalExceptionHandler
        mockMvc.perform(put("/api/v1/internal/cache/{key}", key)
                        .header("X-Expires-At", System.currentTimeMillis() + 60000)
                        .contentType("application/json")
                        .content(data))
                .andExpect(status().is5xxServerError());

        // verify no stale tracking (exception thrown before service call)
        verify(staleKeyRegistry, never()).markStale(anyString(), anyLong(), anyString());
    }

    @Test
    void receiveData_multipleFailures_shouldMarkEachAsStale() throws Exception {
        // given
        String key1 = "key1";
        String key2 = "key2";
        long version1 = 100L;
        long version2 = 200L;
        byte[] data = "test-data".getBytes();

        when(cacheService.storeData(anyString(), any(byte[].class), anyLong(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("Write failed"));

        // when
        mockMvc.perform(put("/api/v1/internal/cache/{key}", key1)
                        .header("X-Quorum-Version", version1)
                        .header("X-Expires-At", System.currentTimeMillis() + 60000)
                        .contentType("application/json")
                        .content(data))
                .andExpect(status().is5xxServerError());

        mockMvc.perform(put("/api/v1/internal/cache/{key}", key2)
                        .header("X-Quorum-Version", version2)
                        .header("X-Expires-At", System.currentTimeMillis() + 60000)
                        .contentType("application/json")
                        .content(data))
                .andExpect(status().is5xxServerError());

        // then
        verify(staleKeyRegistry, times(1)).markStale(eq(key1), eq(version1), eq("local_write_failed"));
        verify(staleKeyRegistry, times(1)).markStale(eq(key2), eq(version2), eq("local_write_failed"));
    }
}
