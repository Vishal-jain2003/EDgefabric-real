package com.edgefabric.caching.resolver;

import com.edgefabric.caching.model.CacheItem;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TtlCacheResolverTest {

    private final TtlCacheResolver resolver = new TtlCacheResolver();

    @Test
    void testItemWithFutureExpiryIsNotExpired() {
        CacheItem item = new CacheItem("data".getBytes(), 3600000, "text/plain");
        assertFalse(resolver.isExpired(item), "Item with future expiry should be valid");
    }

    @Test
    void testItemWithPastExpiryIsExpired() {
        CacheItem item = new CacheItem("data".getBytes(), -5000, "text/plain");
        assertTrue(resolver.isExpired(item), "Item with past expiry should be expired");
    }

    @Test
    void testNullItemThrowsException() {
        // Validation logic changed in refactor: Null should throw an exception to fail fast
        assertThrows(IllegalArgumentException.class, () -> resolver.isExpired(null),
                "Passing null to resolver should throw IllegalArgumentException");
    }

    @Test
    void testZeroTtlExpiresImmediately() {
        CacheItem item = new CacheItem("data".getBytes(), -1, "text/plain");
        assertTrue(resolver.isExpired(item), "Negative TTL should expire immediately");
    }
}