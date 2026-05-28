package com.edgefabric.caching.metrics;

import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GossipMetricsServiceTest {

    private MeterRegistry registry;
    private MembershipList membershipList;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        membershipList = mock(MembershipList.class);
    }

    @Test
    void reachableNodesGauge_excludesSelf() {
        NodeInfo self = mock(NodeInfo.class);
        NodeInfo peer1 = mock(NodeInfo.class);
        NodeInfo peer2 = mock(NodeInfo.class);
        when(membershipList.getAliveNodes()).thenReturn(List.of(self, peer1, peer2));

        new GossipMetricsService(registry, membershipList);

        Gauge gauge = registry.find("edgefabric.gossip.reachable_nodes").gauge();
        assertNotNull(gauge);
        assertEquals(2.0, gauge.value()); // 3 alive - 1 self = 2
    }

    @Test
    void nodeStatusGauges_registeredForAllStatuses() {
        when(membershipList.getAliveNodes()).thenReturn(List.of());
        when(membershipList.getDigest()).thenReturn(List.of());

        new GossipMetricsService(registry, membershipList);

        for (Status status : Status.values()) {
            Gauge gauge = registry.find("edgefabric.gossip.node_status")
                    .tag("status", status.name())
                    .gauge();
            assertNotNull(gauge, "Gauge missing for status " + status.name());
        }
    }

    @Test
    void nodeStatusGauge_countsCorrectly() {
        NodeInfo alive1 = mock(NodeInfo.class);
        when(alive1.getStatus()).thenReturn(Status.ALIVE);
        NodeInfo alive2 = mock(NodeInfo.class);
        when(alive2.getStatus()).thenReturn(Status.ALIVE);
        NodeInfo suspect = mock(NodeInfo.class);
        when(suspect.getStatus()).thenReturn(Status.SUSPECT);
        NodeInfo dead = mock(NodeInfo.class);
        when(dead.getStatus()).thenReturn(Status.DEAD);

        when(membershipList.getAliveNodes()).thenReturn(List.of(alive1, alive2));
        when(membershipList.getDigest()).thenReturn(List.of(alive1, alive2, suspect, dead));

        new GossipMetricsService(registry, membershipList);

        assertEquals(2.0, registry.find("edgefabric.gossip.node_status")
                .tag("status", "ALIVE").gauge().value());
        assertEquals(1.0, registry.find("edgefabric.gossip.node_status")
                .tag("status", "SUSPECT").gauge().value());
        assertEquals(1.0, registry.find("edgefabric.gossip.node_status")
                .tag("status", "DEAD").gauge().value());
        assertEquals(0.0, registry.find("edgefabric.gossip.node_status")
                .tag("status", "DRAINING").gauge().value());
    }

    @Test
    void reachableNodesGauge_returnsZeroWhenNoAliveNodes() {
        when(membershipList.getAliveNodes()).thenReturn(List.of());

        new GossipMetricsService(registry, membershipList);

        Gauge gauge = registry.find("edgefabric.gossip.reachable_nodes").gauge();
        assertEquals(0.0, gauge.value());
    }
}

