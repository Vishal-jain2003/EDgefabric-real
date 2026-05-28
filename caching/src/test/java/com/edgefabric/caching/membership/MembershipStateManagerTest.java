package com.edgefabric.caching.membership;

import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MembershipStateManagerTest {

    private final MembershipStateManager manager = new MembershipStateManager();

    @Test
    void bumpHeartbeatIncrementsHeartbeat() {
        NodeInfo node = new NodeInfo("node-1", "127.0.0.1", 8082, 7946);

        long newHeartbeat = manager.bumpHeartbeat(node);

        assertEquals(1, newHeartbeat);
        assertEquals(1, node.getHeartbeat());
    }

    @Test
    void refuteIncreasesIncarnationAboveIncoming() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.SUSPECT, 0, 2);

        long newIncarnation = manager.refute(node, 3);

        assertTrue(newIncarnation > 3);
        assertEquals(Status.ALIVE, node.getStatus());
    }

    @Test
    void markAliveTransitionsFromSuspect() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.SUSPECT, 0, 0);

        assertTrue(manager.markAlive(node));
        assertEquals(Status.ALIVE, node.getStatus());
    }

    @Test
    void markSuspectTransitionsFromAlive() {
        NodeInfo node = new NodeInfo("node-1", "127.0.0.1", 8082, 7946);

        assertTrue(manager.markSuspect(node));
        assertEquals(Status.SUSPECT, node.getStatus());
    }

    @Test
    void markDeadTransitionsFromSuspect() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.SUSPECT, 0, 0);

        assertTrue(manager.markDead(node));
        assertEquals(Status.DEAD, node.getStatus());
    }

    @Test
    void markDrainingTransitionsFromAlive() {
        NodeInfo node = new NodeInfo("node-1", "127.0.0.1", 8082, 7946);

        assertTrue(manager.markDraining(node));
        assertEquals(Status.DRAINING, node.getStatus());
    }

    @Test
    void markDrainingReturnsFalseFromSuspect() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.SUSPECT, 0, 0);

        assertFalse(manager.markDraining(node));
    }

    @Test
    void cancelDrainingTransitionsFromDraining() {
        NodeInfo node = NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.DRAINING, 0, 0);

        assertTrue(manager.cancelDraining(node));
        assertEquals(Status.ALIVE, node.getStatus());
    }

    @Test
    void cancelDrainingReturnsFalseFromAlive() {
        NodeInfo node = new NodeInfo("node-1", "127.0.0.1", 8082, 7946);

        assertFalse(manager.cancelDraining(node));
    }
}
