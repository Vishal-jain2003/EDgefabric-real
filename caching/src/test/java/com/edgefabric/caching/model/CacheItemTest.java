package com.edgefabric.caching.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CacheItemTest {

    @Test
    void constructorShouldInitializeFields() {
        byte[] data = "hello".getBytes();
        long ttl = 5000;
        String contentType = "text/plain";

        long before = System.currentTimeMillis();
        CacheItem item = new CacheItem(data, ttl, contentType);
        long after = System.currentTimeMillis();

        assertNotNull(item);
        assertArrayEquals(data, item.getData());
        assertEquals(contentType, item.getContentType());

        // Verify Expiry Logic (Now + TTL)
        assertTrue(item.getExpiryTime() >= before + ttl);
        assertTrue(item.getExpiryTime() <= after + ttl);

        // Verify Access Time initialization
        assertTrue(item.getLastAccessTime() >= before);
        assertTrue(item.getLastAccessTime() <= after);
    }

    @Test
    void updateAccessTimeShouldUpdateTimestamp() {
        byte[] data = "test".getBytes();
        CacheItem item = new CacheItem(data, 2000, "text/plain");

        long oldAccessTime = item.getLastAccessTime();

        item.updateAccessTime();


        assertTrue(item.getLastAccessTime() >= oldAccessTime, "Access time should be refreshed");
    }

    @Test
    void shouldHandleEmptyData() {
        CacheItem item = new CacheItem(new byte[0], 1000, "application/json");

        assertNotNull(item);
        assertEquals(0, item.getData().length);
    }

    @Test
    void shouldAllowNullContentType() {
        CacheItem item = new CacheItem("abc".getBytes(), 1000, null);

        assertNull(item.getContentType());
    }

    @Test
    void expiryTimeShouldBeInFutureWhenTtlPositive() {
        CacheItem item = new CacheItem("abc".getBytes(), 1000, "text/plain");

        assertTrue(item.getExpiryTime() > System.currentTimeMillis());
    }

    @Test
    void absoluteExpiryConstructorShouldStoreOriginExpiryWithoutRecalculation() {
        long expiresAt = System.currentTimeMillis() + 30_000L;

        CacheItem item = new CacheItem("replica".getBytes(), expiresAt, "text/plain", 42L, true);

        assertEquals(expiresAt, item.getExpiryTime());
        assertEquals(42L, item.getVersion());
    }

    @Test
    void absoluteExpiryConstructorShouldRejectFalseFlag() {
        byte[] replicaData = "replica".getBytes();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new CacheItem(replicaData, 1L, "text/plain", 42L, false));

        assertTrue(exception.getMessage().contains("absoluteExpiry=true"));
    }
}
