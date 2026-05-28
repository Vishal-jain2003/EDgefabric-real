package com.edgefabric.caching.resolver;

import com.edgefabric.caching.model.CacheItem;
import org.springframework.stereotype.Component;

@Component
public class TtlCacheResolver {

    public boolean isExpired(CacheItem item) {
        if (item == null) {
            throw new IllegalArgumentException("Cannot evaluate expiration for a null cache item");
        }
        return System.currentTimeMillis() > item.getExpiryTime();
    }
}