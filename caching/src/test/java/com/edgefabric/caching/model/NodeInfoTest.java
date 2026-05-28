package com.edgefabric.caching.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NodeInfoTest {

    @Test
    void constructorThrowsWhenCacheNodeIdIsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> new NodeInfo(null, "127.0.0.1", 8082, 7946));
    }

    @Test
    void constructorThrowsWhenCacheNodeIdIsBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> new NodeInfo("  ", "127.0.0.1", 8082, 7946));
    }

    @Test
    void constructorThrowsWhenHostIsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> new NodeInfo("node-1", null, 8082, 7946));
    }

    @Test
    void constructorThrowsWhenHostIsBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> new NodeInfo("node-1", "  ", 8082, 7946));
    }

    @Test
    void setStatusUpdatesStatus() {
        NodeInfo node = new NodeInfo("node-1", "127.0.0.1", 8082, 7946);
        assertEquals(Status.ALIVE, node.getStatus());

        node.setStatus(Status.SUSPECT);

        assertEquals(Status.SUSPECT, node.getStatus());
    }

    @Test
    void setIncarnationUpdatesIncarnation() {
        NodeInfo node = new NodeInfo("node-1", "127.0.0.1", 8082, 7946);

        node.setIncarnation(5);

        assertEquals(5, node.getIncarnation());
    }

    @Test
    void snapshotReturnsCurrentState() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.SUSPECT, 3, 2);

        NodeInfo.NodeState snapshot = node.snapshot();

        assertEquals(Status.SUSPECT, snapshot.status());
        assertEquals(3, snapshot.heartbeat());
        assertEquals(2, snapshot.incarnation());
    }

    @Test
    void fromJsonDefaultsToAliveWhenStatusIsNull() {
        NodeInfo node = NodeInfo.fromJson("node-1", "127.0.0.1", 8082, 7946, null, 3, 1);

        assertEquals(Status.ALIVE, node.getStatus());
        assertEquals(3, node.getHeartbeat());
        assertEquals(1, node.getIncarnation());
    }

    @Test
    void transitionToAliveReturnsFalseWhenAlreadyAlive() {
        NodeInfo node = new NodeInfo("node-1", "127.0.0.1", 8082, 7946);
        assertEquals(Status.ALIVE, node.getStatus());

        assertFalse(node.transitionToAlive());
    }

    @Test
    void transitionToAliveReturnsTrueFromSuspect() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.SUSPECT, 0, 0);

        assertTrue(node.transitionToAlive());
        assertEquals(Status.ALIVE, node.getStatus());
    }

    @Test
    void transitionToSuspectReturnsFalseWhenAlreadySuspect() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.SUSPECT, 0, 0);

        assertFalse(node.transitionToSuspect());
    }

    @Test
    void transitionToSuspectReturnsFalseWhenDead() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.DEAD, 0, 0);

        assertFalse(node.transitionToSuspect());
    }

    @Test
    void transitionToDeadReturnsFalseWhenAlreadyDead() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.DEAD, 0, 0);

        assertFalse(node.transitionToDead());
    }

    @Test
    void transitionToDeadReturnsTrueFromAlive() {
        NodeInfo node = new NodeInfo("node-1", "127.0.0.1", 8082, 7946);

        assertTrue(node.transitionToDead());
        assertEquals(Status.DEAD, node.getStatus());
    }

    // ── DRAINING transitions ──

    @Test
    void transitionToDrainingReturnsTrueFromAlive() {
        NodeInfo node = new NodeInfo("node-1", "127.0.0.1", 8082, 7946);

        assertTrue(node.transitionToDraining());
        assertEquals(Status.DRAINING, node.getStatus());
    }

    @Test
    void transitionToDrainingReturnsFalseFromSuspect() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.SUSPECT, 0, 0);

        assertFalse(node.transitionToDraining());
    }

    @Test
    void transitionToDrainingReturnsFalseFromDead() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.DEAD, 0, 0);

        assertFalse(node.transitionToDraining());
    }

    @Test
    void transitionToDrainingReturnsFalseWhenAlreadyDraining() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.DRAINING, 0, 0);

        assertFalse(node.transitionToDraining());
    }

    @Test
    void transitionFromDrainingToAliveReturnsTrueFromDraining() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.DRAINING, 0, 0);

        assertTrue(node.transitionFromDrainingToAlive());
        assertEquals(Status.ALIVE, node.getStatus());
    }

    @Test
    void transitionFromDrainingToAliveReturnsFalseFromAlive() {
        NodeInfo node = new NodeInfo("node-1", "127.0.0.1", 8082, 7946);

        assertFalse(node.transitionFromDrainingToAlive());
    }

    @Test
    void transitionToSuspectBlockedFromDraining() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.DRAINING, 0, 0);

        assertFalse(node.transitionToSuspect());
        assertEquals(Status.DRAINING, node.getStatus());
    }

    @Test
    void refutePreservesDrainingStatus() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.DRAINING, 0, 5);

        long newIncarnation = node.refute(10);

        assertTrue(newIncarnation > 10);
        assertEquals(Status.DRAINING, node.getStatus());
    }

    @Test
    void refuteFromAliveResultsInAlive() {
        NodeInfo node = new NodeInfo("node-1", "127.0.0.1", 8082, 7946);

        node.refute(5);

        assertEquals(Status.ALIVE, node.getStatus());
    }

    // ── bumpHeartbeat increments from non-zero baseline ──

    @Test
    void bumpHeartbeatIncrementsCorrectly() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.ALIVE, 10, 0);

        long newHeartbeat = node.bumpHeartbeat();

        assertEquals(11, newHeartbeat);
        assertEquals(11, node.getHeartbeat());
    }

    @Test
    void bumpHeartbeatPreservesStatus() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.SUSPECT, 5, 2);

        node.bumpHeartbeat();

        assertEquals(Status.SUSPECT, node.getStatus());
        assertEquals(2, node.getIncarnation());
    }

    // ── refute: when old.incarnation > incomingIncarnation ──

    @Test
    void refuteUsesMaxWhenOldIncarnationHigher() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.ALIVE, 0, 10);

        // old incarnation (10) > incoming (3) → new incarnation = max(10,3) + 1 = 11
        long newInc = node.refute(3);

        assertEquals(11, newInc);
        assertEquals(11, node.getIncarnation());
    }

    @Test
    void refuteUsesMaxWhenIncomingIncarnationHigher() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.ALIVE, 0, 2);

        // incoming (8) > old (2) → new incarnation = max(2,8) + 1 = 9
        long newInc = node.refute(8);

        assertEquals(9, newInc);
        assertEquals(9, node.getIncarnation());
    }

    // ── transitionToAlive from DEAD ──

    @Test
    void transitionToAliveReturnsTrueFromDead() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.DEAD, 0, 0);

        assertTrue(node.transitionToAlive());
        assertEquals(Status.ALIVE, node.getStatus());
    }

    // ── transitionToAlive from DRAINING ──

    @Test
    void transitionToAliveReturnsTrueFromDraining() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.DRAINING, 0, 0);

        assertTrue(node.transitionToAlive());
        assertEquals(Status.ALIVE, node.getStatus());
    }

    // ── applyUpdate sets all fields atomically ──

    @Test
    void applyUpdateSetsStatusHeartbeatAndIncarnation() {
        NodeInfo node = new NodeInfo("node-1", "127.0.0.1", 8082, 7946);

        node.applyUpdate(Status.SUSPECT, 42L, 7L);

        assertEquals(Status.SUSPECT, node.getStatus());
        assertEquals(42L, node.getHeartbeat());
        assertEquals(7L, node.getIncarnation());
    }

    // ── transitionToDead from SUSPECT ──

    @Test
    void transitionToDeadReturnsTrueFromSuspect() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.SUSPECT, 0, 0);

        assertTrue(node.transitionToDead());
        assertEquals(Status.DEAD, node.getStatus());
    }

    // ── transitionToSuspect from ALIVE ──

    @Test
    void transitionToSuspectReturnsTrueFromAlive() {
        NodeInfo node = new NodeInfo("node-1", "127.0.0.1", 8082, 7946);

        assertTrue(node.transitionToSuspect());
        assertEquals(Status.SUSPECT, node.getStatus());
    }

    // ── transitionFromDrainingToAlive bumps incarnation ──

    @Test
    void transitionFromDrainingToAliveBumpsIncarnation() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.DRAINING, 0, 5);
        long incBefore = node.getIncarnation();

        node.transitionFromDrainingToAlive();

        assertEquals(incBefore + 1, node.getIncarnation());
    }

    // ── transitionFromDrainingToAlive returns false from DEAD ──

    @Test
    void transitionFromDrainingToAliveReturnsFalseFromDead() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.DEAD, 0, 0);

        assertFalse(node.transitionFromDrainingToAlive());
        assertEquals(Status.DEAD, node.getStatus());
    }

    // ── setStatus preserves other fields ──

    @Test
    void setStatusPreservesHeartbeatAndIncarnation() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.ALIVE, 7, 3);

        node.setStatus(Status.DEAD);

        assertEquals(Status.DEAD, node.getStatus());
        assertEquals(7, node.getHeartbeat());
        assertEquals(3, node.getIncarnation());
    }

    // ── setIncarnation preserves status and heartbeat ──

    @Test
    void setIncarnationPreservesStatusAndHeartbeat() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.SUSPECT, 5, 1);

        node.setIncarnation(99);

        assertEquals(Status.SUSPECT, node.getStatus());
        assertEquals(5, node.getHeartbeat());
        assertEquals(99, node.getIncarnation());
    }

    // ── getInstance constructs with correct fields ──

    @Test
    void getInstanceSetsAllFields() {
        NodeInfo node = NodeInfo.getInstance("n1", "10.0.0.1", 8082, 7946, Status.DRAINING, 15, 4);

        assertEquals("n1", node.getCacheNodeId());
        assertEquals("10.0.0.1", node.getHost());
        assertEquals(8082, node.getServicePort());
        assertEquals(7946, node.getGossipPort());
        assertEquals(Status.DRAINING, node.getStatus());
        assertEquals(15, node.getHeartbeat());
        assertEquals(4, node.getIncarnation());
    }

    // ── snapshot reflects current state ──

    @Test
    void snapshotReflectsCurrentStateAfterUpdate() {
        NodeInfo node = new NodeInfo("node-1", "127.0.0.1", 8082, 7946);
        node.applyUpdate(Status.DEAD, 20, 8);

        NodeInfo.NodeState snap = node.snapshot();

        assertEquals(Status.DEAD, snap.status());
        assertEquals(20, snap.heartbeat());
        assertEquals(8, snap.incarnation());
    }

    // ── refute preserves heartbeat ──

    @Test
    void refutePreservesHeartbeat() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.ALIVE, 42, 0);

        node.refute(3);

        assertEquals(42, node.getHeartbeat());
    }
}
