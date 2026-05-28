package com.edgefabric.caching.service;

import com.edgefabric.caching.config.FailureDetectorProperties;
import com.edgefabric.caching.membership.InMemoryMembershipList;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class DeadNodeReaperTest {

    private InMemoryMembershipList membershipList;
    private FailureDetectorProperties properties;
    private Clock baseClock;

    @BeforeEach
    void setUp() {
        NodeInfo self = new NodeInfo("self-node", "127.0.0.1", 8080, 7946);
        membershipList = new InMemoryMembershipList(self);
        properties = new FailureDetectorProperties();
        properties.setDeadNodeTtlMs(5000); // 5 seconds for tests
        baseClock = Clock.systemUTC();
    }

    /** Creates a reaper whose clock is {@code offset} ahead of the real clock. */
    private DeadNodeReaper reaperWithOffset(Duration offset) {
        Clock advanced = Clock.offset(baseClock, offset);
        return new DeadNodeReaper(membershipList, properties, advanced);
    }

    private DeadNodeReaper reaperNow() {
        return new DeadNodeReaper(membershipList, properties, baseClock);
    }

    @Test
    void shouldNotEvictDeadNodeBeforeTtlExpires() {
        NodeInfo node = new NodeInfo("node-2", "10.0.0.2", 8080, 7946);
        membershipList.merge(node);
        membershipList.markDead("node-2");

        // Clock is at real time — TTL of 5000ms hasn't expired yet
        reaperNow().reapDeadNodes();

        assertNotNull(membershipList.getNode("node-2"),
                "Dead node should not be evicted before TTL expires");
        assertEquals(Status.DEAD, membershipList.getNode("node-2").getStatus());
    }

    @Test
    void shouldEvictDeadNodeAfterTtlExpires() {
        NodeInfo node = NodeInfo.getInstance("node-2", "10.0.0.2", 8080, 7946,
                Status.DEAD, 5, 1);
        membershipList.merge(node);

        // Advance the clock 6 seconds into the future — exceeds 5s TTL
        reaperWithOffset(Duration.ofSeconds(6)).reapDeadNodes();

        assertNull(membershipList.getNode("node-2"),
                "Dead node should be evicted after TTL expires");
    }

    @Test
    void shouldNotEvictAliveNodes() {
        NodeInfo node = new NodeInfo("node-2", "10.0.0.2", 8080, 7946);
        membershipList.merge(node);

        // Even with a clock far in the future, alive nodes must never be evicted
        reaperWithOffset(Duration.ofHours(1)).reapDeadNodes();

        assertNotNull(membershipList.getNode("node-2"),
                "Alive nodes must never be evicted");
        assertEquals(Status.ALIVE, membershipList.getNode("node-2").getStatus());
    }

    @Test
    void shouldNotEvictSelfEvenIfDead() {
        membershipList.getSelf().setStatus(Status.DEAD);

        reaperWithOffset(Duration.ofHours(1)).reapDeadNodes();

        assertNotNull(membershipList.getSelf(),
                "Self node must never be evicted regardless of status");
    }

    @Test
    void shouldDoNothingWhenTtlIsDisabled() {
        NodeInfo node = NodeInfo.getInstance("node-2", "10.0.0.2", 8080, 7946,
                Status.DEAD, 0, 0);
        membershipList.merge(node);

        properties.setDeadNodeTtlMs(0); // disabled

        reaperWithOffset(Duration.ofHours(1)).reapDeadNodes();

        assertNotNull(membershipList.getNode("node-2"),
                "Eviction should be disabled when TTL <= 0");
    }

    @Test
    void shouldEvictMultipleDeadNodes() {
        for (int i = 1; i <= 5; i++) {
            NodeInfo node = NodeInfo.getInstance("dead-" + i, "10.0.0." + i, 8080, 7946,
                    Status.DEAD, 0, 0);
            membershipList.merge(node);
        }
        membershipList.merge(new NodeInfo("alive-1", "10.0.1.1", 8080, 7946));

        reaperWithOffset(Duration.ofSeconds(6)).reapDeadNodes();

        for (int i = 1; i <= 5; i++) {
            assertNull(membershipList.getNode("dead-" + i),
                    "Dead node dead-" + i + " should be evicted");
        }
        assertNotNull(membershipList.getNode("alive-1"),
                "Alive node should not be evicted");
        assertEquals(2, membershipList.size()); // self + alive-1
    }

    @Test
    void shouldDecreaseMembershipSizeAfterEviction() {
        NodeInfo node = NodeInfo.getInstance("node-2", "10.0.0.2", 8080, 7946,
                Status.DEAD, 0, 0);
        membershipList.merge(node);
        assertEquals(2, membershipList.size());

        reaperWithOffset(Duration.ofSeconds(6)).reapDeadNodes();

        assertEquals(1, membershipList.size()); // only self remains
    }

    @Test
    void shouldNotEvictSuspectNodes() {
        NodeInfo suspect = NodeInfo.getInstance("node-2", "10.0.0.2", 8080, 7946,
                Status.SUSPECT, 5, 1);
        membershipList.merge(suspect);

        reaperWithOffset(Duration.ofHours(1)).reapDeadNodes();

        assertNotNull(membershipList.getNode("node-2"),
                "Suspect nodes must not be evicted (only DEAD nodes)");
    }

    @Test
    void evictedNodeCanRejoinAsNewNode() {
        NodeInfo node = NodeInfo.getInstance("node-2", "10.0.0.2", 8080, 7946,
                Status.DEAD, 0, 0);
        membershipList.merge(node);

        reaperWithOffset(Duration.ofSeconds(6)).reapDeadNodes();
        assertNull(membershipList.getNode("node-2"));

        // Now the same node restarts and is discovered again
        NodeInfo rejoin = new NodeInfo("node-2", "10.0.0.2", 8080, 7946);
        membershipList.merge(rejoin);

        assertNotNull(membershipList.getNode("node-2"),
                "Evicted node should be able to rejoin as a new node");
        assertEquals(Status.ALIVE, membershipList.getNode("node-2").getStatus());
    }
}
