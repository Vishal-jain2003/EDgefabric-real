package com.edgefabric.caching.service;

import com.edgefabric.caching.exception.CacheExpiredException;
import com.edgefabric.caching.exception.CacheNotFoundException;
import com.edgefabric.caching.exception.MissingVersionException;
import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.resolver.TtlCacheResolver;
import com.edgefabric.caching.util.LogSanitizer;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core cache service that handles GET and PUT operations with memory-based LRU eviction.
 *
 * <h3>PUT — Three Paths</h3>
 * <ol>
 *   <li><b>Existing key (fast path)</b> — update value via {@code store.compute()},
 *       adjust memory delta, record access asynchronously. Evict only if memory grew beyond limit.</li>
 *   <li><b>New key, space available (fast path)</b> — insert directly, update memory counter,
 *       record access asynchronously. No lock.</li>
 *   <li><b>New key, memory full</b> — acquire eviction lock, drain access buffer,
 *       evict LRU entries from LL tail, then store. Hard memory cap guaranteed.</li>
 * </ol>
 *
 * <h3>GET — Zero Locks</h3>
 * <p>Reads from ConcurrentHashMap (lock-free), checks TTL, records access event
 * asynchronously via the LRU buffer. No lock is ever acquired on the normal GET path.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InternalCacheService {

    private final Map<String, CacheItem> store;
    private final TtlCacheResolver ttlResolver;
    private final TimeWheelEvictionService timeWheelEvictionService;
    private final LruEvictionService lruEvictionService;
    private final AtomicLong currentMemoryUsage;
    private final CacheMetricsService cacheMetricsService;

    @Value("${edgefabric.cache.max-memory-bytes}")
    private long maxMemoryBytes;

    /**
     * Stores or updates a cache entry.
     * <p>
     * Routes to one of three paths based on whether the key already exists
     * and whether memory is available.
     * </p>
     *
     * @param key              the cache key
     * @param data             the payload bytes
     * @param requestedExpiresAt the requested absolute expiry timestamp
     * @param contentType      the MIME content type
     * @param version          the quorum version (must be positive)
     * @return the applied expiry timestamp
     */
    @Observed(name = "cache.put", contextualName = "cache-put-operation")
    public long storeData(String key, byte[] data, long requestedExpiresAt,
                          String contentType, long version) {

        if (version <= 0) {
            throw new MissingVersionException(key);
        }

        long appliedExpiresAt = requestedExpiresAt;

        // Use compute() to atomically handle both existing and new key paths,
        // avoiding TOCTOU race between containsKey and put/compute.
        final long[] memoryDelta = {0};
        final boolean[] isNewKey = {false};
        final boolean[] writeAccepted = {false};

        store.compute(key, (k, existing) -> {
            if (existing != null && existing.getVersion() >= version) {
                log.debug("Rejecting stale write for key={} (stored={} >= incoming={})",
                        LogSanitizer.sanitize(key), existing.getVersion(), version);
                return existing;
            }

            CacheItem newItem = new CacheItem(data, appliedExpiresAt, contentType, version, true);
            long oldSize = (existing != null) ? existing.getMemorySize() : 0;
            memoryDelta[0] = newItem.getMemorySize() - oldSize;
            isNewKey[0] = (existing == null);
            writeAccepted[0] = true;

            timeWheelEvictionService.addKey(key, appliedExpiresAt);
            return newItem;
        });

        if (writeAccepted[0]) {
            cacheMetricsService.recordPut();
        }

        if (memoryDelta[0] != 0) {
            currentMemoryUsage.addAndGet(memoryDelta[0]);
        }

        lruEvictionService.recordAccess(key);

        if (currentMemoryUsage.get() > maxMemoryBytes) {
            lruEvictionService.evictUntilBelow(maxMemoryBytes, key);
        }

        log.debug("{} key={} (expiresAt={}, memoryDelta={}, totalMemory={})",
                isNewKey[0] ? "Stored new" : "Updated existing",
                LogSanitizer.sanitize(key), appliedExpiresAt, memoryDelta[0], currentMemoryUsage.get());

        return appliedExpiresAt;
    }

    /**
     * Retrieves an item from the cache, applying lazy eviction if expired.
     * <p>
     * This method acquires <b>no locks</b> on the normal path. The access event
     * is recorded asynchronously via the LRU buffer.
     * </p>
     *
     * @param key the cache key
     * @return the valid CacheItem
     * @throws CacheNotFoundException if the key does not exist
     * @throws CacheExpiredException  if the key has expired (lazy eviction triggered)
     */
    @Observed(name = "cache.get", contextualName = "cache-get-operation")
    public CacheItem get(String key) {
        CacheItem item = store.get(key);

        if (item == null) {
            cacheMetricsService.recordMiss();
            throw new CacheNotFoundException("Cache not found for key: " + LogSanitizer.sanitize(key));
        }

        // Lazy TTL eviction — atomically remove; only one thread decrements
        if (ttlResolver.isExpired(item)) {

            boolean removed = store.remove(key, item);

            if (removed) {
                timeWheelEvictionService.removeKey(key);
                lruEvictionService.removeEntry(key);
                currentMemoryUsage.addAndGet(-item.getMemorySize());
                log.debug("Lazy eviction triggered for expired key: {}", LogSanitizer.sanitize(key));
            } else {
                log.debug("Skip eviction for key={} as it was updated concurrently", LogSanitizer.sanitize(key));
            }

            cacheMetricsService.recordMiss();
            throw new CacheExpiredException("Cache expired for key: " + LogSanitizer.sanitize(key));
        }

        cacheMetricsService.recordHit();

        // Record access asynchronously for LRU ordering (lock-free CAS)
        lruEvictionService.recordAccess(key);

        return item;
    }


    /**
     * Extends the TTL of an existing cache entry without modifying its value.
     * <p>
     * Uses {@code ConcurrentHashMap.compute()} for an atomic read-modify-write cycle:
     * <ul>
     *   <li>Key absent → throws {@link CacheNotFoundException}</li>
     *   <li>Key present but expired → throws {@link CacheExpiredException}</li>
     *   <li>Stale version (incoming <= stored) → silently skips; returns current expiresAt</li>
     *   <li>Fresh version → replaces {@link CacheItem} with same data/contentType but new expiryTime</li>
     * </ul>
     * </p>
     *
     * @param key          the cache key to touch
     * @param newExpiresAt absolute epoch-millis for the new expiry
     * @param version      monotonic quorum version; must be {@code > existing.version} to apply
     * @return the applied expiry timestamp (either {@code newExpiresAt} or current if stale)
     * @throws CacheNotFoundException if the key does not exist in the store
     * @throws CacheExpiredException  if the key exists but has already expired
     */
    @Observed(name = "cache.touch", contextualName = "cache-touch-operation")
    public long touch(String key, long newExpiresAt, long version) {
        final long[] resultExpiresAt = {-1L};
        final RuntimeException[] pendingException = {null};

        store.compute(key, (k, existing) -> {
            if (existing == null) {
                pendingException[0] = new CacheNotFoundException(
                        "Cache not found for key: " + LogSanitizer.sanitize(key));
                return null;
            }
            if (ttlResolver.isExpired(existing)) {
                pendingException[0] = new CacheExpiredException(
                        "Cache expired for key: " + LogSanitizer.sanitize(key));
                return existing; // leave stale entry in place; lazy eviction handles removal
            }
            if (existing.getVersion() >= version) {
                // Stale touch — keep the existing entry unchanged
                log.debug("Skipping stale touch for key={} (stored={} >= incoming={})",
                        LogSanitizer.sanitize(key), existing.getVersion(), version);
                resultExpiresAt[0] = existing.getExpiryTime();
                return existing;
            }

            CacheItem updated = new CacheItem(
                    existing.getData(), newExpiresAt, existing.getContentType(), version, true);
            timeWheelEvictionService.addKey(key, newExpiresAt);
            resultExpiresAt[0] = newExpiresAt;
            log.debug("Touched key={} newExpiresAt={}", LogSanitizer.sanitize(key), newExpiresAt);
            return updated;
        });

        if (pendingException[0] != null) {
            throw pendingException[0];
        }

        return resultExpiresAt[0];
    }

    // ─────────────────────────────────────────────────────
    //  Scheduled Tasks
    // ─────────────────────────────────────────────────────

    /**
     * Periodic memory reconciliation — corrects counter drift every 60 seconds.
     * <p>
     * Multiple removal paths (LRU eviction, lazy TTL, time-wheel, PUT overwrite)
     * each update the memory counter independently. In rare edge cases, the counter
     * can drift from the actual total. This task recalculates from the store and resets.
     * </p>
     */
    @Scheduled(fixedDelay = 60_000)
    public void reconcileMemory() {
        lruEvictionService.reconcileMemory();
    }
}