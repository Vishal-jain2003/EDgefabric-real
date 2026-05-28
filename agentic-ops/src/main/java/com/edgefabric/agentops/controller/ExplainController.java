package com.edgefabric.agentops.controller;

import com.edgefabric.agentops.act.ActionProposer;
import com.edgefabric.agentops.act.AgentAction;
import com.edgefabric.agentops.explain.Diagnosis;
import com.edgefabric.agentops.explain.ExplainService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST endpoints for the Explain phase — interprets Observe data and produces
 * structured diagnoses with severity, root cause, evidence, and recommendations.
 */
@Tag(name = "Explain", description = "AI diagnosis endpoints — interprets cluster observations into actionable diagnoses")
@RestController
@RequestMapping("/api/v1/explain")
public class ExplainController {

    private final ExplainService explainService;
    private final ActionProposer actionProposer;

    public ExplainController(ExplainService explainService, ActionProposer actionProposer) {
        this.explainService = explainService;
        this.actionProposer = actionProposer;
    }

    record ClusterHealthResponse(Diagnosis diagnosis, String pendingActionId) {}

    @Operation(summary = "Diagnose cluster health",
            description = "Analyzes the full cluster snapshot and identifies the highest-severity failure mode.")
    @GetMapping("/cluster-health")
    public ResponseEntity<ClusterHealthResponse> clusterHealth() {
        Diagnosis diagnosis = explainService.explainClusterHealth();
        AgentAction action = actionProposer.proposeFromDiagnosis(diagnosis);
        return ResponseEntity.ok(new ClusterHealthResponse(diagnosis, action != null ? action.getId() : null));
    }

    @Operation(summary = "Diagnose node anomaly",
            description = "Diagnoses a specific node's health — identifies isolation, memory pressure, or rebalancing.")
    @GetMapping("/node/{nodeId}")
    public ResponseEntity<Diagnosis> nodeAnomaly(
            @Parameter(description = "Node ID to diagnose") @PathVariable String nodeId) {
        return ResponseEntity.ok(explainService.explainNodeAnomaly(nodeId));
    }

    @Operation(summary = "Diagnose SLO status",
            description = "Explains whether SLO targets are being met, burn rate issues, and recommended actions.")
    @GetMapping("/slo")
    public ResponseEntity<Diagnosis> slo() {
        return ResponseEntity.ok(explainService.explainSlo());
    }

    @Operation(summary = "Diagnose latency spikes",
            description = "Identifies nodes with latency anomalies. Optionally filter to a single node.")
    @GetMapping("/latency")
    public ResponseEntity<Diagnosis> latency(
            @Parameter(description = "Optional node ID to filter") @RequestParam(required = false) String node_id) {
        return ResponseEntity.ok(explainService.explainLatency(node_id));
    }

    @Operation(summary = "Diagnose self-healing activity",
            description = "Explains ongoing repair activity, migration queue depth, and drain state.")
    @GetMapping("/self-healing")
    public ResponseEntity<Diagnosis> selfHealing() {
        return ResponseEntity.ok(explainService.explainSelfHealing());
    }
}
