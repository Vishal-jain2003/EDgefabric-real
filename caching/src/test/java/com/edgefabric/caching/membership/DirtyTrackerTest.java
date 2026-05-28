package com.edgefabric.caching.membership;

import com.edgefabric.caching.model.NodeInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DirtyTrackerTest {

    private DirtyTracker dirtyTracker;
    private MembershipStore store;

    @BeforeEach
    void setUp() {
        dirtyTracker = new DirtyTracker();
        store = new MembershipStore();
    }

    @Test
    void shouldReturnEmptyWhenNothingIsDirty() {
        List<NodeInfo> result = dirtyTracker.getAndClear(store);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnDirtyNodesThatExistInStore() {
        NodeInfo node = new NodeInfo("node-1", "10.0.0.1", 8080, 7946);
        store.put(node);
        dirtyTracker.markDirty("node-1");

        List<NodeInfo> result = dirtyTracker.getAndClear(store);
        assertEquals(1, result.size());
        assertEquals("node-1", result.getFirst().getCacheNodeId());
    }

    @Test
    void shouldClearDirtySetAfterGetAndClear() {
        NodeInfo node = new NodeInfo("node-1", "10.0.0.1", 8080, 7946);
        store.put(node);
        dirtyTracker.markDirty("node-1");

        dirtyTracker.getAndClear(store);

        // Second call should return empty since dirty set was cleared
        List<NodeInfo> secondResult = dirtyTracker.getAndClear(store);
        assertTrue(secondResult.isEmpty());
    }

    @Test
    void shouldSkipDirtyNodeNotInStore() {
        dirtyTracker.markDirty("ghost-node");

        List<NodeInfo> result = dirtyTracker.getAndClear(store);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnMultipleDirtyNodes() {
        NodeInfo node1 = new NodeInfo("node-1", "10.0.0.1", 8080, 7946);
        NodeInfo node2 = new NodeInfo("node-2", "10.0.0.2", 8080, 7947);
        store.put(node1);
        store.put(node2);

        dirtyTracker.markDirty("node-1");
        dirtyTracker.markDirty("node-2");

        List<NodeInfo> result = dirtyTracker.getAndClear(store);
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(n -> "node-1".equals(n.getCacheNodeId())));
        assertTrue(result.stream().anyMatch(n -> "node-2".equals(n.getCacheNodeId())));
    }

    @Test
    void shouldHandleMixOfExistingAndMissingNodes() {
        NodeInfo node1 = new NodeInfo("node-1", "10.0.0.1", 8080, 7946);
        store.put(node1);

        dirtyTracker.markDirty("node-1");
        dirtyTracker.markDirty("missing-node");

        List<NodeInfo> result = dirtyTracker.getAndClear(store);
        assertEquals(1, result.size());
        assertEquals("node-1", result.getFirst().getCacheNodeId());
    }

    @Test
    void shouldNotDuplicateWhenMarkDirtyCalledTwice() {
        NodeInfo node = new NodeInfo("node-1", "10.0.0.1", 8080, 7946);
        store.put(node);

        dirtyTracker.markDirty("node-1");
        dirtyTracker.markDirty("node-1");

        List<NodeInfo> result = dirtyTracker.getAndClear(store);
        assertEquals(1, result.size());
    }
}

