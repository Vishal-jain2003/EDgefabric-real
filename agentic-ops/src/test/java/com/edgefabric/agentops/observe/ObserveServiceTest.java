package com.edgefabric.agentops.observe;

import com.edgefabric.agentops.alert.AlertStore;
import com.edgefabric.agentops.config.AgentOpsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ObserveServiceTest {

    @Mock private LoadBalancerClient loadBalancerClient;
    @Mock private CacheNodeClient cacheNodeClient;
    @Mock private PrometheusClient prometheusClient;
    @Mock private LokiClient lokiClient;
    @Mock private TempoClient tempoClient;
    @Mock private AlertStore alertStore;

    private ObserveService observeService;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        AgentOpsProperties.LlmProperties llmProps = new AgentOpsProperties.LlmProperties(null, 0);
        AgentOpsProperties.ActionsProperties actionsProps = new AgentOpsProperties.ActionsProperties(15, 60000);
        AgentOpsProperties props = new AgentOpsProperties(
                "http://lb", "http://prom", "http://loki", "http://tempo",
                llmProps, Map.of(), Map.of(), actionsProps);
        observeService = new ObserveService(
                loadBalancerClient, cacheNodeClient,
                prometheusClient, lokiClient, tempoClient, alertStore, props);
    }

    // ── getSnapshot ────────────────────────────────────────────────────────

    @Test
    void getSnapshot_returnsEmptySnapshot_whenLbIsUnreachable() {
        when(loadBalancerClient.getDashboardExport()).thenReturn(null);

        ClusterSnapshot snapshot = observeService.getSnapshot();

        assertThat(snapshot.totalNodes()).isZero();
        assertThat(snapshot.nodes()).isEmpty();
        assertThat(snapshot.snapshotTakenAt()).isNotNull();
    }

    @Test
    void getSnapshot_assemblesSnapshotFromAllSources() throws Exception {
        // ── given ─────────────────────────────────────────────────────────
        ObjectNode dashboardExport = buildDashboardExport("node-1", "10.0.0.1", 8082, "UP");
        ObjectNode ringInfo        = buildRingInfo(1, 150, 150, "xxhash");
        ObjectNode stats           = buildCacheStats(85.0, 100L);
        ObjectNode gossipTable     = buildGossipTable("ALIVE", 42L, 2L);

        when(loadBalancerClient.getDashboardExport()).thenReturn(dashboardExport);
        when(loadBalancerClient.getRingInfo()).thenReturn(ringInfo);
        when(cacheNodeClient.getStats("10.0.0.1", 8082)).thenReturn(stats);
        when(cacheNodeClient.getGossipTable("10.0.0.1", 8082)).thenReturn(gossipTable);
        when(prometheusClient.queryMemoryUsedRatio(nullable(String.class))).thenReturn(0.65);
        when(prometheusClient.queryEvictionsPerMin(nullable(String.class))).thenReturn(3.2);

        // ── when ──────────────────────────────────────────────────────────
        ClusterSnapshot snapshot = observeService.getSnapshot();

        // ── then ──────────────────────────────────────────────────────────
        assertThat(snapshot.totalNodes()).isEqualTo(1);
        assertThat(snapshot.healthyNodes()).isEqualTo(1);
        assertThat(snapshot.suspectNodes()).isZero();
        assertThat(snapshot.deadNodes()).isZero();
        assertThat(snapshot.snapshotTakenAt()).isNotNull();

        LoadBalancerSnapshot lb = snapshot.loadBalancer();
        assertThat(lb.status()).isEqualTo("UP");
        assertThat(lb.activeNodeCount()).isEqualTo(1);
        assertThat(lb.ringSize()).isEqualTo(150);
        assertThat(lb.virtualNodesPerNode()).isEqualTo(150);
        assertThat(lb.hashAlgorithm()).isEqualTo("xxhash");

        assertThat(snapshot.nodes()).hasSize(1);
        NodeSnapshot node = snapshot.nodes().get(0);
        assertThat(node.nodeId()).isEqualTo("node-1");
        assertThat(node.host()).isEqualTo("10.0.0.1");
        assertThat(node.port()).isEqualTo(8082);
        assertThat(node.status()).isEqualTo("HEALTHY");
        assertThat(node.hitRate()).isEqualTo(85.0);
        assertThat(node.missRate()).isEqualTo(15.0);
        assertThat(node.memorySizeBytes()).isEqualTo(100L);
        assertThat(node.memoryUsedRatio()).isEqualTo(0.65);
        assertThat(node.gossipStatus()).isEqualTo("ALIVE");
        assertThat(node.gossipHeartbeat()).isEqualTo(42L);
        assertThat(node.secondsSinceUpdate()).isEqualTo(2L);
        assertThat(node.evictionsPerMin()).isEqualTo(3.2);
    }

    @Test
    void getSnapshot_marksSuspect_whenGossipIsSuspect() throws Exception {
        ObjectNode dashboardExport = buildDashboardExport("node-1", "10.0.0.1", 8082, "UP");
        ObjectNode stats           = buildCacheStats(70.0, 50L);
        ObjectNode gossipTable     = buildGossipTable("SUSPECT", 10L, 30L);

        when(loadBalancerClient.getDashboardExport()).thenReturn(dashboardExport);
        when(loadBalancerClient.getRingInfo()).thenReturn(null);
        when(cacheNodeClient.getStats("10.0.0.1", 8082)).thenReturn(stats);
        when(cacheNodeClient.getGossipTable("10.0.0.1", 8082)).thenReturn(gossipTable);
        when(prometheusClient.queryMemoryUsedRatio(nullable(String.class))).thenReturn(null);
        when(prometheusClient.queryEvictionsPerMin(nullable(String.class))).thenReturn(null);

        ClusterSnapshot snapshot = observeService.getSnapshot();

        assertThat(snapshot.nodes().get(0).status()).isEqualTo("SUSPECT");
        assertThat(snapshot.suspectNodes()).isEqualTo(1);
    }

    @Test
    void getSnapshot_marksUnreachable_whenStatsEndpointFails() throws Exception {
        ObjectNode dashboardExport = buildDashboardExport("node-1", "10.0.0.1", 8082, "UP");

        when(loadBalancerClient.getDashboardExport()).thenReturn(dashboardExport);
        when(loadBalancerClient.getRingInfo()).thenReturn(null);
        when(cacheNodeClient.getStats("10.0.0.1", 8082)).thenReturn(null);
        when(cacheNodeClient.getGossipTable("10.0.0.1", 8082)).thenReturn(null);
        when(prometheusClient.queryMemoryUsedRatio(nullable(String.class))).thenReturn(null);
        when(prometheusClient.queryEvictionsPerMin(nullable(String.class))).thenReturn(null);

        ClusterSnapshot snapshot = observeService.getSnapshot();

        assertThat(snapshot.nodes().get(0).status()).isEqualTo("UNREACHABLE");
        assertThat(snapshot.deadNodes()).isEqualTo(1);
    }

    @Test
    void getSnapshot_toleratesPrometheusFailure() throws Exception {
        ObjectNode dashboardExport = buildDashboardExport("node-1", "10.0.0.1", 8082, "UP");
        ObjectNode stats           = buildCacheStats(60.0, 80L);
        ObjectNode gossipTable     = buildGossipTable("ALIVE", 5L, 1L);

        when(loadBalancerClient.getDashboardExport()).thenReturn(dashboardExport);
        when(loadBalancerClient.getRingInfo()).thenReturn(null);
        when(cacheNodeClient.getStats("10.0.0.1", 8082)).thenReturn(stats);
        when(cacheNodeClient.getGossipTable("10.0.0.1", 8082)).thenReturn(gossipTable);
        when(prometheusClient.queryMemoryUsedRatio(nullable(String.class))).thenReturn(null);
        when(prometheusClient.queryEvictionsPerMin(nullable(String.class))).thenReturn(null);

        ClusterSnapshot snapshot = observeService.getSnapshot();

        NodeSnapshot node = snapshot.nodes().get(0);
        assertThat(node.status()).isEqualTo("HEALTHY");
        assertThat(node.memoryUsedRatio()).isNull();
        assertThat(node.evictionsPerMin()).isNull();
    }

    // ── getNodeHealth ──────────────────────────────────────────────────────

    @Test
    void getNodeHealth_returnsNull_whenNodeNotFound() {
        when(loadBalancerClient.getDashboardExport()).thenReturn(null);

        NodeSnapshot result = observeService.getNodeHealth("nonexistent");

        assertThat(result).isNull();
    }

    // ── getMetricsSummary ──────────────────────────────────────────────────

    @Test
    void getMetricsSummary_returnsZeroErrors_whenLokiUnavailable() {
        when(prometheusClient.queryRange(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Map.of());
        when(lokiClient.countErrors(anyString(), anyString(), anyInt())).thenReturn(0);
        when(tempoClient.searchSlowTraces(anyString(), anyLong(), anyInt())).thenReturn(List.of());

        ObserveService.MetricsSummary summary = observeService.getMetricsSummary(5);

        assertThat(summary.errorLogCount()).isZero();
        assertThat(summary.lookbackMinutes()).isEqualTo(5);
        assertThat(summary.snapshotTakenAt()).isNotNull();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private ObjectNode buildDashboardExport(String nodeId, String host, int port, String lbStatus) throws Exception {
        ObjectNode node = mapper.createObjectNode();
        node.put("sourceNodeId", nodeId);
        node.put("sourceHost", host);
        node.put("sourcePort", port);
        node.put("reachable", true);

        ObjectNode export = mapper.createObjectNode();
        export.put("loadBalancerStatus", lbStatus);
        export.set("nodes", mapper.createArrayNode().add(node));
        return export;
    }

    private ObjectNode buildRingInfo(int nodeCount, int ringSize, int vNodes, String algo) {
        ObjectNode ring = mapper.createObjectNode();
        ring.put("nodeCount", nodeCount);
        ring.put("ringSize", ringSize);
        ring.put("virtualNodesPerNode", vNodes);
        ring.put("hashAlgorithm", algo);
        return ring;
    }

    private ObjectNode buildCacheStats(double hitRate, long memoryUsed) {
        ObjectNode stats = mapper.createObjectNode();
        stats.put("hitRate", hitRate);
        stats.put("memoryUsed", memoryUsed);
        stats.put("totalHits", 1000L);
        stats.put("totalMisses", 176L);
        stats.put("cacheSize", 500L);
        stats.put("healthStatus", "UP");
        return stats;
    }

    private ObjectNode buildGossipTable(String status, long heartbeat, long secondsSince) {
        ObjectNode member = mapper.createObjectNode();
        member.put("nodeId", "node-1");
        member.put("status", status);
        member.put("heartbeat", heartbeat);
        member.put("secondsSinceUpdate", secondsSince);
        member.put("self", true);

        ObjectNode table = mapper.createObjectNode();
        table.set("members", mapper.createArrayNode().add(member));
        table.put("totalNodes", 1);
        table.put("aliveCount", 1);
        table.put("suspectCount", 0);
        table.put("deadCount", 0);
        return table;
    }
}
