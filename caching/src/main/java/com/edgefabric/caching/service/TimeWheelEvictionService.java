package com.edgefabric.caching.service;

import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.util.LogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class TimeWheelEvictionService {

    public static final int BUCKET_COUNT = 60;

    private final List<Set<String>> wheel ;
    private final AtomicInteger currentBucket;
    private final AtomicReference<Map<String, CacheItem>> storeRef ;
    private final ConcurrentMap<String, Integer> keyBucketMap ;
    private final AtomicLong currentMemoryUsage;
    private final LruEvictionService lruEvictionService;
    private final CacheMetricsService cacheMetricsService;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "time-wheel-eviction");
                thread.setDaemon(true);
                return thread;
            });


    public TimeWheelEvictionService(List<Set<String>> wheel,
                                    AtomicInteger currentBucket,
                                    AtomicReference<Map<String, CacheItem>> storeRef,
                                    ConcurrentMap<String, Integer> keyBucketMap,
                                    AtomicLong currentMemoryUsage,
                                    @Lazy LruEvictionService lruEvictionService,
                                    CacheMetricsService cacheMetricsService) {
        this.wheel = wheel;
        this.currentBucket = currentBucket;
        this.storeRef = storeRef;
        this.keyBucketMap = keyBucketMap;
        this.currentMemoryUsage = currentMemoryUsage;
        this.lruEvictionService = lruEvictionService;
        this.cacheMetricsService = cacheMetricsService;
    }

    @PostConstruct
    public void init() {
        scheduler.scheduleWithFixedDelay(
                this::evictExpiredKeys,
                1,
                1,
                TimeUnit.SECONDS
        );
    }

    public void addKey(String key, long expiryTimeMillis) {

        long delaySeconds =
                (expiryTimeMillis - System.currentTimeMillis()) / 1000;

        int bucketIndex =
                (int) ((currentBucket.get() + delaySeconds) % BUCKET_COUNT);

        if (bucketIndex < 0) {
            bucketIndex = bucketIndex + BUCKET_COUNT;
        }
        Integer oldBucket = keyBucketMap.put(key, bucketIndex);

        if (oldBucket != null) {
            wheel.get(oldBucket).remove(key);
        }

        wheel.get(bucketIndex).add(key);
        log.debug("Stored on bucket {}", bucketIndex);
    }



    public void removeKey(String key) {
        Integer bucket = keyBucketMap.remove(key);
        if (bucket != null) {
            wheel.get(bucket).remove(key);
        }
    }

    private void evictExpiredKeys() {
        Map<String, CacheItem> localStore = storeRef.get();
        try {

            int bucketIndex = currentBucket.get();

            Set<String> bucket = wheel.get(bucketIndex);

            Iterator<String> iterator = bucket.iterator();

            while (iterator.hasNext()) {

                String key = iterator.next();

                Optional<CacheItem> itemOpt = Optional.ofNullable(localStore.get(key));

                if (itemOpt.isEmpty()) {
                    iterator.remove();
                    keyBucketMap.remove(key);
                    continue;
                }

                CacheItem item = itemOpt.get();

                if (System.currentTimeMillis() >= item.getExpiryTime()) {

                    CacheItem removedItem = localStore.remove(key);
                    iterator.remove();
                    keyBucketMap.remove(key);

                    // Update memory counter and remove from LRU linked list
                    if (removedItem != null) {
                        currentMemoryUsage.addAndGet(-removedItem.getMemorySize());
                        lruEvictionService.removeEntry(key);
                        cacheMetricsService.recordEviction();
                    }

                    log.info("Active eviction removed key: {}", LogSanitizer.sanitize(key));
                }
            }

            currentBucket.set((bucketIndex + 1) % BUCKET_COUNT);

        } catch (Exception e) {

            log.error("Error during active eviction", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}