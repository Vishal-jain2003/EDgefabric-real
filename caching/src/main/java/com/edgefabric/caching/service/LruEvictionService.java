package com.edgefabric.caching.service;

import com.edgefabric.caching.model.CacheItem;
import com.edgefabric.caching.util.LogSanitizer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;


@Slf4j
@Service
public class LruEvictionService {

    static class Node {
        final String key;
        Node prev;
        Node next;

        Node(String key) {
            this.key = key;
        }
    }


    /** Maximum number of access events buffered before new events are dropped. */
    private static final int MAX_BUFFER_SIZE = 100_000;

    /**
     * Interval in milliseconds between scheduled drain runs.
     * Increased from 10ms to 50ms to reduce lock contention between drain and PUT operations.
     * At 10ms, the drain runs 100 times/second, causing frequent lock conflicts.
     */
    private static final long DRAIN_INTERVAL_MS = 50;

    /**
     * Timeout for tryLock() in scheduled drain.
     * If the lock is held by a PUT operation, we skip this drain cycle instead of blocking.
     */
    private static final long DRAIN_LOCK_TIMEOUT_MS = 10;

    /** Sentinel head node. head.next is the MRU entry. */
    private final Node head = new Node(null);

    /** Sentinel tail node. tail.prev is the LRU entry (eviction candidate). */
    private final Node tail = new Node(null);

    // ─────────────────────────────────────────────────────
    //  Data Structures
    // ─────────────────────────────────────────────────────

    /** O(1) lookup from cache key → its LL node. */
    private final ConcurrentHashMap<String, Node> nodeMap = new ConcurrentHashMap<>();

    /**
     * Bounded lock-free buffer for recording access events from GET/PUT threads.
     * Uses ArrayBlockingQueue instead of ConcurrentLinkedQueue to prevent unbounded growth.
     * If buffer is full, new access events are silently dropped (LRU becomes approximate).
     * This prevents OOM when eviction thread falls behind under extreme load.
     */
    private final BlockingQueue<String> accessBuffer = new ArrayBlockingQueue<>(MAX_BUFFER_SIZE);

    /** Guards all LL mutations — drain, eviction, and removeEntry. */
    private final ReentrantLock evictionLock = new ReentrantLock();

    // ─────────────────────────────────────────────────────
    //  Injected Dependencies
    // ─────────────────────────────────────────────────────

    private final Map<String, CacheItem> store;
    private final AtomicLong currentMemoryUsage;
    private final TimeWheelEvictionService timeWheelEvictionService;
    private final CacheMetricsService cacheMetricsService;

    // ─────────────────────────────────────────────────────
    //  Background Drain Worker
    // ─────────────────────────────────────────────────────

    private final ScheduledExecutorService drainScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "lru-drain-worker");
                t.setDaemon(true);
                return t;
            });

    // ─────────────────────────────────────────────────────
    //  Constructor
    // ─────────────────────────────────────────────────────

    public LruEvictionService(Map<String, CacheItem> store,
                              AtomicLong currentMemoryUsage,
                              TimeWheelEvictionService timeWheelEvictionService,
                              CacheMetricsService cacheMetricsService) {
        this.store = store;
        this.currentMemoryUsage = currentMemoryUsage;
        this.timeWheelEvictionService = timeWheelEvictionService;
        this.cacheMetricsService = cacheMetricsService;

        // Link sentinels to form an empty list: HEAD ↔ TAIL
        head.next = tail;
        tail.prev = head;
    }

    // ─────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────

    /** Starts the background drain worker that processes access events every 10 ms. */
    @PostConstruct
    public void init() {
        drainScheduler.scheduleWithFixedDelay(
                this::scheduledDrain,
                DRAIN_INTERVAL_MS,
                DRAIN_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
        log.info("LRU drain worker started (interval={}ms, maxBuffer={})",
                DRAIN_INTERVAL_MS, MAX_BUFFER_SIZE);
    }

    /** Gracefully shuts down the drain worker on application shutdown. */
    @PreDestroy
    public void shutdown() {
        drainScheduler.shutdown();
        log.info("LRU drain worker shut down");
    }

    // ═════════════════════════════════════════════════════
    //  PUBLIC API — called by InternalCacheService
    // ═════════════════════════════════════════════════════

    /**
     * Records a cache access event for LRU tracking.
     * Uses non-blocking offer() - if buffer is full, the event is silently dropped.
     * This makes LRU ordering approximate under extreme load but prevents blocking.
     */
    public void recordAccess(String key) {
        accessBuffer.offer(key);  // Returns false if full, but we don't care - approximate LRU is fine
    }

    public void removeEntry(String key) {
        evictionLock.lock();
        try {
            Node node = nodeMap.remove(key);
            if (node != null) {
                removeNode(node);
            }
        } finally {
            evictionLock.unlock();
        }
    }

    public void evictThenStore(String key, CacheItem newItem,
                               long maxMemoryBytes, long appliedExpiresAt) {
        evictionLock.lock();
        try {
            // Re-check: another thread may have freed space while we waited for the lock
            if (currentMemoryUsage.get() + newItem.getMemorySize() <= maxMemoryBytes) {
                storeEntryUnderLock(key, newItem, appliedExpiresAt);
                return;
            }

            // Drain pending access events so LL reflects true access ordering
            drainBufferUnderLock();

            // Evict LRU entries until enough memory is freed for the new item
            evictUntilFits(newItem.getMemorySize(), maxMemoryBytes);

            // Store the new entry
            storeEntryUnderLock(key, newItem, appliedExpiresAt);

        } finally {
            evictionLock.unlock();
        }
    }


    public void evictUntilBelow(long maxMemoryBytes, String protectedKey) {
        evictionLock.lock();
        try {
            // Drain pending events for accurate LL ordering
            drainBufferUnderLock();

            // Issue #2 fix: move the just-updated key to HEAD so it cannot be evicted
            Node protectedNode = nodeMap.get(protectedKey);
            if (protectedNode != null) {
                removeNode(protectedNode);
                addAfterHead(protectedNode);
            }

            // Evict from TAIL until memory is within limit
            while (currentMemoryUsage.get() > maxMemoryBytes) {
                Node victim = tail.prev;
                if (victim == head) {
                    // LL is empty — nothing left to evict
                    log.warn("LRU list empty but memory still exceeds limit (current={})",
                            currentMemoryUsage.get());
                    break;
                }
                evictNode(victim);
            }
        } finally {
            evictionLock.unlock();
        }
    }

    public void reconcileMemory() {
        long actual = store.values().stream()
                .mapToLong(CacheItem::getMemorySize)
                .sum();
        long reported = currentMemoryUsage.getAndSet(actual);
        long drift = Math.abs(reported - actual);

        if (drift > 1_048_576L) {
            log.warn("Memory counter drift corrected: reported={}KB, actual={}KB, drift={}KB",
                    reported / 1024, actual / 1024, drift / 1024);
        }
    }

    // ═════════════════════════════════════════════════════
    //  PRIVATE — LL Operations (caller MUST hold evictionLock)
    // ═════════════════════════════════════════════════════

    private void addAfterHead(Node node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }


    private void removeNode(Node node) {
        if (node.prev != null && node.next != null) {
            node.prev.next = node.next;
            node.next.prev = node.prev;
        }
        node.prev = null;
        node.next = null;
    }

    // ═════════════════════════════════════════════════════
    //  PRIVATE — Drain, Eviction, and Store helpers
    // ═════════════════════════════════════════════════════

    private void drainBufferUnderLock() {
        String key;
        while ((key = accessBuffer.poll()) != null) {
            processAccessEvent(key);
        }
    }


    private void processAccessEvent(String key) {
        Node node = nodeMap.get(key);

        if (node != null) {
            // Existing node — promote to MRU (HEAD ke baad)
            removeNode(node);
            addAfterHead(node);
        } else if (store.containsKey(key)) {
            // Issue #1 fix: key was added via fast path and has no LL node yet.
            // Create a new node and place it at MRU position.
            node = new Node(key);
            nodeMap.put(key, node);
            addAfterHead(node);
        }
        // else: stale event — key was already evicted or expired. Silently skip.
    }

    private void evictUntilFits(long requiredBytes, long maxMemoryBytes) {
        while (currentMemoryUsage.get() + requiredBytes > maxMemoryBytes) {
            Node victim = tail.prev;
            if (victim == head) {
                log.warn("LRU list empty but memory still insufficient for {} bytes (current={})",
                        requiredBytes, currentMemoryUsage.get());
                break;
            }
            evictNode(victim);
        }
    }


    private void evictNode(Node victim) {
        CacheItem victimItem = store.remove(victim.key);
        if (victimItem != null) {
            currentMemoryUsage.addAndGet(-victimItem.getMemorySize());
            timeWheelEvictionService.removeKey(victim.key);
            cacheMetricsService.recordEviction();
            log.debug("LRU evicted key={} (freed={} bytes, remaining={})",
                    LogSanitizer.sanitize(victim.key), victimItem.getMemorySize(), currentMemoryUsage.get());
        }
        removeNode(victim);
        nodeMap.remove(victim.key);
    }

    private void storeEntryUnderLock(String key, CacheItem newItem, long appliedExpiresAt) {
        store.put(key, newItem);
        currentMemoryUsage.addAndGet(newItem.getMemorySize());
        timeWheelEvictionService.addKey(key, appliedExpiresAt);

        // Add to LL directly (not via buffer — we already hold the lock)
        Node existing = nodeMap.get(key);
        if (existing != null) {
            // Edge case: key somehow already in LL — promote to HEAD
            removeNode(existing);
            addAfterHead(existing);
        } else {
            Node node = new Node(key);
            nodeMap.put(key, node);
            addAfterHead(node);
        }
    }

    /**
     * Scheduled drain task — invoked by the background worker every 50 ms.
     * Uses tryLock() with timeout to avoid blocking if a PUT operation holds the lock.
     * If we can't acquire the lock, we skip this drain cycle - PUT operations have priority.
     */
    private void scheduledDrain() {
        boolean acquired = false;
        try {
            acquired = evictionLock.tryLock(DRAIN_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (acquired) {
                drainBufferUnderLock();
            } else {
                if (log.isTraceEnabled()) {
                    log.trace("Skipped LRU drain cycle - lock held by PUT operation");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("LRU drain interrupted");
        } catch (Exception e) {
            log.error("Error during scheduled LRU buffer drain", e);
        } finally {
            if (acquired) {
                evictionLock.unlock();
            }
        }
    }
}

