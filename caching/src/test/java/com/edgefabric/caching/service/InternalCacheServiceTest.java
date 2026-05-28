package com.edgefabric.caching.service;

import com.edgefabric.caching.exception.CacheExpiredException;
import com.edgefabric.caching.exception.CacheNotFoundException;
import com.edgefabric.caching.exception.MissingVersionException;
import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.resolver.TtlCacheResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class InternalCacheServiceTest {

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

        ReflectionTestUtils.setField(cacheService, "maxMemoryBytes", 536870912L);
    }

    @Test
    void testStoreDataSuccess() {
        long requestedExpiresAt = System.currentTimeMillis() + 1000L;
        long appliedExpiresAt = cacheService.storeData("testKey", "hello".getBytes(), requestedExpiresAt, "text/plain", 100L);
        assertEquals(requestedExpiresAt, appliedExpiresAt);

        CacheItem item = cacheService.get("testKey");
        assertNotNull(item);
        assertArrayEquals("hello".getBytes(), item.getData());
        assertEquals(100L, item.getVersion());
        assertEquals(requestedExpiresAt, item.getExpiryTime());
    }

    @Test
    void testStoreDataWithLargeTTL() {
        long requestedExpiresAt = System.currentTimeMillis() + 100000000L; // ~27 hours
        long appliedExpiresAt = cacheService.storeData("largeKey", "data".getBytes(), requestedExpiresAt, "text/plain", 1L);

        // With no TTL limit, the applied expiry should match the requested expiry
        assertEquals(requestedExpiresAt, appliedExpiresAt);
    }

    @Test
    void testOverwriteExistingKey_NewerVersionWins() {
        cacheService.storeData("key1", "old".getBytes(), System.currentTimeMillis() + 1000L, "text", 100L);
        long newerExpiresAt = System.currentTimeMillis() + 2000L;
        cacheService.storeData("key1", "new".getBytes(), newerExpiresAt, "json", 200L);

        CacheItem item = cacheService.get("key1");
        assertArrayEquals("new".getBytes(), item.getData());
        assertEquals(200L, item.getVersion());
        assertEquals(newerExpiresAt, item.getExpiryTime());
    }

    @Test
    void testOverwriteExistingKey_OlderVersionIgnored() {
        long newerExpiresAt = System.currentTimeMillis() + 2000L;
        cacheService.storeData("key1", "new".getBytes(), newerExpiresAt, "json", 200L);
        cacheService.storeData("key1", "old".getBytes(), System.currentTimeMillis() + 1000L, "text", 100L);

        CacheItem item = cacheService.get("key1");
        assertArrayEquals("new".getBytes(), item.getData());
        assertEquals(200L, item.getVersion());
        assertEquals(newerExpiresAt, item.getExpiryTime());
    }

    @Test
    void testMissingVersion_ThrowsMissingVersionException() {
        long requestedExpiresAt = System.currentTimeMillis() + 1000L;
        byte[] payload = "data".getBytes();

        assertThrows(MissingVersionException.class,
                () -> cacheService.storeData("key", payload, requestedExpiresAt, "text/plain", 0L));
        assertThrows(MissingVersionException.class,
                () -> cacheService.storeData("key", payload, requestedExpiresAt, "text/plain", -1L));
    }

    @Test
    void testGetWhenKeyDoesNotExist() {
        assertThrows(CacheNotFoundException.class, () -> cacheService.get("missing"));
    }


    @Test
    void expiredEntryShouldBeRemovedOnGetAndThrowSpecificException() {
        TtlCacheResolver fakeResolver = new TtlCacheResolver() {
            @Override
            public boolean isExpired(CacheItem item) {
                return true;
            }
        };

        InternalCacheService tempService = new InternalCacheService(
                store,
                fakeResolver,
                timeWheelEvictionService,
                lruEvictionService,
                currentMemoryUsage,
                cacheMetricsService
        );

        ReflectionTestUtils.setField(tempService, "maxMemoryBytes", 536870912L);
        tempService.storeData("k1", "data".getBytes(), System.currentTimeMillis() + 1000L, "text", 1L);

        assertThrows(CacheExpiredException.class, () -> tempService.get("k1"));
    }

    @Test
    void replicatedWriteShouldStoreExactOriginExpiry() {
        long originExpiresAt = System.currentTimeMillis() + 5000L;

        cacheService.storeData("replicated", "payload".getBytes(), originExpiresAt, "application/json", 1L);

        CacheItem item = cacheService.get("replicated");
        assertEquals(originExpiresAt, item.getExpiryTime());
    }

    // ── Metrics instrumentation tests ────────────────────────────────────────

    @Test
    void storeData_ShouldRecordPutOnAcceptedWrite() {
        cacheService.storeData("k", "v".getBytes(), System.currentTimeMillis() + 1000L, "text/plain", 1L);

        verify(cacheMetricsService).recordPut();
    }

    @Test
    void storeData_ShouldNotRecordPutOnStaleWrite() {
        long expiresAt = System.currentTimeMillis() + 1000L;
        cacheService.storeData("k", "new".getBytes(), expiresAt, "text/plain", 100L);
        // stale write — version 50 < stored version 100
        cacheService.storeData("k", "old".getBytes(), expiresAt, "text/plain", 50L);

        // only the first accepted write should record a put
        verify(cacheMetricsService).recordPut();
    }

    @Test
    void get_ShouldRecordHitOnCacheHit() {
        cacheService.storeData("k", "v".getBytes(), System.currentTimeMillis() + 10_000L, "text/plain", 1L);

        cacheService.get("k");

        verify(cacheMetricsService).recordHit();
    }

    @Test
    void get_ShouldRecordMissWhenKeyNotFound() {
        assertThrows(Exception.class, () -> cacheService.get("missing"));

        verify(cacheMetricsService).recordMiss();
        verify(cacheMetricsService, never()).recordHit();
    }

    @Test
    void get_ShouldRecordMissOnExpiredKey() {
        TtlCacheResolver alwaysExpired = new TtlCacheResolver() {
            @Override
            public boolean isExpired(CacheItem item) { return true; }
        };

        InternalCacheService svc = new InternalCacheService(
                store, alwaysExpired, timeWheelEvictionService, lruEvictionService,
                currentMemoryUsage, cacheMetricsService);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "maxMemoryBytes", 536870912L);

        svc.storeData("k", "v".getBytes(), System.currentTimeMillis() + 1000L, "text/plain", 1L);
        assertThrows(Exception.class, () -> svc.get("k"));

        verify(cacheMetricsService).recordMiss();
        verify(cacheMetricsService, never()).recordHit();
    }
}
