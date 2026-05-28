package com.edgefabric.caching.service;

import com.edgefabric.caching.membership.InMemoryMembershipList;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class DrainServiceTest {

    private InMemoryMembershipList membershipList;
    private MeterRegistry meterRegistry;
    private DrainService drainService;

    @BeforeEach
    void setUp() {
        NodeInfo self = new NodeInfo("self-node", "127.0.0.1", 8082, 7946);
        membershipList = new InMemoryMembershipList(self);
        meterRegistry = new SimpleMeterRegistry();
        drainService = new DrainService(membershipList, meterRegistry);
        // Set grace period to 1 second for faster tests
        ReflectionTestUtils.setField(drainService, "drainGracePeriodMs", 1000L);
    }

    @Test
    void startDrainFromAliveReturnsTrue() {
        assertTrue(drainService.startDrain());
        assertEquals(Status.DRAINING, membershipList.getSelf().getStatus());
    }

    @Test
    void startDrainWhenAlreadyDrainingReturnsFalse() {
        drainService.startDrain();

        assertFalse(drainService.startDrain());
    }

    @Test
    void startDrainWhenSuspectReturnsFalse() {
        membershipList.getSelf().setStatus(Status.SUSPECT);

        assertFalse(drainService.startDrain());
    }

    @Test
    void cancelDrainFromDrainingReturnsTrue() {
        drainService.startDrain();

        assertTrue(drainService.cancelDrain());
        assertEquals(Status.ALIVE, membershipList.getSelf().getStatus());
    }

    @Test
    void cancelDrainWhenAliveReturnsFalse() {
        assertFalse(drainService.cancelDrain());
    }

    @Test
    void isDrainingReturnsTrueWhenDraining() {
        assertFalse(drainService.isDraining());

        drainService.startDrain();

        assertTrue(drainService.isDraining());
    }

    @Test
    void gaugeIsOneWhenDraining() {
        drainService.startDrain();

        double gaugeValue = meterRegistry.get("edgefabric.node.drain.active").gauge().value();
        assertEquals(1.0, gaugeValue);
    }

    @Test
    void gaugeIsZeroAfterCancelDrain() {
        drainService.startDrain();
        drainService.cancelDrain();

        double gaugeValue = meterRegistry.get("edgefabric.node.drain.active").gauge().value();
        assertEquals(0.0, gaugeValue);
    }

    @Test
    void gaugeIsZeroInitially() {
        double gaugeValue = meterRegistry.get("edgefabric.node.drain.active").gauge().value();
        assertEquals(0.0, gaugeValue);
    }

    @Test
    void isDrainingWithinGracePeriodReturnsTrueInitially() {
        drainService.startDrain();
        assertTrue(drainService.isDrainingWithinGracePeriod());
    }

    @Test
    void isDrainingWithinGracePeriodReturnsFalseWhenNotDraining() {
        assertFalse(drainService.isDrainingWithinGracePeriod());
    }

    @Test
    void isDrainingWithinGracePeriodReturnsFalseAfterExpiry() throws InterruptedException {
        drainService.startDrain();
        assertTrue(drainService.isDrainingWithinGracePeriod());

        // Wait for grace period to expire (1 second)
        Thread.sleep(1100);

        assertFalse(drainService.isDrainingWithinGracePeriod());
    }

    @Test
    void cancelDrainClearsGracePeriodTracking() {
        drainService.startDrain();
        assertTrue(drainService.isDrainingWithinGracePeriod());

        drainService.cancelDrain();

        assertFalse(drainService.isDrainingWithinGracePeriod());
    }
}

