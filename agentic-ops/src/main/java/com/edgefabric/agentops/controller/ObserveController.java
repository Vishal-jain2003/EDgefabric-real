package com.edgefabric.agentops.controller;

import com.edgefabric.agentops.observe.ClusterSnapshot;
import com.edgefabric.agentops.observe.LokiClient;
import com.edgefabric.agentops.observe.NodeSnapshot;
import com.edgefabric.agentops.observe.ObserveService;
import com.edgefabric.agentops.observe.ObserveService.HashRingInfo;
import com.edgefabric.agentops.observe.ObserveService.MetricsSummary;
import com.edgefabric.agentops.observe.ObserveService.NodeLatencyProfile;
import com.edgefabric.agentops.observe.ObserveService.SelfHealingStatus;
import com.edgefabric.agentops.observe.ObserveService.SloStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST endpoints for observing the EdgeFabric cluster state.
 *
 * <p>These endpoints expose the same data as the MCP {@code observe.*} tools
 * so both human operators (via Swagger UI) and LLM clients (via MCP) can
 * consume them.</p>
 */
@Tag(name = "Observe", description = "Cluster observation endpoints — full snapshot, per-node detail, and metrics summary")
@RestController
@RequestMapping("/api/v1/observe")
public class ObserveController {

    private final ObserveService observeService;

    public ObserveController(ObserveService observeService) {
        this.observeService = observeService;
    }

    /**
     * Returns the full cluster snapshot (MCP tool: {@code observe.cluster_topology}).
     */
    @Operation(
            summary = "Full cluster snapshot",
            description = "Assembles a real-time ClusterSnapshot by reading all five data sources " +
                    "(LB, cache nodes, Prometheus, Loki, Tempo) in parallel. " +
                    "Returns within 2 seconds for clusters of ≤ 10 nodes. " +
                    "Fields are null when a data source is unavailable.")
    @GetMapping("/snapshot")
    public ResponseEntity<ClusterSnapshot> snapshot() {
        return ResponseEntity.ok(observeService.getSnapshot());
    }

    /**
     * Returns health detail for a specific node (MCP tool: {@code observe.node_health}).
     */
    @Operation(
            summary = "Single-node health",
            description = "Returns the full NodeSnapshot for the given nodeId including gossip state, " +
                    "cache stats, and Prometheus enrichment.")
    @GetMapping("/node/{nodeId}")
    public ResponseEntity<NodeSnapshot> nodeHealth(
            @Parameter(description = "Node ID as reported by the LB dashboard export (e.g. '10.0.0.1:8082')")
            @PathVariable String nodeId) {
        NodeSnapshot node = observeService.getNodeHealth(nodeId);
        if (node == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(node);
    }

    /**
     * Returns a metrics summary for the last N minutes (MCP tool: {@code observe.metrics_summary}).
     */
    @Operation(
            summary = "Metrics summary",
            description = "Returns P99 latency, error log count, and slow trace IDs for the last N minutes.")
    @GetMapping("/metrics")
    public ResponseEntity<MetricsSummary> metricsSummary(
            @Parameter(description = "Lookback window in minutes (default: 5)")
            @RequestParam(defaultValue = "5") int minutes) {
        return ResponseEntity.ok(observeService.getMetricsSummary(minutes));
    }

    @Operation(summary = "SLO status", description = "Current SLO: availability ratio, burn rate (1h), error rate (5m), and health verdict.")
    @GetMapping("/slo")
    public ResponseEntity<SloStatus> sloStatus() {
        return ResponseEntity.ok(observeService.getSloStatus());
    }

    @Operation(summary = "Hash ring topology", description = "Consistent hash ring: node count, ring size, virtual nodes, hash algorithm, active node IDs.")
    @GetMapping("/ring")
    public ResponseEntity<HashRingInfo> ringInfo() {
        return ResponseEntity.ok(observeService.getHashRing());
    }

    @Operation(summary = "Latency profile per node", description = "P50 and P99 latency in ms for each cache node.")
    @GetMapping("/latency")
    public ResponseEntity<List<NodeLatencyProfile>> latencyProfile() {
        return ResponseEntity.ok(observeService.getLatencyProfile());
    }

    @Operation(summary = "Self-healing status", description = "Anti-entropy repairs, self-healing attempts, migration queue, and drain state per node.")
    @GetMapping("/self-healing")
    public ResponseEntity<SelfHealingStatus> selfHealingStatus() {
        return ResponseEntity.ok(observeService.getSelfHealingStatus());
    }

    @Operation(summary = "Recent cluster logs", description = "Recent ERROR/WARN log lines from Loki for a service.")
    @GetMapping("/logs")
    public ResponseEntity<List<LokiClient.LogLine>> clusterLogs(
            @Parameter(description = "Service name, e.g. edgefabric-caching")
            @RequestParam(defaultValue = "edgefabric-caching") String service,
            @Parameter(description = "Lookback window in minutes (default: 10)")
            @RequestParam(defaultValue = "10") int minutes) {
        return ResponseEntity.ok(observeService.getClusterLogs(service, minutes));
    }
}

