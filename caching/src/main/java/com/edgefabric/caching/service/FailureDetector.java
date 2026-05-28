package com.edgefabric.caching.service;

import com.edgefabric.caching.config.FailureDetectorProperties;
import com.edgefabric.caching.gossip.PeerSelector;
import com.edgefabric.caching.gossip.SuspectTracker;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.metrics.FailureDetectorMetricsService;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class FailureDetector {

    private final MembershipList membershipList;
    private final PeerSelector peerSelector;
    private final SuspectTracker suspectTracker;
    private final FailureDetectionTransport transport;
    private final FailureDetectorProperties properties;
    private final FailureDetectorMetricsService metricsService;

    public FailureDetector(MembershipList membershipList,
                           PeerSelector peerSelector,
                           SuspectTracker suspectTracker,
                           FailureDetectionTransport transport,
                           FailureDetectorProperties properties,
                           FailureDetectorMetricsService metricsService) {
        this.membershipList = membershipList;
        this.peerSelector = peerSelector;
        this.suspectTracker = suspectTracker;
        this.transport = transport;
        this.properties = properties;
        this.metricsService = metricsService;
    }

    @Scheduled(fixedDelayString = "${failure-detector.probe-interval-ms:1000}")
    public void runFailureDetectionCycle() {
        if (membershipList.getSelf() == null) {
            return;
        }

        // Self-refutation: if gossip or a race left us in SUSPECT/DEAD,
        // bump incarnation and broadcast ALIVE immediately
        refuteSelfIfSuspected();

        Optional<NodeInfo> target = peerSelector.nextTarget();
        target.ifPresent(this::probeNode);

        processSuspectTimeouts();
    }

    /**
     * If this node has been marked SUSPECT or DEAD (e.g. by incoming gossip
     * that hasn't yet been refuted), bump incarnation → ALIVE and mark dirty
     * so the refutation propagates in the next gossip round.
     */
    private void refuteSelfIfSuspected() {
        NodeInfo self = membershipList.getSelf();
        if (self.getStatus() == Status.SUSPECT || self.getStatus() == Status.DEAD) {
            membershipList.refuteSuspicion();
            log.warn("Self was {} — refuted with incarnation {}",
                    self.getStatus(), membershipList.getSelf().getIncarnation());
        }
    }

    private void probeNode(NodeInfo target) {
        Duration timeout = Duration.ofMillis(properties.getPingTimeoutMs());

        log.debug("Direct PING -> {}", target.getCacheNodeId());
        if (transport.sendPing(target, timeout)) {
            metricsService.recordPing("ack");
            markAlive(target.getCacheNodeId(), "direct-ack");
            return;
        }

        List<NodeInfo> helpers = peerSelector.selectHelpers(
                target.getCacheNodeId(),
                properties.getIndirectProbeFanout());

        boolean indirectAck = helpers.stream().anyMatch(helper -> {
            log.debug("Indirect PING_REQ helper={} target={}",
                    helper.getCacheNodeId(),
                    target.getCacheNodeId());
            return transport.sendPingReq(helper, target, timeout);
        });

        if (indirectAck) {
            metricsService.recordPing("ack");
            markAlive(target.getCacheNodeId(), "indirect-ack");
            return;
        }

        metricsService.recordPing("timeout");
        markSuspect(target.getCacheNodeId());
    }

    private void markSuspect(String nodeId) {
        NodeInfo node = membershipList.getNode(nodeId);
        if (node == null || node.getStatus() == Status.DEAD) {
            return;
        }

        if (node.getStatus() == Status.SUSPECT) {
            return;
        }

        membershipList.markSuspect(nodeId);
        suspectTracker.markSuspect(nodeId, System.currentTimeMillis());
        metricsService.recordSuspect();
        log.warn("Node {} transitioned to SUSPECT", nodeId);
    }

    private void processSuspectTimeouts() {
        long now = System.currentTimeMillis();
        List<String> expired = suspectTracker.getExpiredSuspects(now, properties.getSuspectTimeoutMs());

        for (String nodeId : expired) {
            NodeInfo node = membershipList.getNode(nodeId);
            if (node != null && node.getStatus() == Status.SUSPECT) {
                membershipList.markDead(nodeId);
                log.error("Node {} transitioned to DEAD after suspect timeout", nodeId);
            }
            suspectTracker.clear(nodeId);
        }
    }

    private void markAlive(String nodeId, String reason) {
        NodeInfo node = membershipList.getNode(nodeId);
        if (node == null) {
            return;
        }

        if (node.getStatus() != Status.ALIVE && node.getStatus() != Status.DRAINING) {
            // Check if this is a false positive: node was SUSPECT but came back alive
            if (node.getStatus() == Status.SUSPECT) {
                metricsService.recordFalsePositive();
            }
            metricsService.recordAliveTransition();
            membershipList.markAlive(nodeId);
            log.info("Node {} restored to ALIVE via {}", nodeId, reason);
        }

        suspectTracker.clear(nodeId);
    }
}


