package com.edgefabric.agentops.explain;

import com.edgefabric.agentops.observe.ClusterSnapshot;
import com.edgefabric.agentops.observe.NodeSnapshot;
import com.edgefabric.agentops.observe.ObserveService;
import com.edgefabric.agentops.observe.ObserveService.NodeLatencyProfile;
import com.edgefabric.agentops.observe.ObserveService.SelfHealingStatus;
import com.edgefabric.agentops.observe.ObserveService.SloStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Explain service — interprets Observe data and produces structured diagnoses.
 * Failure-mode matching is data-driven via {@link FailureMode#ALL_MODES}.
 */
@Service
public class ExplainService {

    private final ObserveService observeService;

    public ExplainService(ObserveService observeService) {
        this.observeService = observeService;
    }

    public Diagnosis explainClusterHealth() {
        ClusterSnapshot snapshot = observeService.getSnapshot();
        if (snapshot == null) {
            return unavailable("explain_cluster_health");
        }

        List<String> evidence = new ArrayList<>();
        String detectedMode = null;

        // Check node_isolation
        if (snapshot.deadNodes() > 0) {
            evidence.add("deadNodes=" + snapshot.deadNodes() + " (threshold: >0)");
            detectedMode = "node_isolation";
        }
        for (NodeSnapshot node : snapshot.nodes()) {
            if ("DEAD".equals(node.status()) || "UNREACHABLE".equals(node.status())) {
                evidence.add("[" + node.nodeId() + "] status=" + node.status());
                if (detectedMode == null) detectedMode = "node_isolation";
            }
            if (node.lbReachable() != null && !node.lbReachable()) {
                evidence.add("[" + node.nodeId() + "] lbReachable=false");
                if (detectedMode == null) detectedMode = "node_isolation";
            }
        }

        // Check quorum_failure
        if (snapshot.healthyNodes() < 2) {
            evidence.add("healthyNodes=" + snapshot.healthyNodes() + " (threshold: <2)");
            detectedMode = "quorum_failure"; // overrides — more severe
        }

        // Check cache_memory_pressure
        if (detectedMode == null) {
            for (NodeSnapshot node : snapshot.nodes()) {
                if (node.memoryUsedRatio() != null && node.memoryUsedRatio() > 0.85) {
                    evidence.add("[" + node.nodeId() + "] memoryUsedRatio=" + String.format("%.2f", node.memoryUsedRatio()));
                    detectedMode = "cache_memory_pressure";
                }
                if (node.evictionsPerMin() != null && node.evictionsPerMin() > 100) {
                    evidence.add("[" + node.nodeId() + "] evictionsPerMin=" + String.format("%.0f", node.evictionsPerMin()));
                    if (detectedMode == null) detectedMode = "cache_memory_pressure";
                }
            }
        }

        // Check ring_rebalancing_lag
        if (detectedMode == null) {
            for (NodeSnapshot node : snapshot.nodes()) {
                if (node.migrationQueueSize() != null && node.migrationQueueSize() > 10) {
                    evidence.add("[" + node.nodeId() + "] migrationQueueSize=" + node.migrationQueueSize());
                    detectedMode = "ring_rebalancing_lag";
                }
                if (node.drainActive() != null && node.drainActive()) {
                    evidence.add("[" + node.nodeId() + "] drainActive=true");
                    if (detectedMode == null) detectedMode = "ring_rebalancing_lag";
                }
            }
        }

        if (detectedMode != null) {
            FailureMode mode = FailureMode.ALL_MODES.get(detectedMode);
            String diagStr = mode.description() + " — " + String.join("; ", evidence.subList(0, Math.min(3, evidence.size())))
                    + " → " + mode.recommendations().get(0);
            return new Diagnosis("explain_cluster_health", mode.severity(), detectedMode,
                    mode.description(), evidence, mode.recommendations(), diagStr,
                    snapshot.snapshotTakenAt() != null ? snapshot.snapshotTakenAt() : Instant.now(), null);
        }

        // Healthy
        evidence.add("healthyNodes=" + snapshot.healthyNodes() + "/" + snapshot.totalNodes());
        evidence.add("avgHitRate=" + snapshot.avgHitRate());
        evidence.add("deadNodes=" + snapshot.deadNodes());
        return new Diagnosis("explain_cluster_health", "OK", null,
                "No anomalies detected — cluster is healthy", evidence, List.of(),
                "Cluster healthy — " + snapshot.healthyNodes() + "/" + snapshot.totalNodes() + " nodes operational",
                snapshot.snapshotTakenAt() != null ? snapshot.snapshotTakenAt() : Instant.now(), null);
    }

    public Diagnosis explainNodeAnomaly(String nodeId) {
        NodeSnapshot node = observeService.getNodeHealth(nodeId);
        if (node == null) {
            return new Diagnosis("explain_node_anomaly", "ERROR", null,
                    "Node " + nodeId + " not found in cluster", List.of("nodeId=" + nodeId),
                    List.of("Verify node ID exists in the cluster"), "Node " + nodeId + " not found");
        }

        List<String> evidence = new ArrayList<>();
        String detectedMode = null;

        if ("DEAD".equals(node.status()) || "UNREACHABLE".equals(node.status())) {
            evidence.add("status=" + node.status());
            detectedMode = "node_isolation";
        }
        if (node.lbReachable() != null && !node.lbReachable()) {
            evidence.add("lbReachable=false");
            if (detectedMode == null) detectedMode = "node_isolation";
        }
        if (node.secondsSinceUpdate() != null && node.secondsSinceUpdate() > 60) {
            evidence.add("secondsSinceUpdate=" + node.secondsSinceUpdate() + "s (stale)");
            if (detectedMode == null) detectedMode = "node_isolation";
        }
        if (node.memoryUsedRatio() != null && node.memoryUsedRatio() > 0.85) {
            evidence.add("memoryUsedRatio=" + String.format("%.2f", node.memoryUsedRatio()));
            if (detectedMode == null) detectedMode = "cache_memory_pressure";
        }
        if (node.evictionsPerMin() != null && node.evictionsPerMin() > 100) {
            evidence.add("evictionsPerMin=" + String.format("%.0f", node.evictionsPerMin()));
            if (detectedMode == null) detectedMode = "cache_memory_pressure";
        }
        if (node.migrationQueueSize() != null && node.migrationQueueSize() > 10) {
            evidence.add("migrationQueueSize=" + node.migrationQueueSize());
            if (detectedMode == null) detectedMode = "ring_rebalancing_lag";
        }
        if (node.drainActive() != null && node.drainActive()) {
            evidence.add("drainActive=true");
            if (detectedMode == null) detectedMode = "ring_rebalancing_lag";
        }

        if (detectedMode != null) {
            FailureMode mode = FailureMode.ALL_MODES.get(detectedMode);
            String diagStr = "Node " + nodeId + ": " + mode.description() + " — " +
                    String.join("; ", evidence.subList(0, Math.min(3, evidence.size())));
            return new Diagnosis("explain_node_anomaly", mode.severity(), detectedMode,
                    "Node " + nodeId + ": " + mode.description(), evidence, mode.recommendations(),
                    diagStr, Instant.now(), nodeId);
        }

        evidence.add("status=" + node.status());
        evidence.add("hitRate=" + node.hitRate());
        evidence.add("p99=" + node.p99LatencyMs() + "ms");
        return new Diagnosis("explain_node_anomaly", "OK", null,
                "Node " + nodeId + " is healthy", evidence, List.of(),
                "Node " + nodeId + " healthy — status=" + node.status() + ", hitRate=" + node.hitRate(),
                Instant.now(), nodeId);
    }

    public Diagnosis explainSlo() {
        SloStatus slo = observeService.getSloStatus();
        if (slo == null) {
            return unavailable("explain_slo_breach");
        }

        List<String> evidence = new ArrayList<>();
        String severity = "OK";
        String failureMode = null;
        String rootCause = "SLO targets are being met";
        List<String> recommendations = new ArrayList<>();

        if (slo.availabilityRatio() != null) {
            evidence.add("availability=" + String.format("%.4f", slo.availabilityRatio()));
            if (slo.availabilityRatio() < 0.999) {
                severity = "WARNING";
                failureMode = "node_isolation";
                rootCause = "SLO availability below target: " + String.format("%.4f", slo.availabilityRatio()) + " (target: 0.999)";
                recommendations.add("Investigate failing nodes to restore availability");
            }
        } else {
            evidence.add("availability=data_unavailable");
        }

        if (slo.burnRate1h() != null) {
            evidence.add("burnRate1h=" + String.format("%.2f", slo.burnRate1h()));
            if (slo.burnRate1h() > 1.0) {
                severity = "CRITICAL";
                failureMode = "quorum_failure";
                rootCause = "SLO burn rate critical: " + String.format("%.2f", slo.burnRate1h()) + "x — error budget exhausting rapidly";
                recommendations = List.of(
                        "URGENT: Error budget burning at >1x rate",
                        "Identify and fix the root cause of elevated error rate",
                        "Consider enabling circuit breaker to shed load"
                );
            }
        } else {
            evidence.add("burnRate1h=data_unavailable");
        }

        if (slo.errorRate5m() != null) {
            evidence.add("errorRate5m=" + String.format("%.4f", slo.errorRate5m()));
            if (slo.errorRate5m() > 0.01 && !"CRITICAL".equals(severity)) {
                severity = "WARNING";
                recommendations.add("Error rate elevated — investigate recent changes");
            }
        } else {
            evidence.add("errorRate5m=data_unavailable");
        }

        evidence.add("healthVerdict=" + slo.overallHealth());

        String diagStr = "SLO Status: " + severity + " — " + String.join("; ", evidence.subList(0, Math.min(3, evidence.size())));
        return new Diagnosis("explain_slo_breach", severity, failureMode, rootCause,
                evidence, recommendations, diagStr);
    }

    public Diagnosis explainLatency(String nodeId) {
        List<NodeLatencyProfile> profiles = observeService.getLatencyProfile();
        if (profiles == null || profiles.isEmpty()) {
            return unavailable("explain_latency_spike");
        }

        if (nodeId != null && !nodeId.isBlank()) {
            profiles = profiles.stream().filter(p -> nodeId.equals(p.nodeId())).toList();
        }

        List<String> evidence = new ArrayList<>();
        List<String> criticalNodes = new ArrayList<>();
        List<String> slowNodes = new ArrayList<>();

        for (NodeLatencyProfile p : profiles) {
            String tier = p.performanceTier();
            evidence.add("[" + p.nodeId() + "] p99=" + p.p99LatencyMs() + "ms tier=" + tier);
            if ("CRITICAL".equals(tier) || (p.p99LatencyMs() != null && p.p99LatencyMs() > 500)) {
                criticalNodes.add(p.nodeId());
            } else if ("SLOW".equals(tier) || (p.p99LatencyMs() != null && p.p99LatencyMs() > 200)) {
                slowNodes.add(p.nodeId());
            }
        }

        if (!criticalNodes.isEmpty()) {
            return new Diagnosis("explain_latency_spike", "CRITICAL", "cache_memory_pressure",
                    "Critical latency on nodes: " + String.join(", ", criticalNodes) + " — p99 > 500ms",
                    evidence, List.of(
                    "Check memory pressure on affected nodes (high evictions cause latency)",
                    "Verify network connectivity — high latency may indicate packet loss",
                    "Check if affected nodes are under rebalancing (drain active)",
                    "Consider removing hot keys or redistributing load"),
                    "Latency: CRITICAL — critical nodes: " + String.join(", ", criticalNodes));
        }
        if (!slowNodes.isEmpty()) {
            return new Diagnosis("explain_latency_spike", "WARNING", "cache_memory_pressure",
                    "Elevated latency on nodes: " + String.join(", ", slowNodes) + " — p99 > 200ms",
                    evidence, List.of(
                    "Monitor if latency is trending upward",
                    "Check memory used ratio on slow nodes",
                    "Verify no ongoing ring rebalancing affecting these nodes"),
                    "Latency: WARNING — slow nodes: " + String.join(", ", slowNodes));
        }

        return new Diagnosis("explain_latency_spike", "OK", null,
                "All nodes within acceptable latency bounds", evidence, List.of(),
                "Latency: OK — all nodes in FAST/OK tier");
    }

    public Diagnosis explainSelfHealing() {
        SelfHealingStatus status = observeService.getSelfHealingStatus();
        if (status == null) {
            return unavailable("explain_self_healing");
        }

        long totalRepairs = status.totalRepairsAcrossCluster();
        long pendingMigrations = status.nodes().stream()
                .mapToLong(s -> s.migrationQueueSize() != null ? s.migrationQueueSize() : 0)
                .sum();
        boolean anyDrainActive = status.nodes().stream()
                .anyMatch(s -> s.drainActive() != null && s.drainActive());

        List<String> evidence = new ArrayList<>();
        evidence.add("antiEntropyRepairs=" + totalRepairs);
        evidence.add("migrationQueueSize=" + pendingMigrations);
        evidence.add("drainActive=" + anyDrainActive);

        if (anyDrainActive && pendingMigrations > 10) {
            FailureMode mode = FailureMode.ALL_MODES.get("ring_rebalancing_lag");
            return new Diagnosis("explain_self_healing", "WARNING", "ring_rebalancing_lag",
                    "Active drain with large migration queue (" + pendingMigrations + " pending)",
                    evidence, mode.recommendations(),
                    "Self-healing: WARNING — drain active, queue=" + pendingMigrations);
        }
        if (totalRepairs > 50) {
            return new Diagnosis("explain_self_healing", "WARNING", "ring_rebalancing_lag",
                    "High anti-entropy repair count (" + totalRepairs + ") — significant data inconsistency being repaired",
                    evidence, List.of(
                    "Repairs indicate recent node failures or network partitions",
                    "Monitor until repair count stabilizes",
                    "Check if any nodes were recently added/removed from ring"),
                    "Self-healing: WARNING — repairs=" + totalRepairs);
        }

        String severity = anyDrainActive ? "INFO" : "OK";
        return new Diagnosis("explain_self_healing", severity, null,
                anyDrainActive ? "Drain active but migration queue is manageable" : "Self-healing activity is normal",
                evidence, List.of(),
                "Self-healing: " + severity + " — repairs=" + totalRepairs + ", queue=" + pendingMigrations);
    }

    private Diagnosis unavailable(String tool) {
        return new Diagnosis(tool, "CRITICAL", null,
                "Cannot diagnose: Observe data unavailable",
                List.of("Observe API returned null"),
                List.of("Ensure agentic-ops module is running on port 8090"),
                "Diagnosis unavailable — Observe data is null");
    }
}
