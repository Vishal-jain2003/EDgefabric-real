package com.edgefabric.agentops.explain;

import com.edgefabric.agentops.observe.ClusterSnapshot;
import com.edgefabric.agentops.observe.NodeSnapshot;
import com.edgefabric.agentops.observe.ObserveService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExplainServiceTest {

    @Mock
    private ObserveService observeService;

    @InjectMocks
    private ExplainService explainService;

    // ── explainClusterHealth ───────────────────────────────────────────────

    @Test
    void clusterHealth_nullSnapshot_returnsCriticalUnavailable() {
        when(observeService.getSnapshot()).thenReturn(null);
        Diagnosis d = explainService.explainClusterHealth();
        assertThat(d.severity()).isEqualTo("CRITICAL");
        assertThat(d.rootCause()).contains("Observe data unavailable");
    }

    @Test
    void clusterHealth_deadNodes_detectsNodeIsolation() {
        NodeSnapshot dead = node("n1", "DEAD", null, null, null, null, null);
        NodeSnapshot alive = node("n2", "HEALTHY", true, null, null, null, null);
        // healthyNodes=2 to avoid quorum_failure override; dead node triggers node_isolation
        ClusterSnapshot snap = snapshot(3, 2, 0, 1, List.of(dead, alive));
        when(observeService.getSnapshot()).thenReturn(snap);

        Diagnosis d = explainService.explainClusterHealth();
        assertThat(d.severity()).isEqualTo("CRITICAL");
        assertThat(d.failureMode()).isEqualTo("node_isolation");
    }

    @Test
    void clusterHealth_unreachableNode_detectsNodeIsolation() {
        NodeSnapshot unreachable = node("n2", "UNREACHABLE", null, null, null, null, null);
        NodeSnapshot alive = node("n1", "HEALTHY", true, null, null, null, null);
        ClusterSnapshot snap = snapshot(3, 2, 0, 0, List.of(unreachable, alive));
        when(observeService.getSnapshot()).thenReturn(snap);

        Diagnosis d = explainService.explainClusterHealth();
        assertThat(d.failureMode()).isEqualTo("node_isolation");
    }

    @Test
    void clusterHealth_lbNotReachable_detectsNodeIsolation() {
        NodeSnapshot n = node("n1", "HEALTHY", false, null, null, null, null);
        NodeSnapshot alive = node("n2", "HEALTHY", true, null, null, null, null);
        ClusterSnapshot snap = snapshot(3, 2, 0, 0, List.of(n, alive));
        when(observeService.getSnapshot()).thenReturn(snap);

        Diagnosis d = explainService.explainClusterHealth();
        assertThat(d.failureMode()).isEqualTo("node_isolation");
    }

    @Test
    void clusterHealth_healthyNodesLessThanTwo_detectsQuorumFailure() {
        NodeSnapshot n = node("n1", "HEALTHY", true, null, null, null, null);
        ClusterSnapshot snap = snapshot(1, 1, 0, 0, List.of(n));
        when(observeService.getSnapshot()).thenReturn(snap);

        Diagnosis d = explainService.explainClusterHealth();
        assertThat(d.failureMode()).isEqualTo("quorum_failure");
        assertThat(d.severity()).isEqualTo("CRITICAL");
    }

    @Test
    void clusterHealth_highMemory_detectsMemoryPressure() {
        NodeSnapshot n = node("n1", "HEALTHY", true, 0.91, null, null, null);
        ClusterSnapshot snap = snapshot(3, 3, 0, 0, List.of(n));
        when(observeService.getSnapshot()).thenReturn(snap);

        Diagnosis d = explainService.explainClusterHealth();
        assertThat(d.failureMode()).isEqualTo("cache_memory_pressure");
    }

    @Test
    void clusterHealth_highEvictions_detectsMemoryPressure() {
        NodeSnapshot n = node("n1", "HEALTHY", true, null, 150.0, null, null);
        ClusterSnapshot snap = snapshot(3, 3, 0, 0, List.of(n));
        when(observeService.getSnapshot()).thenReturn(snap);

        Diagnosis d = explainService.explainClusterHealth();
        assertThat(d.failureMode()).isEqualTo("cache_memory_pressure");
    }

    @Test
    void clusterHealth_largeMigrationQueue_detectsRingRebalancing() {
        NodeSnapshot n = node("n1", "HEALTHY", true, null, null, 15L, false);
        ClusterSnapshot snap = snapshot(3, 3, 0, 0, List.of(n));
        when(observeService.getSnapshot()).thenReturn(snap);

        Diagnosis d = explainService.explainClusterHealth();
        assertThat(d.failureMode()).isEqualTo("ring_rebalancing_lag");
    }

    @Test
    void clusterHealth_drainActive_detectsRingRebalancing() {
        NodeSnapshot n = node("n1", "HEALTHY", true, null, null, null, true);
        ClusterSnapshot snap = snapshot(3, 3, 0, 0, List.of(n));
        when(observeService.getSnapshot()).thenReturn(snap);

        Diagnosis d = explainService.explainClusterHealth();
        assertThat(d.failureMode()).isEqualTo("ring_rebalancing_lag");
    }

    @Test
    void clusterHealth_allHealthy_returnsOk() {
        NodeSnapshot n = node("n1", "HEALTHY", true, 0.5, 10.0, 0L, false);
        ClusterSnapshot snap = snapshot(3, 3, 0, 0, List.of(n));
        when(observeService.getSnapshot()).thenReturn(snap);

        Diagnosis d = explainService.explainClusterHealth();
        assertThat(d.severity()).isEqualTo("OK");
        assertThat(d.failureMode()).isNull();
    }

    @Test
    void clusterHealth_quorumOverridesNodeIsolation() {
        // dead node AND healthy < 2 → quorum_failure wins (more severe)
        NodeSnapshot dead = node("n1", "DEAD", null, null, null, null, null);
        ClusterSnapshot snap = snapshot(2, 1, 0, 1, List.of(dead));
        when(observeService.getSnapshot()).thenReturn(snap);

        Diagnosis d = explainService.explainClusterHealth();
        assertThat(d.failureMode()).isEqualTo("quorum_failure");
    }

    // ── explainNodeAnomaly ─────────────────────────────────────────────────

    @Test
    void nodeAnomaly_nullNode_returnsError() {
        when(observeService.getNodeHealth("missing")).thenReturn(null);
        Diagnosis d = explainService.explainNodeAnomaly("missing");
        assertThat(d.severity()).isEqualTo("ERROR");
        assertThat(d.rootCause()).contains("not found");
    }

    @Test
    void nodeAnomaly_deadStatus_detectsNodeIsolation() {
        NodeSnapshot n = node("n1", "DEAD", null, null, null, null, null);
        when(observeService.getNodeHealth("n1")).thenReturn(n);

        Diagnosis d = explainService.explainNodeAnomaly("n1");
        assertThat(d.failureMode()).isEqualTo("node_isolation");
        assertThat(d.affectedComponent()).isEqualTo("n1");
    }

    @Test
    void nodeAnomaly_staleGossip_detectsNodeIsolation() {
        NodeSnapshot n = nodeWithGossip("n1", "HEALTHY", 90L);
        when(observeService.getNodeHealth("n1")).thenReturn(n);

        Diagnosis d = explainService.explainNodeAnomaly("n1");
        assertThat(d.failureMode()).isEqualTo("node_isolation");
    }

    @Test
    void nodeAnomaly_highMemory_detectsMemoryPressure() {
        NodeSnapshot n = node("n1", "HEALTHY", true, 0.92, null, null, null);
        when(observeService.getNodeHealth("n1")).thenReturn(n);

        Diagnosis d = explainService.explainNodeAnomaly("n1");
        assertThat(d.failureMode()).isEqualTo("cache_memory_pressure");
    }

    @Test
    void nodeAnomaly_healthy_returnsOk() {
        NodeSnapshot n = node("n1", "HEALTHY", true, 0.4, 5.0, 0L, false);
        when(observeService.getNodeHealth("n1")).thenReturn(n);

        Diagnosis d = explainService.explainNodeAnomaly("n1");
        assertThat(d.severity()).isEqualTo("OK");
    }

    // ── explainSlo ─────────────────────────────────────────────────────────

    @Test
    void slo_nullStatus_returnsCritical() {
        when(observeService.getSloStatus()).thenReturn(null);
        Diagnosis d = explainService.explainSlo();
        assertThat(d.severity()).isEqualTo("CRITICAL");
    }

    @Test
    void slo_highBurnRate_returnsCritical() {
        ObserveService.SloStatus slo = new ObserveService.SloStatus(0.999, 2.5, 0.001, "DEGRADED", Instant.now());
        when(observeService.getSloStatus()).thenReturn(slo);

        Diagnosis d = explainService.explainSlo();
        assertThat(d.severity()).isEqualTo("CRITICAL");
        assertThat(d.rootCause()).contains("burn rate critical");
    }

    @Test
    void slo_lowAvailability_returnsWarning() {
        ObserveService.SloStatus slo = new ObserveService.SloStatus(0.990, 0.5, 0.001, "DEGRADED", Instant.now());
        when(observeService.getSloStatus()).thenReturn(slo);

        Diagnosis d = explainService.explainSlo();
        assertThat(d.severity()).isEqualTo("WARNING");
    }

    @Test
    void slo_elevatedErrorRate_returnsWarning() {
        ObserveService.SloStatus slo = new ObserveService.SloStatus(0.9999, 0.5, 0.05, "OK", Instant.now());
        when(observeService.getSloStatus()).thenReturn(slo);

        Diagnosis d = explainService.explainSlo();
        assertThat(d.severity()).isEqualTo("WARNING");
    }

    @Test
    void slo_allGood_returnsOk() {
        ObserveService.SloStatus slo = new ObserveService.SloStatus(0.9999, 0.3, 0.001, "HEALTHY", Instant.now());
        when(observeService.getSloStatus()).thenReturn(slo);

        Diagnosis d = explainService.explainSlo();
        assertThat(d.severity()).isEqualTo("OK");
    }

    @Test
    void slo_nullMetrics_includesDataUnavailableEvidence() {
        ObserveService.SloStatus slo = new ObserveService.SloStatus(null, null, null, "UNKNOWN", Instant.now());
        when(observeService.getSloStatus()).thenReturn(slo);

        Diagnosis d = explainService.explainSlo();
        assertThat(d.evidence()).anyMatch(e -> e.contains("data_unavailable"));
    }

    // ── explainLatency ─────────────────────────────────────────────────────

    @Test
    void latency_nullProfiles_returnsCritical() {
        when(observeService.getLatencyProfile()).thenReturn(null);
        Diagnosis d = explainService.explainLatency(null);
        assertThat(d.severity()).isEqualTo("CRITICAL");
    }

    @Test
    void latency_emptyProfiles_returnsCritical() {
        when(observeService.getLatencyProfile()).thenReturn(List.of());
        Diagnosis d = explainService.explainLatency(null);
        assertThat(d.severity()).isEqualTo("CRITICAL");
    }

    @Test
    void latency_criticalP99_returnsCritical() {
        List<ObserveService.NodeLatencyProfile> profiles = List.of(
                new ObserveService.NodeLatencyProfile("n1", 10.0, 600.0, "CRITICAL"));
        when(observeService.getLatencyProfile()).thenReturn(profiles);

        Diagnosis d = explainService.explainLatency(null);
        assertThat(d.severity()).isEqualTo("CRITICAL");
    }

    @Test
    void latency_slowP99_returnsWarning() {
        List<ObserveService.NodeLatencyProfile> profiles = List.of(
                new ObserveService.NodeLatencyProfile("n1", 10.0, 250.0, "SLOW"));
        when(observeService.getLatencyProfile()).thenReturn(profiles);

        Diagnosis d = explainService.explainLatency(null);
        assertThat(d.severity()).isEqualTo("WARNING");
    }

    @Test
    void latency_fastNodes_returnsOk() {
        List<ObserveService.NodeLatencyProfile> profiles = List.of(
                new ObserveService.NodeLatencyProfile("n1", 2.0, 5.0, "FAST"));
        when(observeService.getLatencyProfile()).thenReturn(profiles);

        Diagnosis d = explainService.explainLatency(null);
        assertThat(d.severity()).isEqualTo("OK");
    }

    @Test
    void latency_filterByNodeId_onlyMatchingNode() {
        List<ObserveService.NodeLatencyProfile> profiles = List.of(
                new ObserveService.NodeLatencyProfile("n1", 2.0, 5.0, "FAST"),
                new ObserveService.NodeLatencyProfile("n2", 10.0, 600.0, "CRITICAL"));
        when(observeService.getLatencyProfile()).thenReturn(profiles);

        Diagnosis d = explainService.explainLatency("n1");
        assertThat(d.severity()).isEqualTo("OK");
    }

    // ── explainSelfHealing ─────────────────────────────────────────────────

    @Test
    void selfHealing_nullStatus_returnsCritical() {
        when(observeService.getSelfHealingStatus()).thenReturn(null);
        Diagnosis d = explainService.explainSelfHealing();
        assertThat(d.severity()).isEqualTo("CRITICAL");
    }

    @Test
    void selfHealing_drainActiveWithLargeQueue_returnsWarning() {
        ObserveService.NodeHealingStats stats = new ObserveService.NodeHealingStats("n1", 5L, 10L, 15L, true);
        ObserveService.SelfHealingStatus status = new ObserveService.SelfHealingStatus(List.of(stats), 10L, Instant.now());
        when(observeService.getSelfHealingStatus()).thenReturn(status);

        Diagnosis d = explainService.explainSelfHealing();
        assertThat(d.severity()).isEqualTo("WARNING");
        assertThat(d.failureMode()).isEqualTo("ring_rebalancing_lag");
    }

    @Test
    void selfHealing_highRepairCount_returnsWarning() {
        ObserveService.NodeHealingStats stats = new ObserveService.NodeHealingStats("n1", 5L, 60L, 0L, false);
        ObserveService.SelfHealingStatus status = new ObserveService.SelfHealingStatus(List.of(stats), 60L, Instant.now());
        when(observeService.getSelfHealingStatus()).thenReturn(status);

        Diagnosis d = explainService.explainSelfHealing();
        assertThat(d.severity()).isEqualTo("WARNING");
    }

    @Test
    void selfHealing_normal_returnsOk() {
        ObserveService.NodeHealingStats stats = new ObserveService.NodeHealingStats("n1", 1L, 2L, 0L, false);
        ObserveService.SelfHealingStatus status = new ObserveService.SelfHealingStatus(List.of(stats), 2L, Instant.now());
        when(observeService.getSelfHealingStatus()).thenReturn(status);

        Diagnosis d = explainService.explainSelfHealing();
        assertThat(d.severity()).isEqualTo("OK");
    }

    @Test
    void selfHealing_drainActiveSmallQueue_returnsInfo() {
        ObserveService.NodeHealingStats stats = new ObserveService.NodeHealingStats("n1", 1L, 2L, 3L, true);
        ObserveService.SelfHealingStatus status = new ObserveService.SelfHealingStatus(List.of(stats), 2L, Instant.now());
        when(observeService.getSelfHealingStatus()).thenReturn(status);

        Diagnosis d = explainService.explainSelfHealing();
        assertThat(d.severity()).isEqualTo("INFO");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private ClusterSnapshot snapshot(int total, int healthy, int suspect, int dead, List<NodeSnapshot> nodes) {
        return new ClusterSnapshot(total, healthy, suspect, dead, Instant.now(),
                null, nodes, 0L, 0L, 0.9, null, null, null);
    }

    private NodeSnapshot node(String id, String status, Boolean lbReachable,
                               Double memRatio, Double evictions, Long migQueue, Boolean drain) {
        return new NodeSnapshot(id, "host", 8082, status, lbReachable,
                0.9, 0.1, 100L, 10L, 1000L, 1024L,
                memRatio, evictions, 5.0, 10.0, drain, 1L, 2L, migQueue,
                "HEALTHY", 100L, 5L, 1, 3, 0, 0);
    }

    private NodeSnapshot nodeWithGossip(String id, String status, Long secondsSinceUpdate) {
        return new NodeSnapshot(id, "host", 8082, status, true,
                0.9, 0.1, 100L, 10L, 1000L, 1024L,
                0.4, 5.0, 5.0, 10.0, false, 1L, 2L, 0L,
                "HEALTHY", 100L, secondsSinceUpdate, 1, 3, 0, 0);
    }
}
