package com.edgefabric.caching.service;

import com.edgefabric.caching.model.CacheItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class TimeWheelEvictionServiceTest {

    private TimeWheelEvictionService service;
    private List<Set<String>> wheel;
    private AtomicInteger currentBucket;
    private AtomicReference<Map<String, CacheItem>> storeRef;
    private ConcurrentMap<String, Integer> keyBucketMap;
    private Map<String, CacheItem> store;
    private AtomicLong currentMemoryUsage;

    @BeforeEach
    void setUp() {
        wheel = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            wheel.add(ConcurrentHashMap.newKeySet());
        }
        currentBucket = new AtomicInteger(0);
        store = new ConcurrentHashMap<>();
        storeRef = new AtomicReference<>(store);
        keyBucketMap = new ConcurrentHashMap<>();
        currentMemoryUsage = new AtomicLong(0);
        LruEvictionService lruEvictionService = mock(LruEvictionService.class);
        CacheMetricsService cacheMetricsService = mock(CacheMetricsService.class);

        service = new TimeWheelEvictionService(wheel, currentBucket, storeRef, keyBucketMap,
                currentMemoryUsage, lruEvictionService, cacheMetricsService);
    }

    @Test
    void testAddKey_NewKey() {
        String key = "testKey";
        long expiry = System.currentTimeMillis() + 5500;

        service.addKey(key, expiry);

        int expectedBucket = (currentBucket.get() + 5) % 60;
        assertTrue(wheel.get(expectedBucket).contains(key));
        assertEquals(expectedBucket, keyBucketMap.get(key));
    }

    @Test
    void testAddKey_UpdateExistingKey() {
        String key = "testKey";
        service.addKey(key, System.currentTimeMillis() + 2000);
        int firstBucket = keyBucketMap.get(key);

        service.addKey(key, System.currentTimeMillis() + 10000);
        int secondBucket = keyBucketMap.get(key);

        assertFalse(wheel.get(firstBucket).contains(key));
        assertTrue(wheel.get(secondBucket).contains(key));
        assertEquals(1, keyBucketMap.size());
    }

    @Test
    void testRemoveKey() {
        String key = "removeMe";
        service.addKey(key, System.currentTimeMillis() + 5000);
        int bucket = keyBucketMap.get(key);

        service.removeKey(key);

        assertFalse(wheel.get(bucket).contains(key));
        assertFalse(keyBucketMap.containsKey(key));
    }

    @Test
    void testEvictExpiredKeys_Expired() throws Exception {
        String key = "expiredKey";
        long pastExpiry = System.currentTimeMillis() - 1000;
        CacheItem item = new CacheItem(new byte[0], pastExpiry, "text", 1L, true);

        store.put(key, item);
        service.addKey(key, pastExpiry);

        int bucketIndex = keyBucketMap.get(key);
        currentBucket.set(bucketIndex);

        Method method = TimeWheelEvictionService.class.getDeclaredMethod("evictExpiredKeys");
        method.setAccessible(true);
        method.invoke(service);

        assertFalse(store.containsKey(key));
        assertFalse(keyBucketMap.containsKey(key));
        assertTrue(wheel.get(bucketIndex).isEmpty());
        assertEquals((bucketIndex + 1) % 60, currentBucket.get());
    }

    @Test
    void testEvictExpiredKeys_Valid() throws Exception {
        String key = "validKey";
        long futureExpiry = System.currentTimeMillis() + 10000;
        CacheItem item = new CacheItem(new byte[0], futureExpiry, "text", 1L, true);

        store.put(key, item);
        service.addKey(key, futureExpiry);

        int bucketIndex = keyBucketMap.get(key);
        currentBucket.set(bucketIndex);

        Method method = TimeWheelEvictionService.class.getDeclaredMethod("evictExpiredKeys");
        method.setAccessible(true);
        method.invoke(service);

        assertTrue(store.containsKey(key));
        assertEquals((bucketIndex + 1) % 60, currentBucket.get());
    }

    @Test
    void testEvictExpiredKeys_EmptyStore() throws Exception {
        String key = "ghostKey";
        service.addKey(key, System.currentTimeMillis() + 1000);
        int bucketIndex = keyBucketMap.get(key);
        currentBucket.set(bucketIndex);
        store.remove(key);

        Method method = TimeWheelEvictionService.class.getDeclaredMethod("evictExpiredKeys");
        method.setAccessible(true);
        method.invoke(service);

        assertFalse(keyBucketMap.containsKey(key));
        assertTrue(wheel.get(bucketIndex).isEmpty());
    }

    @Test
    void testLifecycle() {
        assertDoesNotThrow(() -> {
            service.init();
            service.shutdown();
        });
    }
}