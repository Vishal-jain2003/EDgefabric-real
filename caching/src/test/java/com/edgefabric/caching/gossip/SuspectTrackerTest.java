package com.edgefabric.caching.gossip;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SuspectTrackerTest {

    private SuspectTracker suspectTracker;

    @BeforeEach
    void setUp() {
        suspectTracker = new SuspectTracker();
    }

    @Test
    void shouldMarkNodeAsSuspect() {
        suspectTracker.markSuspect("node-1", 1000L);

        List<String> expired = suspectTracker.getExpiredSuspects(5000L, 3000L);
        assertEquals(1, expired.size());
        assertEquals("node-1", expired.getFirst());
    }

    @Test
    void shouldNotOverwriteExistingSuspectTimestamp() {
        suspectTracker.markSuspect("node-1", 1000L);
        suspectTracker.markSuspect("node-1", 9000L); // putIfAbsent — should keep 1000

        // With timeout=3000, at now=5000 the entry (1000) is expired (5000-1000=4000 > 3000)
        List<String> expired = suspectTracker.getExpiredSuspects(5000L, 3000L);
        assertEquals(1, expired.size());
        assertEquals("node-1", expired.getFirst());
    }

    @Test
    void shouldClearSuspect() {
        suspectTracker.markSuspect("node-1", 1000L);
        suspectTracker.clear("node-1");

        List<String> expired = suspectTracker.getExpiredSuspects(99999L, 0L);
        assertTrue(expired.isEmpty());
    }

    @Test
    void shouldClearNonExistentNodeWithoutError() {
        assertDoesNotThrow(() -> suspectTracker.clear("non-existent"));
    }

    @Test
    void shouldReturnOnlyExpiredSuspects() {
        suspectTracker.markSuspect("node-1", 1000L); // expired at now=5000, timeout=3000
        suspectTracker.markSuspect("node-2", 4000L); // NOT expired at now=5000, timeout=3000

        List<String> expired = suspectTracker.getExpiredSuspects(5000L, 3000L);
        assertEquals(1, expired.size());
        assertEquals("node-1", expired.getFirst());
    }

    @Test
    void shouldReturnEmptyWhenNoSuspectsExist() {
        List<String> expired = suspectTracker.getExpiredSuspects(5000L, 3000L);
        assertTrue(expired.isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenNoSuspectsExpired() {
        suspectTracker.markSuspect("node-1", 4500L);

        List<String> expired = suspectTracker.getExpiredSuspects(5000L, 3000L);
        assertTrue(expired.isEmpty());
    }

    @Test
    void shouldReturnMultipleExpiredSuspects() {
        suspectTracker.markSuspect("node-1", 1000L);
        suspectTracker.markSuspect("node-2", 500L);
        suspectTracker.markSuspect("node-3", 2000L);

        List<String> expired = suspectTracker.getExpiredSuspects(5000L, 2500L);
        assertEquals(3, expired.size());
        assertTrue(expired.containsAll(List.of("node-1", "node-2", "node-3")));
    }

    @Test
    void shouldHandleExactBoundary() {
        suspectTracker.markSuspect("node-1", 2000L);

        // nowMs - entry.getValue() == timeoutMs  →  NOT expired (filter uses >)
        List<String> expired = suspectTracker.getExpiredSuspects(5000L, 3000L);
        assertTrue(expired.isEmpty());
    }
}

