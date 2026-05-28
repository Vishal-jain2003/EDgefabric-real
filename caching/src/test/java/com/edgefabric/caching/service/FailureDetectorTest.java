package com.edgefabric.caching.service;

import com.edgefabric.caching.config.FailureDetectorProperties;
import com.edgefabric.caching.gossip.PeerSelector;
import com.edgefabric.caching.gossip.SuspectTracker;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.metrics.FailureDetectorMetricsService;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FailureDetectorTest {

    @Mock
    private MembershipList membershipList;

    @Mock
    private PeerSelector peerSelector;

    @Mock
    private SuspectTracker suspectTracker;

    @Mock
    private FailureDetectionTransport transport;

    @Mock
    private FailureDetectorProperties properties;

    @Mock
    private FailureDetectorMetricsService metricsService;

    @InjectMocks
    private FailureDetector failureDetector;

    private NodeInfo self;
    private NodeInfo target;
    private NodeInfo helper;

    @BeforeEach
    void setUp() {
        self = new NodeInfo("self", "127.0.0.1", 8082, 7946);
        target = new NodeInfo("target", "10.0.0.2", 8082, 7946);
        helper = new NodeInfo("helper", "10.0.0.3", 8082, 7946);

        when(properties.getSuspectTimeoutMs()).thenReturn(7000L);

        when(membershipList.getSelf()).thenReturn(self);
        when(suspectTracker.getExpiredSuspects(anyLong(), anyLong())).thenReturn(List.of());
    }

    @Test
    void shouldMarkAliveOnDirectAck() {
        target.setStatus(Status.SUSPECT);
        when(properties.getPingTimeoutMs()).thenReturn(300L);

        when(peerSelector.nextTarget()).thenReturn(Optional.of(target));
        when(transport.sendPing(eq(target), any(Duration.class))).thenReturn(true);
        when(membershipList.getNode("target")).thenReturn(target);

        failureDetector.runFailureDetectionCycle();

        verify(transport).sendPing(eq(target), any(Duration.class));
        verify(transport, never()).sendPingReq(any(), any(), any());
        verify(membershipList).markAlive("target");
        verify(suspectTracker).clear("target");
    }

    @Test
    void shouldMarkAliveOnIndirectAck() {
        target.setStatus(Status.SUSPECT);
        when(properties.getPingTimeoutMs()).thenReturn(300L);
        when(properties.getIndirectProbeFanout()).thenReturn(3);

        when(peerSelector.nextTarget()).thenReturn(Optional.of(target));
        when(transport.sendPing(eq(target), any(Duration.class))).thenReturn(false);
        when(peerSelector.selectHelpers("target", 3)).thenReturn(List.of(helper));
        when(transport.sendPingReq(eq(helper), eq(target), any(Duration.class))).thenReturn(true);
        when(membershipList.getNode("target")).thenReturn(target);

        failureDetector.runFailureDetectionCycle();

        verify(transport).sendPingReq(eq(helper), eq(target), any(Duration.class));
        verify(membershipList).markAlive("target");
        verify(suspectTracker).clear("target");
    }

    @Test
    void shouldMarkSuspectWhenNoAck() {
        target.setStatus(Status.ALIVE);
        when(properties.getPingTimeoutMs()).thenReturn(300L);
        when(properties.getIndirectProbeFanout()).thenReturn(3);

        when(peerSelector.nextTarget()).thenReturn(Optional.of(target));
        when(transport.sendPing(eq(target), any(Duration.class))).thenReturn(false);
        when(peerSelector.selectHelpers("target", 3)).thenReturn(List.of(helper));
        when(transport.sendPingReq(eq(helper), eq(target), any(Duration.class))).thenReturn(false);
        when(membershipList.getNode("target")).thenReturn(target);

        failureDetector.runFailureDetectionCycle();

        verify(membershipList).markSuspect("target");
        verify(suspectTracker).markSuspect(eq("target"), anyLong());
    }

    @Test
    void shouldNotMarkAliveWhenNodeIsDraining() {
        target.setStatus(Status.DRAINING);
        when(properties.getPingTimeoutMs()).thenReturn(300L);

        when(peerSelector.nextTarget()).thenReturn(Optional.of(target));
        when(transport.sendPing(eq(target), any(Duration.class))).thenReturn(true);
        when(membershipList.getNode("target")).thenReturn(target);

        failureDetector.runFailureDetectionCycle();

        verify(membershipList, never()).markAlive("target");
        verify(suspectTracker).clear("target");
    }

    @Test
    void shouldMarkDeadWhenSuspectTimeoutExpires() {
        NodeInfo suspectNode = new NodeInfo("node-x", "10.0.0.9", 8082, 7946);
        suspectNode.setStatus(Status.SUSPECT);

        when(peerSelector.nextTarget()).thenReturn(Optional.empty());
        when(suspectTracker.getExpiredSuspects(anyLong(), eq(7000L))).thenReturn(List.of("node-x"));
        when(membershipList.getNode("node-x")).thenReturn(suspectNode);

        failureDetector.runFailureDetectionCycle();

        verify(membershipList).markDead("node-x");
        verify(suspectTracker).clear("node-x");
    }

    // ── early return when self is null ──

    @Test
    void shouldReturnEarlyWhenSelfIsNull() {
        when(membershipList.getSelf()).thenReturn(null);

        failureDetector.runFailureDetectionCycle();

        verifyNoInteractions(peerSelector);
        verifyNoInteractions(transport);
    }

    // ── refuteSelfIfSuspected: self is ALIVE, no refute needed ──

    @Test
    void shouldNotRefuteWhenSelfIsAlive() {
        // self is ALIVE by default — refuteSuspicion must NOT be called
        when(peerSelector.nextTarget()).thenReturn(Optional.empty());

        failureDetector.runFailureDetectionCycle();

        verify(membershipList, never()).refuteSuspicion();
    }

    // ── refuteSelfIfSuspected: self is SUSPECT → refute ──

    @Test
    void shouldRefuteWhenSelfIsSuspect() {
        self.setStatus(Status.SUSPECT);
        when(peerSelector.nextTarget()).thenReturn(Optional.empty());

        failureDetector.runFailureDetectionCycle();

        verify(membershipList).refuteSuspicion();
    }

    // ── refuteSelfIfSuspected: self is DEAD → refute ──

    @Test
    void shouldRefuteWhenSelfIsDead() {
        self.setStatus(Status.DEAD);
        when(peerSelector.nextTarget()).thenReturn(Optional.empty());

        failureDetector.runFailureDetectionCycle();

        verify(membershipList).refuteSuspicion();
    }

    // ── processSuspectTimeouts: expired node is null ──

    @Test
    void shouldClearTrackerEvenWhenExpiredNodeIsNull() {
        when(peerSelector.nextTarget()).thenReturn(Optional.empty());
        when(suspectTracker.getExpiredSuspects(anyLong(), eq(7000L))).thenReturn(List.of("ghost-node"));
        when(membershipList.getNode("ghost-node")).thenReturn(null);

        failureDetector.runFailureDetectionCycle();

        // markDead must not be called for a null node
        verify(membershipList, never()).markDead("ghost-node");
        // tracker is still cleared
        verify(suspectTracker).clear("ghost-node");
    }

    // ── processSuspectTimeouts: expired node is ALIVE (not SUSPECT) ──

    @Test
    void shouldNotMarkDeadWhenExpiredNodeIsNoLongerSuspect() {
        NodeInfo aliveNode = new NodeInfo("node-recovered", "10.0.0.5", 8082, 7946);
        // node is ALIVE — suspect timeout expired but node recovered in the meantime

        when(peerSelector.nextTarget()).thenReturn(Optional.empty());
        when(suspectTracker.getExpiredSuspects(anyLong(), eq(7000L))).thenReturn(List.of("node-recovered"));
        when(membershipList.getNode("node-recovered")).thenReturn(aliveNode);

        failureDetector.runFailureDetectionCycle();

        verify(membershipList, never()).markDead("node-recovered");
        verify(suspectTracker).clear("node-recovered");
    }

    // ── markAlive: node is null → early return, no markAlive, no suspectTracker.clear ──

    @Test
    void shouldHandleNullNodeOnDirectAck() {
        when(properties.getPingTimeoutMs()).thenReturn(300L);
        when(peerSelector.nextTarget()).thenReturn(Optional.of(target));
        when(transport.sendPing(eq(target), any(Duration.class))).thenReturn(true);
        // getNode returns null — markAlive must return early without calling membershipList.markAlive
        when(membershipList.getNode("target")).thenReturn(null);

        failureDetector.runFailureDetectionCycle();

        verify(membershipList, never()).markAlive(any());
        // suspectTracker.clear is NOT called when node is null (early return in markAlive)
        verify(suspectTracker, never()).clear("target");
    }

    // ── markSuspect: node is already DEAD ──

    @Test
    void shouldNotMarkSuspectWhenNodeIsAlreadyDead() {
        target.setStatus(Status.DEAD);
        when(properties.getPingTimeoutMs()).thenReturn(300L);
        when(properties.getIndirectProbeFanout()).thenReturn(3);

        when(peerSelector.nextTarget()).thenReturn(Optional.of(target));
        when(transport.sendPing(eq(target), any(Duration.class))).thenReturn(false);
        when(peerSelector.selectHelpers("target", 3)).thenReturn(List.of(helper));
        when(transport.sendPingReq(eq(helper), eq(target), any(Duration.class))).thenReturn(false);
        when(membershipList.getNode("target")).thenReturn(target);

        failureDetector.runFailureDetectionCycle();

        verify(membershipList, never()).markSuspect("target");
    }

    // ── markSuspect: node is null ──

    @Test
    void shouldNotMarkSuspectWhenNodeIsNull() {
        when(properties.getPingTimeoutMs()).thenReturn(300L);
        when(properties.getIndirectProbeFanout()).thenReturn(3);

        when(peerSelector.nextTarget()).thenReturn(Optional.of(target));
        when(transport.sendPing(eq(target), any(Duration.class))).thenReturn(false);
        when(peerSelector.selectHelpers("target", 3)).thenReturn(List.of(helper));
        when(transport.sendPingReq(eq(helper), eq(target), any(Duration.class))).thenReturn(false);
        when(membershipList.getNode("target")).thenReturn(null);

        failureDetector.runFailureDetectionCycle();

        verify(membershipList, never()).markSuspect("target");
    }

    // ── markAlive: node is ALIVE (should not call markAlive again) ──

    @Test
    void shouldNotCallMarkAliveWhenNodeIsAlreadyAlive() {
        // target is ALIVE — markAlive must not be called even on direct ack
        when(properties.getPingTimeoutMs()).thenReturn(300L);

        when(peerSelector.nextTarget()).thenReturn(Optional.of(target));
        when(transport.sendPing(eq(target), any(Duration.class))).thenReturn(true);
        when(membershipList.getNode("target")).thenReturn(target);

        failureDetector.runFailureDetectionCycle();

        // target is ALIVE so no markAlive call — only suspectTracker.clear
        verify(membershipList, never()).markAlive("target");
        verify(suspectTracker).clear("target");
    }

    // ── No helpers available for indirect probe ──

    @Test
    void shouldMarkSuspectWhenNoHelpersAvailable() {
        target.setStatus(Status.ALIVE);
        when(properties.getPingTimeoutMs()).thenReturn(300L);
        when(properties.getIndirectProbeFanout()).thenReturn(3);

        when(peerSelector.nextTarget()).thenReturn(Optional.of(target));
        when(transport.sendPing(eq(target), any(Duration.class))).thenReturn(false);
        when(peerSelector.selectHelpers("target", 3)).thenReturn(List.of()); // no helpers
        when(membershipList.getNode("target")).thenReturn(target);

        failureDetector.runFailureDetectionCycle();

        verify(membershipList).markSuspect("target");
        verify(suspectTracker).markSuspect(eq("target"), anyLong());
    }

    // ── markSuspect: node is already SUSPECT (no duplicate marking) ──

    @Test
    void shouldNotMarkSuspectAgainWhenNodeAlreadySuspect() {
        target.setStatus(Status.SUSPECT);
        when(properties.getPingTimeoutMs()).thenReturn(300L);
        when(properties.getIndirectProbeFanout()).thenReturn(3);

        when(peerSelector.nextTarget()).thenReturn(Optional.of(target));
        when(transport.sendPing(eq(target), any(Duration.class))).thenReturn(false);
        when(peerSelector.selectHelpers("target", 3)).thenReturn(List.of(helper));
        when(transport.sendPingReq(eq(helper), eq(target), any(Duration.class))).thenReturn(false);
        when(membershipList.getNode("target")).thenReturn(target);

        failureDetector.runFailureDetectionCycle();

        verify(membershipList, never()).markSuspect("target");
        verify(suspectTracker, never()).markSuspect(eq("target"), anyLong());
    }
}


