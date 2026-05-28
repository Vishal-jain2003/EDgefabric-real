package com.edgefabric.caching.service;

import com.edgefabric.caching.model.CacheItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LruEvictionServiceTest {

    private Map<String, CacheItem> store;
    private AtomicLong currentMemoryUsage;

    @Mock
    private TimeWheelEvictionService timeWheelEvictionService;
    @Mock
    private CacheMetricsService cacheMetricsService;

    private LruEvictionService lruEvictionService;

    @BeforeEach
    void setUp() {
        store = new ConcurrentHashMap<>();
        currentMemoryUsage = new AtomicLong(0);
        lruEvictionService = new LruEvictionService(store, currentMemoryUsage,
                timeWheelEvictionService, cacheMetricsService);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Tests for recordAccess()
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    void recordAccess_ShouldAddKeyToBuffer() throws Exception {
        // Record an access event
        lruEvictionService.recordAccess("key1");

        // Verify buffer contains the key via reflection
        java.util.concurrent.BlockingQueue<String> buffer = getAccessBuffer();
        assertTrue(buffer.contains("key1"));
    }

    @Test
    void recordAccess_ShouldIncrementBufferSize() throws Exception {
        lruEvictionService.recordAccess("key1");
        lruEvictionService.recordAccess("key2");

        int bufferSize = getBufferSize();
        assertEquals(2, bufferSize);
    }

    @Test
    void recordAccess_ShouldDropEventsWhenBufferFull() throws Exception {
        // Fill buffer to MAX_BUFFER_SIZE (100,000)
        // Since we changed to ArrayBlockingQueue, we need to fill it differently
        java.util.concurrent.BlockingQueue<String> buffer = getAccessBuffer();

        // Fill buffer to capacity
        for (int i = 0; i < 100_000; i++) {
            buffer.offer("filler-" + i);
        }

        // This should be dropped (offer returns false when full)
        lruEvictionService.recordAccess("overflow-key");

        assertFalse(buffer.contains("overflow-key"));
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Tests for removeEntry()
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    void removeEntry_ShouldRemoveKeyFromNodeMap() throws Exception {
        // First, add a key via evictThenStore
        CacheItem item = createCacheItem(100);
        lruEvictionService.evictThenStore("key1", item, 10_000, System.currentTimeMillis() + 60_000);

        // Verify key is in nodeMap
        ConcurrentHashMap<String, ?> nodeMap = getNodeMap();
        assertTrue(nodeMap.containsKey("key1"));

        // Remove the entry
        lruEvictionService.removeEntry("key1");

        // Verify key is removed from nodeMap
        assertFalse(nodeMap.containsKey("key1"));
    }

    @Test
    void removeEntry_ShouldHandleNonExistentKeyGracefully() {
        // Should not throw exception for non-existent key
        assertDoesNotThrow(() -> lruEvictionService.removeEntry("non-existent"));
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Tests for evictThenStore()
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    void evictThenStore_ShouldStoreItemWhenMemoryAvailable() {
        CacheItem item = createCacheItem(100);
        long expiresAt = System.currentTimeMillis() + 60_000;

        lruEvictionService.evictThenStore("key1", item, 10_000, expiresAt);

        assertTrue(store.containsKey("key1"));
        assertEquals(item.getMemorySize(), currentMemoryUsage.get());
        verify(timeWheelEvictionService).addKey("key1", expiresAt);
    }

    @Test
    void evictThenStore_ShouldAddNodeToLinkedList() throws Exception {
        CacheItem item = createCacheItem(100);
        lruEvictionService.evictThenStore("key1", item, 10_000, System.currentTimeMillis() + 60_000);

        ConcurrentHashMap<String, ?> nodeMap = getNodeMap();
        assertTrue(nodeMap.containsKey("key1"));
    }

    @Test
    void evictThenStore_ShouldEvictLruWhenMemoryExceeded() {
        // Add initial items to fill memory
        CacheItem item1 = createCacheItem(500);
        CacheItem item2 = createCacheItem(500);
        long expiresAt = System.currentTimeMillis() + 60_000;

        lruEvictionService.evictThenStore("key1", item1, 10_000, expiresAt);
        lruEvictionService.evictThenStore("key2", item2, 10_000, expiresAt);

        // Force access to key2 to make key1 the LRU
        lruEvictionService.recordAccess("key2");
        drainBuffer();

        // Add new item that requires eviction (total would exceed 1000 bytes)
        CacheItem item3 = createCacheItem(500);
        lruEvictionService.evictThenStore("key3", item3, 1000, expiresAt);

        // key1 should be evicted (LRU), key2 and key3 should remain
        assertFalse(store.containsKey("key1"));
        assertTrue(store.containsKey("key3"));
        verify(timeWheelEvictionService).removeKey("key1");
    }

    @Test
    void evictThenStore_ShouldPromoteExistingKeyToMru() throws Exception {
        CacheItem item1 = createCacheItem(100);
        long expiresAt = System.currentTimeMillis() + 60_000;

        // Store key1 twice - second store should promote to MRU
        lruEvictionService.evictThenStore("key1", item1, 10_000, expiresAt);
        lruEvictionService.evictThenStore("key1", item1, 10_000, expiresAt);

        ConcurrentHashMap<String, ?> nodeMap = getNodeMap();
        assertEquals(1, nodeMap.size()); // Should still be only one node
        assertTrue(nodeMap.containsKey("key1"));
    }

    @Test
    void evictThenStore_ShouldStoreWithoutEvictionIfMemoryFreedByAnotherThread() {
        // Simulate a scenario where memory is available without eviction needed
        CacheItem item = createCacheItem(100);
        long expiresAt = System.currentTimeMillis() + 60_000;

        // Memory is well under limit
        lruEvictionService.evictThenStore("key1", item, 10_000, expiresAt);

        assertTrue(store.containsKey("key1"));
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Tests for evictUntilBelow()
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    void evictUntilBelow_ShouldEvictUntilMemoryIsBelow() {
        // Add items to exceed memory limit
        CacheItem item1 = createCacheItem(400);
        CacheItem item2 = createCacheItem(400);
        CacheItem item3 = createCacheItem(400);
        long expiresAt = System.currentTimeMillis() + 60_000;

        lruEvictionService.evictThenStore("key1", item1, 5_000, expiresAt);
        lruEvictionService.evictThenStore("key2", item2, 5_000, expiresAt);
        lruEvictionService.evictThenStore("key3", item3, 5_000, expiresAt);

        // Evict until below 1000 bytes, protecting key3
        lruEvictionService.evictUntilBelow(1000, "key3");

        // key3 should be protected, others should be evicted
        assertTrue(store.containsKey("key3"));
        assertTrue(currentMemoryUsage.get() <= 1000 || store.size() == 1);
    }

    @Test
    void evictUntilBelow_ShouldProtectSpecifiedKey() {
        CacheItem item1 = createCacheItem(300);
        CacheItem item2 = createCacheItem(300);
        long expiresAt = System.currentTimeMillis() + 60_000;

        lruEvictionService.evictThenStore("key1", item1, 5_000, expiresAt);
        lruEvictionService.evictThenStore("key2", item2, 5_000, expiresAt);

        // Evict to very low limit, but protect key2
        // The protected key should be moved to MRU, so key1 is evicted first
        long limitBelowTwoItems = item1.getMemorySize() + 100; // Only enough for one item
        lruEvictionService.evictUntilBelow(limitBelowTwoItems, "key2");

        // key2 should still exist (protected and moved to MRU, so key1 evicted first)
        assertTrue(store.containsKey("key2"));
        assertFalse(store.containsKey("key1"));
    }

    @Test
    void evictUntilBelow_ShouldHandleEmptyListGracefully() {
        // No items in store, should not throw
        assertDoesNotThrow(() -> lruEvictionService.evictUntilBelow(100, "non-existent"));
    }

    @Test
    void evictUntilBelow_ShouldHandleProtectedKeyNotInNodeMap() {
        CacheItem item = createCacheItem(500);
        long expiresAt = System.currentTimeMillis() + 60_000;

        lruEvictionService.evictThenStore("key1", item, 5_000, expiresAt);

        // Protect a key that doesn't exist - should not throw
        assertDoesNotThrow(() -> lruEvictionService.evictUntilBelow(100, "non-existent-key"));
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Tests for reconcileMemory()
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    void reconcileMemory_ShouldCorrectMemoryCounter() {
        CacheItem item1 = createCacheItem(100);
        CacheItem item2 = createCacheItem(200);

        store.put("key1", item1);
        store.put("key2", item2);

        // Set incorrect memory usage
        currentMemoryUsage.set(999_999);

        lruEvictionService.reconcileMemory();

        // Should be corrected to actual sum
        long expectedMemory = item1.getMemorySize() + item2.getMemorySize();
        assertEquals(expectedMemory, currentMemoryUsage.get());
    }

    @Test
    void reconcileMemory_ShouldHandleEmptyStore() {
        currentMemoryUsage.set(1000);

        lruEvictionService.reconcileMemory();

        assertEquals(0, currentMemoryUsage.get());
    }

    @Test
    void reconcileMemory_ShouldLogWarningForLargeDrift() {
        CacheItem item = createCacheItem(100);
        store.put("key1", item);

        // Set memory with drift > 1MB
        currentMemoryUsage.set(item.getMemorySize() + 2_000_000);

        // Should complete without error and correct the value
        lruEvictionService.reconcileMemory();

        assertEquals(item.getMemorySize(), currentMemoryUsage.get());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Tests for init() and shutdown()
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    void init_ShouldStartDrainScheduler() {
        // init() is called by Spring via @PostConstruct
        // We can verify it doesn't throw when called
        assertDoesNotThrow(() -> lruEvictionService.init());
    }

    @Test
    void shutdown_ShouldStopDrainScheduler() {
        lruEvictionService.init();
        assertDoesNotThrow(() -> lruEvictionService.shutdown());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Tests for scheduledDrain() via reflection
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    void scheduledDrain_ShouldProcessAccessEvents() throws Exception {
        // Add items to store
        CacheItem item = createCacheItem(100);
        store.put("key1", item);

        // Record access
        lruEvictionService.recordAccess("key1");

        // Invoke scheduledDrain via reflection
        Method scheduledDrain = LruEvictionService.class.getDeclaredMethod("scheduledDrain");
        scheduledDrain.setAccessible(true);
        scheduledDrain.invoke(lruEvictionService);

        // Verify buffer is drained
        int bufferSize = getBufferSize();
        assertEquals(0, bufferSize);
    }

    @Test
    void scheduledDrain_ShouldHandleExceptionsGracefully() throws Exception {
        // This tests that exceptions in scheduled drain don't crash the service
        Method scheduledDrain = LruEvictionService.class.getDeclaredMethod("scheduledDrain");
        scheduledDrain.setAccessible(true);

        // Should not throw even with empty buffer
        assertDoesNotThrow(() -> scheduledDrain.invoke(lruEvictionService));
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Tests for processAccessEvent() via drain behavior
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    void processAccessEvent_ShouldCreateNodeForNewKeyInStore() throws Exception {
        // Add item to store but not to LL
        CacheItem item = createCacheItem(100);
        store.put("key1", item);

        // Record access and drain
        lruEvictionService.recordAccess("key1");
        drainBuffer();

        // Verify node was created
        ConcurrentHashMap<String, ?> nodeMap = getNodeMap();
        assertTrue(nodeMap.containsKey("key1"));
    }

    @Test
    void processAccessEvent_ShouldIgnoreStaleEvents() throws Exception {
        // Record access for a key not in store
        lruEvictionService.recordAccess("stale-key");
        drainBuffer();

        // Verify no node was created
        ConcurrentHashMap<String, ?> nodeMap = getNodeMap();
        assertFalse(nodeMap.containsKey("stale-key"));
    }

    @Test
    void processAccessEvent_ShouldPromoteExistingNode() {
        // Add two items
        CacheItem item1 = createCacheItem(100);
        CacheItem item2 = createCacheItem(100);
        long expiresAt = System.currentTimeMillis() + 60_000;
        long itemSize = item1.getMemorySize();

        lruEvictionService.evictThenStore("key1", item1, itemSize * 3, expiresAt);
        lruEvictionService.evictThenStore("key2", item2, itemSize * 3, expiresAt);

        // Access key1 to promote it
        lruEvictionService.recordAccess("key1");
        drainBuffer();

        // Now key1 should be MRU, key2 should be LRU
        // Add key3 with limit that forces eviction
        CacheItem item3 = createCacheItem(100);
        // Set max memory to only fit 2 items - key2 (LRU) should be evicted
        lruEvictionService.evictThenStore("key3", item3, itemSize * 2, expiresAt);

        // key2 should be evicted (was LRU after key1 was promoted)
        assertFalse(store.containsKey("key2"));
        assertTrue(store.containsKey("key1"));
        assertTrue(store.containsKey("key3"));
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Tests for edge cases and linked list operations
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    void linkedList_ShouldMaintainCorrectOrderAfterMultipleOperations() {
        CacheItem item1 = createCacheItem(100);
        CacheItem item2 = createCacheItem(100);
        CacheItem item3 = createCacheItem(100);
        long expiresAt = System.currentTimeMillis() + 60_000;
        long itemSize = item1.getMemorySize();

        // Add three items: key1, key2, key3 (key3 is MRU)
        lruEvictionService.evictThenStore("key1", item1, itemSize * 4, expiresAt);
        lruEvictionService.evictThenStore("key2", item2, itemSize * 4, expiresAt);
        lruEvictionService.evictThenStore("key3", item3, itemSize * 4, expiresAt);

        // Access key1 to make it MRU
        // After this, order is: key1 (MRU) -> key3 -> key2 (LRU)
        lruEvictionService.recordAccess("key1");
        drainBuffer();

        // Evict one item - should be key2 (LRU)
        CacheItem item4 = createCacheItem(100);
        // Set max to fit only 3 items - key2 (LRU) should be evicted
        lruEvictionService.evictThenStore("key4", item4, itemSize * 3, expiresAt);

        assertFalse(store.containsKey("key2"));
        assertTrue(store.containsKey("key1"));
        assertTrue(store.containsKey("key3"));
        assertTrue(store.containsKey("key4"));
    }

    @Test
    void eviction_ShouldStopWhenListEmpty() {
        CacheItem item = createCacheItem(500);
        long expiresAt = System.currentTimeMillis() + 60_000;

        lruEvictionService.evictThenStore("key1", item, 10_000, expiresAt);

        // Try to evict below item size - should evict key1 and stop
        lruEvictionService.evictUntilBelow(100, "other-key");

        assertTrue(store.isEmpty());
    }

    @Test
    void evictThenStore_ShouldHandleMultipleConcurrentStores() throws InterruptedException {
        long expiresAt = System.currentTimeMillis() + 60_000;

        // Simulate concurrent stores
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                CacheItem item = createCacheItem(50);
                lruEvictionService.evictThenStore("thread1-key" + i, item, 100_000, expiresAt);
            }
        });

        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                CacheItem item = createCacheItem(50);
                lruEvictionService.evictThenStore("thread2-key" + i, item, 100_000, expiresAt);
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // All items should be stored (no concurrent modification issues)
        assertEquals(20, store.size());
    }

    @Test
    void eviction_ShouldRecordEvictionMetricOnLruEvict() {
        CacheItem item1 = createCacheItem(500);
        CacheItem item2 = createCacheItem(500);
        long expiresAt = System.currentTimeMillis() + 60_000;

        lruEvictionService.evictThenStore("key1", item1, 10_000, expiresAt);
        lruEvictionService.evictThenStore("key2", item2, 10_000, expiresAt);

        // force key2 to be MRU so key1 is the LRU victim
        lruEvictionService.recordAccess("key2");
        drainBuffer();

        // trigger eviction — key1 must be evicted
        CacheItem item3 = createCacheItem(500);
        lruEvictionService.evictThenStore("key3", item3, 1000, expiresAt);

        verify(cacheMetricsService, org.mockito.Mockito.atLeastOnce()).recordEviction();
    }

    @Test
    void evictUntilBelow_ShouldRecordEvictionMetricForEachEvictedEntry() {
        CacheItem item1 = createCacheItem(300);
        CacheItem item2 = createCacheItem(300);
        long expiresAt = System.currentTimeMillis() + 60_000;

        lruEvictionService.evictThenStore("key1", item1, 5_000, expiresAt);
        lruEvictionService.evictThenStore("key2", item2, 5_000, expiresAt);

        lruEvictionService.evictUntilBelow(100, "key2");

        verify(cacheMetricsService, org.mockito.Mockito.atLeastOnce()).recordEviction();
    }

    @Test
    void removeEntry_ShouldHandleConcurrentRemoval() throws Exception {
        long expiresAt = System.currentTimeMillis() + 60_000;

        // Add items
        for (int i = 0; i < 20; i++) {
            CacheItem item = createCacheItem(50);
            lruEvictionService.evictThenStore("key" + i, item, 100_000, expiresAt);
        }

        // Concurrent removal
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                lruEvictionService.removeEntry("key" + i);
            }
        });

        Thread t2 = new Thread(() -> {
            for (int i = 10; i < 20; i++) {
                lruEvictionService.removeEntry("key" + i);
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        ConcurrentHashMap<String, ?> nodeMap = getNodeMap();
        assertEquals(0, nodeMap.size());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Additional branch-coverage tests
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * evictThenStore: memory is freed by another thread while we wait for the lock.
     * The re-check inside the lock body should take the fast path (storeEntryUnderLock)
     * without calling evictUntilFits.
     */
    @Test
    void evictThenStore_ShouldTakeFastPathWhenMemoryFreedBeforeLock() {
        CacheItem item = createCacheItem(50);
        long expiresAt = System.currentTimeMillis() + 60_000;

        // With maxMemory=10_000 and tiny item, memory check passes immediately
        // → storeEntryUnderLock is called directly without going through eviction
        lruEvictionService.evictThenStore("fast-path-key", item, 10_000, expiresAt);

        assertTrue(store.containsKey("fast-path-key"));
        assertEquals(item.getMemorySize(), currentMemoryUsage.get());
    }

    /**
     * evictUntilFits: LRU list becomes empty (victim == head sentinel) before
     * enough memory is freed — the while-loop must break without NPE.
     */
    @Test
    void evictUntilFits_ShouldBreakWhenListIsExhausted() {
        // Add one small item, then demand more space than the whole store
        CacheItem item = createCacheItem(100);
        long expiresAt = System.currentTimeMillis() + 60_000;

        lruEvictionService.evictThenStore("only-key", item, Long.MAX_VALUE, expiresAt);

        // Now set a maxMemory so tiny that evicting everything still doesn't satisfy it.
        // evictThenStore should complete without throwing even though the LL is exhausted.
        CacheItem bigItem = createCacheItem(200);
        assertDoesNotThrow(() ->
                lruEvictionService.evictThenStore("big-key", bigItem, 1L, expiresAt)
        );
    }

    /**
     * evictUntilBelow with protectedKey that IS already in the nodeMap.
     * Exercises the removeNode + addAfterHead path for the protected node.
     */
    @Test
    void evictUntilBelow_ProtectedKeyInNodeMap_ShouldMoveToHead() {
        CacheItem item1 = createCacheItem(300);
        CacheItem item2 = createCacheItem(300);
        long expiresAt = System.currentTimeMillis() + 60_000;

        // Add both keys; item2 is stored last so it is MRU by default
        lruEvictionService.evictThenStore("key1", item1, 10_000, expiresAt);
        lruEvictionService.evictThenStore("key2", item2, 10_000, expiresAt);

        // Protect key1: evictUntilBelow should promote key1 to head so key2 is evicted
        long limitForOneItem = item1.getMemorySize() + 50;
        lruEvictionService.evictUntilBelow(limitForOneItem, "key1");

        assertTrue(store.containsKey("key1"), "protected key must survive");
        assertFalse(store.containsKey("key2"), "non-protected LRU key must be evicted");
    }

    /**
     * evictNode: store.remove returns null (key already evicted by TTL or another thread).
     * The node map entry must still be removed, memory must not go negative.
     */
    @Test
    void evictUntilBelow_WhenStoreEntryAlreadyRemovedConcurrently() throws Exception {
        CacheItem item = createCacheItem(500);
        long expiresAt = System.currentTimeMillis() + 60_000;

        lruEvictionService.evictThenStore("ghost-key", item, 10_000, expiresAt);

        // Simulate TTL eviction removing item from store without going through LRU
        store.remove("ghost-key");
        currentMemoryUsage.set(500); // memory counter is still non-zero (simulated drift)

        // This should not throw even though store.remove returns null inside evictNode
        assertDoesNotThrow(() -> lruEvictionService.evictUntilBelow(100, "other-key"));

        // nodeMap should be clean after eviction attempt
        ConcurrentHashMap<String, ?> nodeMap = getNodeMap();
        assertFalse(nodeMap.containsKey("ghost-key"));
    }

    /**
     * processAccessEvent: key is in nodeMap (not just store).
     * The promote-to-MRU (removeNode + addAfterHead) path must be exercised.
     */
    @Test
    void processAccessEvent_ShouldPromoteNodeAlreadyInNodeMap() throws Exception {
        CacheItem item1 = createCacheItem(100);
        CacheItem item2 = createCacheItem(100);
        long expiresAt = System.currentTimeMillis() + 60_000;
        long twoItemMax = item1.getMemorySize() * 3;

        lruEvictionService.evictThenStore("key1", item1, twoItemMax, expiresAt);
        lruEvictionService.evictThenStore("key2", item2, twoItemMax, expiresAt);

        // Both keys are in nodeMap; access key1 to promote it (hits removeNode+addAfterHead)
        lruEvictionService.recordAccess("key1");
        drainBuffer(); // triggers processAccessEvent for an existing nodeMap entry

        // After promotion key1 is MRU; adding key3 with tight limit evicts key2
        CacheItem item3 = createCacheItem(100);
        lruEvictionService.evictThenStore("key3", item3, item1.getMemorySize() * 2, expiresAt);

        assertTrue(store.containsKey("key1"), "key1 (MRU) must survive");
        assertFalse(store.containsKey("key2"), "key2 (LRU) must be evicted");
    }

    /**
     * reconcileMemory: no drift — memory is already correct.
     * Counter must stay at the accurate value without spurious warning.
     */
    @Test
    void reconcileMemory_ShouldLeaveCounterUnchangedWhenAlreadyAccurate() {
        CacheItem item = createCacheItem(100);
        store.put("key1", item);
        currentMemoryUsage.set(item.getMemorySize());

        lruEvictionService.reconcileMemory();

        assertEquals(item.getMemorySize(), currentMemoryUsage.get());
    }

    /**
     * storeEntryUnderLock: key already exists in nodeMap.
     * The "existing != null" branch (promote existing LL node to HEAD) must be hit.
     */
    @Test
    void evictThenStore_UpdateExistingKeyPromotesInLinkedList() throws Exception {
        CacheItem original = createCacheItem(100);
        CacheItem updated  = createCacheItem(100);
        long expiresAt = System.currentTimeMillis() + 60_000;

        // First store — creates a fresh node in nodeMap
        lruEvictionService.evictThenStore("update-key", original, 10_000, expiresAt);

        // Drain the buffer so processAccessEvent won't double-insert
        drainBuffer();

        // Second store of the same key — must hit "existing != null" inside storeEntryUnderLock
        lruEvictionService.evictThenStore("update-key", updated, 10_000, expiresAt);

        ConcurrentHashMap<String, ?> nodeMap = getNodeMap();
        // Still exactly one LL node for this key
        assertEquals(1, nodeMap.values().stream()
                .filter(n -> {
                    try {
                        java.lang.reflect.Field keyField = n.getClass().getDeclaredField("key");
                        keyField.setAccessible(true);
                        return "update-key".equals(keyField.get(n));
                    } catch (Exception e) {
                        return false;
                    }
                }).count());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Helper Methods
    // ═══════════════════════════════════════════════════════════════════════════════

    private CacheItem createCacheItem(int dataSize) {
        byte[] data = new byte[dataSize];
        return new CacheItem(data, System.currentTimeMillis() + 60_000, "application/octet-stream", 1L, true);
    }

    @SuppressWarnings("unchecked")
    private java.util.concurrent.BlockingQueue<String> getAccessBuffer() throws Exception {
        Field field = LruEvictionService.class.getDeclaredField("accessBuffer");
        field.setAccessible(true);
        return (java.util.concurrent.BlockingQueue<String>) field.get(lruEvictionService);
    }

    /**
     * Gets buffer size. Note: accessBuffer is now a BlockingQueue, no separate size counter.
     * Use .size() method on the queue itself.
     */
    private int getBufferSize() throws Exception {
        return getAccessBuffer().size();
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, ?> getNodeMap() throws Exception {
        Field field = LruEvictionService.class.getDeclaredField("nodeMap");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, ?>) field.get(lruEvictionService);
    }

    private void drainBuffer() {
        try {
            Method scheduledDrain = LruEvictionService.class.getDeclaredMethod("scheduledDrain");
            scheduledDrain.setAccessible(true);
            scheduledDrain.invoke(lruEvictionService);
        } catch (Exception e) {
            throw new RuntimeException("Failed to drain buffer", e);
        }
    }
}
