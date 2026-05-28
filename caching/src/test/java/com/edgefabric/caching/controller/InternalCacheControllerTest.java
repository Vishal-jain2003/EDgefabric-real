package com.edgefabric.caching.controller;

import com.edgefabric.caching.client.WalClient;
import com.edgefabric.caching.exception.CacheNotFoundException;
import com.edgefabric.caching.exception.GlobalExceptionHandler;
import com.edgefabric.caching.exception.MissingVersionException;
import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.service.DrainService;
import com.edgefabric.caching.service.InternalCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class InternalCacheControllerTest {

    private MockMvc mockMvc;

    @Mock
    private InternalCacheService cacheService;

    @Mock
    private DrainService drainService;

    @Mock
    private com.edgefabric.caching.antiEntropy.StaleKeyRegistry staleKeyRegistry;

    @Mock
    private WalClient walClient;

    @InjectMocks
    private InternalCacheController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // --- PUT Tests ---

    @Test
    void testReceiveDataSuccessWithAbsoluteExpiry() throws Exception {
        String key = "testKey";
        byte[] data = "Hello World".getBytes();
        long expiresAt = System.currentTimeMillis() + 5000L;
        long version = 12345L;

        Mockito.when(cacheService.storeData(key, data, expiresAt, "text/plain", version)).thenReturn(expiresAt);

        mockMvc.perform(put("/api/v1/internal/cache/{key}", key)
                        .content(data)
                        .header("X-Expires-At", expiresAt)
                        .header("X-Quorum-Version", version)
                        .header("Content-Type", "text/plain"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Expires-At", String.valueOf(expiresAt)))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Entry created successfully."))
                .andExpect(jsonPath("$.key").value(key));

        Mockito.verify(cacheService).storeData(key, data, expiresAt, "text/plain", version);
    }

    @Test
    void testReceiveDataWithTtlFallbackComputesExpiryOnceAtIngress() throws Exception {
        String key = "ttlKey";
        byte[] data = "Hello World".getBytes();
        long ttl = 5000L;
        long version = 12345L;

        Mockito.when(cacheService.storeData(Mockito.eq(key), Mockito.eq(data), Mockito.anyLong(), Mockito.eq("text/plain"), Mockito.eq(version)))
                .thenAnswer(invocation -> invocation.getArgument(2));

        mockMvc.perform(put("/api/v1/internal/cache/{key}", key)
                        .content(data)
                        .header("X-TTL-MS", ttl)
                        .header("X-Quorum-Version", version)
                        .header("Content-Type", "text/plain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<Long> expiresAtCaptor = ArgumentCaptor.forClass(Long.class);
        Mockito.verify(cacheService).storeData(Mockito.eq(key), Mockito.eq(data), expiresAtCaptor.capture(), Mockito.eq("text/plain"), Mockito.eq(version));

        long forwardedExpiresAt = expiresAtCaptor.getValue();
        long now = System.currentTimeMillis();
        assertTrue(forwardedExpiresAt >= now + ttl - 1000L);
        assertTrue(forwardedExpiresAt <= now + ttl + 1000L);
    }

    @Test
    void testReceiveDataWithLargeExpiry() throws Exception {
        String key = "longKey";
        byte[] data = "Hello World".getBytes();
        long requestedExpiresAt = System.currentTimeMillis() + 100000000L; // ~27 hours
        long version = 99999L;

        Mockito.when(cacheService.storeData(key, data, requestedExpiresAt, "text/plain", version)).thenReturn(requestedExpiresAt);

        mockMvc.perform(put("/api/v1/internal/cache/{key}", key)
                        .content(data)
                        .header("X-Expires-At", requestedExpiresAt)
                        .header("X-Quorum-Version", version)
                        .header("Content-Type", "text/plain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("Entry created successfully."));
    }

    @Test
    void testReceiveData_MissingVersionHeader_ReturnsBadRequest() throws Exception {
        String key = "testKey";

        Mockito.when(cacheService.storeData(Mockito.eq(key), Mockito.any(), Mockito.anyLong(),
                        Mockito.anyString(), Mockito.eq(0L)))
                .thenThrow(new MissingVersionException(key));

        mockMvc.perform(put("/api/v1/internal/cache/{key}", key)
                        .content("data".getBytes())
                        .header("X-Quorum-Version", 0L)
                        .header("Content-Type", "text/plain"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void testReceiveDataWithoutKey() throws Exception {
        mockMvc.perform(put("/api/v1/internal/cache/")
                        .content("test".getBytes()))
                .andExpect(status().is4xxClientError());
    }

    // --- GET Tests ---

    @Test
    void testGetDataSuccess() throws Exception {
        String key = "testKey";
        byte[] data = "Hello World".getBytes();
        String contentType = "text/plain";
        long expiresAt = System.currentTimeMillis() + 5000L;
        CacheItem mockItem = new CacheItem(data, expiresAt, contentType, 12345L, true);

        Mockito.when(cacheService.get(key)).thenReturn(mockItem);

        mockMvc.perform(get("/api/v1/internal/cache/{key}", key))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", contentType))
                .andExpect(header().string("X-Quorum-Version", "12345"))
                .andExpect(header().string("X-Expires-At", String.valueOf(expiresAt)))
                .andExpect(content().bytes(data));
    }

    @Test
    void testGetDataNotFound() throws Exception {
        String key = "missingKey";

        Mockito.when(cacheService.get(key))
                .thenThrow(new CacheNotFoundException("Cache not found for key: " + key));

        mockMvc.perform(get("/api/v1/internal/cache/{key}", key))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Cache not found for key: " + key));
    }

    // --- Drain Tests ---

    @Test
    void testPutReturns503WhenDrainingAndGracePeriodExpired() throws Exception {
        Mockito.when(drainService.isDraining()).thenReturn(true);
        Mockito.when(drainService.isDrainingWithinGracePeriod()).thenReturn(false);

        mockMvc.perform(put("/api/v1/internal/cache/{key}", "testKey")
                        .content("data".getBytes())
                        .header("X-Quorum-Version", 1L)
                        .header("Content-Type", "text/plain"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Node is draining - grace period expired, writes rejected"));

        Mockito.verify(cacheService, Mockito.never()).storeData(
                Mockito.anyString(), Mockito.any(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyLong());
    }

    @Test
    void testPutAcceptsInFlightRequestsDuringGracePeriod() throws Exception {
        String key = "testKey";
        byte[] data = "data".getBytes();
        long expiresAt = System.currentTimeMillis() + 5000L;
        long version = 1L;

        Mockito.when(drainService.isDraining()).thenReturn(true);
        Mockito.when(drainService.isDrainingWithinGracePeriod()).thenReturn(true);
        Mockito.when(cacheService.storeData(key, data, expiresAt, "text/plain", version)).thenReturn(expiresAt);

        mockMvc.perform(put("/api/v1/internal/cache/{key}", key)
                        .content(data)
                        .header("X-Expires-At", expiresAt)
                        .header("X-Quorum-Version", version)
                        .header("Content-Type", "text/plain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Mockito.verify(cacheService).storeData(key, data, expiresAt, "text/plain", version);
    }

    @Test
    void testGetReturns503WhenDrainingAndGracePeriodExpired() throws Exception {
        Mockito.when(drainService.isDraining()).thenReturn(true);
        Mockito.when(drainService.isDrainingWithinGracePeriod()).thenReturn(false);

        mockMvc.perform(get("/api/v1/internal/cache/{key}", "testKey"))
                .andExpect(status().isServiceUnavailable());

        Mockito.verify(cacheService, Mockito.never()).get(Mockito.anyString());
    }

    @Test
    void testGetAcceptsInFlightRequestsDuringGracePeriod() throws Exception {
        String key = "testKey";
        byte[] data = "Hello".getBytes();
        CacheItem mockItem = new CacheItem(data, System.currentTimeMillis() + 5000L, "text/plain", 1L, true);

        Mockito.when(drainService.isDraining()).thenReturn(true);
        Mockito.when(drainService.isDrainingWithinGracePeriod()).thenReturn(true);
        Mockito.when(cacheService.get(key)).thenReturn(mockItem);

        mockMvc.perform(get("/api/v1/internal/cache/{key}", key))
                .andExpect(status().isOk())
                .andExpect(content().bytes(data));

        Mockito.verify(cacheService).get(key);
    }

    // ── NEW: Header precedence and default TTL behaviour ───────────────────────

    @Test
    void testReceiveData_BothExpiresAtAndTtl_ExpiresAtTakesPrecedence() throws Exception {
        // When both X-Expires-At and X-TTL-MS are supplied, the absolute expiry wins.
        String key = "testKey";
        byte[] data = "data".getBytes();
        long expiresAt = System.currentTimeMillis() + 10_000L;
        long version  = 1L;

        Mockito.when(cacheService.storeData(key, data, expiresAt, "text/plain", version))
                .thenReturn(expiresAt);

        mockMvc.perform(put("/api/v1/internal/cache/{key}", key)
                        .content(data)
                        .header("X-Expires-At",     expiresAt)
                        .header("X-TTL-MS",         99_999L)   // must be ignored
                        .header("X-Quorum-Version", version)
                        .header("Content-Type",     "text/plain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // storeData must have been called with the X-Expires-At value, not a TTL-derived one
        Mockito.verify(cacheService).storeData(key, data, expiresAt, "text/plain", version);
    }

    @Test
    void testReceiveData_NeitherExpiresAtNorTtl_DefaultTtl60000Applied() throws Exception {
        // When neither header is present the controller falls back to 60 000 ms TTL.
        String key     = "defaultTtlKey";
        byte[] data    = "data".getBytes();
        long   version = 1L;

        Mockito.when(cacheService.storeData(
                        Mockito.eq(key), Mockito.eq(data), Mockito.anyLong(),
                        Mockito.eq("text/plain"), Mockito.eq(version)))
                .thenAnswer(invocation -> invocation.getArgument(2));

        mockMvc.perform(put("/api/v1/internal/cache/{key}", key)
                        .content(data)
                        .header("X-Quorum-Version", version)
                        .header("Content-Type",     "text/plain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<Long> expiresAtCaptor = ArgumentCaptor.forClass(Long.class);
        Mockito.verify(cacheService).storeData(
                Mockito.eq(key), Mockito.eq(data), expiresAtCaptor.capture(),
                Mockito.eq("text/plain"), Mockito.eq(version));

        long forwardedExpiresAt = expiresAtCaptor.getValue();
        long now = System.currentTimeMillis();
        // Default TTL is 60 000 ms — allow ±1 s for test execution time
        assertThat(forwardedExpiresAt).isBetween(now + 59_000L, now + 61_000L);
    }

    // ── WAL journal tests (EPMICMPHE-246) ─────────────────────────────────────

    /**
     * AC: after a successful storeData, walClient.appendAsync must be invoked
     * exactly once with the key, data, expiresAt, contentType, and version.
     */
    @Test
    void receiveData_successfulPut_invokesWalClientAppendAsyncOnce() throws Exception {
        String key      = "wal-key";
        byte[] data     = "payload".getBytes();
        long expiresAt  = System.currentTimeMillis() + 60_000L;
        long version    = 7L;

        Mockito.when(cacheService.storeData(key, data, expiresAt, "text/plain", version))
                .thenReturn(expiresAt);

        mockMvc.perform(put("/api/v1/internal/cache/{key}", key)
                        .content(data)
                        .header("X-Expires-At",     expiresAt)
                        .header("X-Quorum-Version", version)
                        .header("Content-Type",     "text/plain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Mockito.verify(walClient, Mockito.times(1))
                .appendAsync(key, data, expiresAt, "text/plain", version);
    }

    /**
     * AC: when storeData throws, the failure path (staleKeyRegistry) is taken and
     * walClient.appendAsync must NOT be invoked.
     */
    @Test
    void receiveData_storeDataThrows_walClientAppendAsyncNotInvoked() throws Exception {
        String key     = "fail-key";
        byte[] data    = "payload".getBytes();
        long expiresAt = System.currentTimeMillis() + 60_000L;
        long version   = 3L;

        Mockito.when(cacheService.storeData(key, data, expiresAt, "text/plain", version))
                .thenThrow(new RuntimeException("storage failure"));

        mockMvc.perform(put("/api/v1/internal/cache/{key}", key)
                        .content(data)
                        .header("X-Expires-At",     expiresAt)
                        .header("X-Quorum-Version", version)
                        .header("Content-Type",     "text/plain"))
                .andExpect(status().isInternalServerError());

        Mockito.verify(walClient, Mockito.never())
                .appendAsync(Mockito.anyString(), Mockito.any(), Mockito.anyLong(),
                        Mockito.anyString(), Mockito.anyLong());
    }
}
