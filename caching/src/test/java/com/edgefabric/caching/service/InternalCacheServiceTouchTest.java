package com.edgefabric.caching.service;

import com.edgefabric.caching.exception.CacheExpiredException;
import com.edgefabric.caching.exception.CacheNotFoundException;
import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.resolver.TtlCacheResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InternalCacheServiceTouchTest {

    private InternalCacheService cacheService;
    private Map<String, CacheItem> store;
    private AtomicLong currentMemoryUsage;

    @Mock
    private TtlCacheResolver ttlResolver;

    @Mock
    private TimeWheelEvictionService timeWheelEvictionService;

    @Mock
    private LruEvictionService lruEvictionService;

    @Mock
    private CacheMetricsService cacheMetricsService;

    private static final String KEY = "user:session:abc";
    private static final long VERSION = 100L;

    @BeforeEach
    void setUp() {
        store = new ConcurrentHashMap<>();
        currentMemoryUsage = new AtomicLong(0);
        cacheService = new InternalCacheService(
                store,
                ttlResolver,
                timeWheelEvictionService,
                lruEvictionService,
                currentMemoryUsage,
                cacheMetricsService
        );
        ReflectionTestUtils.setField(cacheService, "maxMemoryBytes", 536_870_912L);
    }

    // ─── AC2: touch on valid existing key updates expiresAt and calls timeWheel ───

    @Test
    @DisplayName("AC2: touch() updates expiresAt in-place via compute() and updates time-wheel")
    void touch_existingValidKey_updatesExpiryTime() {
        long oldExpiresAt = System.currentTimeMillis() + 60_000L;
        long newExpiresAt = System.currentTimeMillis() + 3_600_000L;
        CacheItem original = new CacheItem("data".getBytes(), oldExpiresAt, "text/plain", VERSION, true);
        store.put(KEY, original);

        when(ttlResolver.isExpired(original)).thenReturn(false);

        long returned = cacheService.touch(KEY, newExpiresAt, VERSION + 1);

        assertThat(returned).isEqualTo(newExpiresAt);

        CacheItem updated = store.get(KEY);
        assertThat(updated).isNotNull();
        assertThat(updated.getExpiryTime()).isEqualTo(newExpiresAt);
        assertThat(updated.getData()).isEqualTo("data".getBytes());
        assertThat(updated.getContentType()).isEqualTo("text/plain");

        verify(timeWheelEvictionService).addKey(KEY, newExpiresAt);
    }

    // ─── AC8: unit coverage — touch preserves original data bytes ───

    @Test
    @DisplayName("AC8: touch() preserves original data and content-type unchanged")
    void touch_preservesDataAndContentType() {
        byte[] originalData = "important-session-data".getBytes();
        long oldExpiresAt = System.currentTimeMillis() + 60_000L;
        long newExpiresAt = System.currentTimeMillis() + 7_200_000L;
        CacheItem original = new CacheItem(originalData, oldExpiresAt, "application/json", VERSION, true);
        store.put(KEY, original);

        when(ttlResolver.isExpired(original)).thenReturn(false);

        cacheService.touch(KEY, newExpiresAt, VERSION + 1);

        CacheItem updated = store.get(KEY);
        assertThat(updated.getData()).isEqualTo(originalData);
        assertThat(updated.getContentType()).isEqualTo("application/json");
    }

    // ─── AC3: absent key → CacheNotFoundException ───

    @Test
    @DisplayName("AC3: touch() throws CacheNotFoundException when key does not exist")
    void touch_keyAbsent_throwsCacheNotFoundException() {
        long newExpiresAt = System.currentTimeMillis() + 3_600_000L;
        // key not in store

        assertThrows(CacheNotFoundException.class,
                () -> cacheService.touch(KEY, newExpiresAt, VERSION));

        verify(timeWheelEvictionService, never()).addKey(any(), anyLong());
    }

    // ─── AC3: expired key → CacheExpiredException ───

    @Test
    @DisplayName("AC3: touch() throws CacheExpiredException when key is expired")
    void touch_keyExpired_throwsCacheExpiredException() {
        long pastExpiresAt = System.currentTimeMillis() - 5_000L;
        long newExpiresAt = System.currentTimeMillis() + 3_600_000L;
        CacheItem expired = new CacheItem("data".getBytes(), pastExpiresAt, "text/plain", VERSION, true);
        store.put(KEY, expired);

        when(ttlResolver.isExpired(expired)).thenReturn(true);

        assertThrows(CacheExpiredException.class,
                () -> cacheService.touch(KEY, newExpiresAt, VERSION + 1));

        verify(timeWheelEvictionService, never()).addKey(any(), anyLong());
    }

    // ─── Stale version: touch with version <= existing → returns current expiresAt unchanged ───

    @Test
    @DisplayName("Stale touch (version <= existing.version) is ignored, returns current expiresAt")
    void touch_staleVersion_returnsCurrentExpiresAtUnchanged() {
        long currentExpiresAt = System.currentTimeMillis() + 3_600_000L;
        long newExpiresAt = System.currentTimeMillis() + 7_200_000L;
        CacheItem existing = new CacheItem("data".getBytes(), currentExpiresAt, "text/plain", VERSION, true);
        store.put(KEY, existing);

        when(ttlResolver.isExpired(existing)).thenReturn(false);

        // Same version as existing — stale touch
        long returned = cacheService.touch(KEY, newExpiresAt, VERSION);

        // expiresAt should NOT have changed (stale write rejected)
        assertThat(store.get(KEY).getExpiryTime()).isEqualTo(currentExpiresAt);
        // time-wheel should not be updated with new expiry
        verify(timeWheelEvictionService, never()).addKey(eq(KEY), eq(newExpiresAt));
    }

    // ─── Lower version: touch with lower version → also ignored ───

    @Test
    @DisplayName("Touch with lower version than stored is ignored")
    void touch_lowerVersion_isIgnored() {
        long currentExpiresAt = System.currentTimeMillis() + 3_600_000L;
        CacheItem existing = new CacheItem("data".getBytes(), currentExpiresAt, "text/plain", VERSION, true);
        store.put(KEY, existing);

        when(ttlResolver.isExpired(existing)).thenReturn(false);

        cacheService.touch(KEY, currentExpiresAt + 1_000L, VERSION - 1);

        assertThat(store.get(KEY).getExpiryTime()).isEqualTo(currentExpiresAt);
        verify(timeWheelEvictionService, never()).addKey(eq(KEY), eq(currentExpiresAt + 1_000L));
    }
}
