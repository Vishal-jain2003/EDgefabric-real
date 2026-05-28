package com.edgefabric.caching.config;

import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.service.TimeWheelEvictionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Configuration
public class CacheConfig {


    @Bean
    public Map<String, CacheItem> store() {
        return new ConcurrentHashMap<>();
    }

    /** Global memory counter — tracks total heap usage of all cache entries. */
    @Bean
    public AtomicLong currentMemoryUsage() {
        return new AtomicLong(0);
    }

    @Bean
    public List<Set<String>> wheel() {
        List<Set<String>> wheel = new ArrayList<>(TimeWheelEvictionService.BUCKET_COUNT);
        for (int i = 0; i < TimeWheelEvictionService.BUCKET_COUNT; i++) {
            wheel.add(ConcurrentHashMap.newKeySet());
        }
        return wheel;
    }

    @Bean
    public AtomicInteger currentBucket() {
        return new AtomicInteger(0);
    }

    @Bean
    public ConcurrentMap<String, Integer> keyBucketMap() {
        return new ConcurrentHashMap<>();
    }

    @Bean
    public AtomicReference<Map<String, CacheItem>> storeRef(Map<String, CacheItem> store) {
        return new AtomicReference<>(store);
    }
}