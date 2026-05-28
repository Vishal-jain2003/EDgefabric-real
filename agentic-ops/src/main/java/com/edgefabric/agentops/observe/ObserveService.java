package com.edgefabric.agentops.observe;

import com.edgefabric.agentops.alert.AlertStore;
import com.edgefabric.agentops.config.AgentOpsProperties;
import com.edgefabric.agentops.util.StructuredLogContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates parallel reads from all data sources and assembles a {@link ClusterSnapshot}.
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>Node discovery is driven by the LB dashboard export — single source of truth.</li>
 *   <li>Per-node cache stats, gossip and Prometheus enrichment are fetched in parallel.</li>
 *   <li>Cluster-wide SLO metrics (burn rate, availability, error rate) run in parallel too.</li>
 *   <li>Every source is independently fault-tolerant: an outage yields null fields, not failures.</li>
 *   <li>Overall snapshot completes within 2 seconds for clusters of ≤ 10 nodes.</li>
 * </ul>
 */
@Service
public class ObserveService {

    private static final Logger log = LoggerFactory.getLogger(ObserveService.class);
    private static final long SNAPSHOT_TIMEOUT_MS = 2_000;
    private static final long SLO_TIMEOUT_MS      = 1_000;

    private final LoadBalancerClient loadBalancerClient;
    private final CacheNodeClient    cacheNodeClient;
    private final PrometheusClient   prometheusClient;
    private final LokiClient         lokiClient;
    private final TempoClient        tempoClient;
    private final AlertStore         alertStore;
    private final Map<String, String> dockerNodeHostMap;
    private final Map<String, String> prometheusInstanceMap;

    public ObserveService(LoadBalancerClient loadBalancerClient,
                          CacheNodeClient cacheNodeClient,
                          PrometheusClient prometheusClient,
                          LokiClient lokiClient,
                          TempoClient tempoClient,
                          AlertStore alertStore,
                          AgentOpsProperties props) {
        this.loadBalancerClient   = loadBalancerClient;
        this.cacheNodeClient      = cacheNodeClient;
        this.prometheusClient     = prometheusClient;
        this.lokiClient           = lokiClient;
        this.tempoClient          = tempoClient;
        this.alertStore           = alertStore;
        this.dockerNodeHostMap    = props.dockerNodeHostMap();
        this.prometheusInstanceMap = props.prometheusInstanceMap();
    }

    // ── Public API / MCP Tools ─────────────────────────────────────────────

    @Tool(name = "observe_cluster_topology",
            description = "Returns a full real-time ClusterSnapshot: all nodes with HEALTHY/SUSPECT/DEAD status, " +
                    "hit rate, miss rate, total hits/misses, cache size, latency (P50/P99), gossip state, " +
                    "evictions, self-healing stats, drain state, and cluster-wide SLO metrics " +
                    "(availability ratio, burn rate, error rate). Use this first for overall cluster health.")
    public ClusterSnapshot getSnapshot() {
        long start = System.currentTimeMillis();
        try (var ctx = StructuredLogContext.create().operation("OBSERVE_SNAPSHOT")) {
            ClusterSnapshot snapshot = buildSnapshot();
            long durationMs = System.currentTimeMillis() - start;
            ctx.duration(durationMs).result("SUCCESS");
            log.info("Cluster snapshot assembled [nodes={}, duration={}ms]",
                    snapshot.totalNodes(), durationMs);
            return snapshot;
        }
    }

    @Tool(name = "observe_node_health",
            description = "Returns detailed health for a specific cache node by nodeId. " +
                    "Includes cache stats (hits, misses, size), gossip heartbeat, incarnation (restart counter), " +
                    "P50/P99 latency, memory, drain state, self-healing attempts, and anti-entropy repairs. " +
                    "Use for deep-dive into a single node.")
    public NodeSnapshot getNodeHealth(String nodeId) {
        try (var ctx = StructuredLogContext.create().operation("OBSERVE_NODE_HEALTH").nodeId(nodeId)) {
            return getSnapshot().nodes().stream()
                    .filter(n -> nodeId.equals(n.nodeId()))
                    .findFirst()
                    .orElse(null);
        }
    }

    @Tool(name = "observe_slo_status",
            description = "Returns current SLO metrics from Prometheus recording rules: " +
                    "availability ratio (1.0 = 100%), burn rate 1h (> 1 = budget burning too fast), " +
                    "error rate 5m, and overall health verdict (OK/WARNING/CRITICAL). " +
                    "A burn rate of 1000 means the SLO budget is exhausted — check immediately.")
    public SloStatus getSloStatus() {
        try (var ctx = StructuredLogContext.create().operation("OBSERVE_SLO")) {
            Double availability = prometheusClient.querySloAvailability();
            Double burnRate     = prometheusClient.queryBurnRate1h();
            Double errorRate    = prometheusClient.queryErrorRate5m();

            String health = "UNKNOWN";
            if (burnRate != null) {
                if (burnRate > 10)     health = "CRITICAL";
                else if (burnRate > 1) health = "WARNING";
                else                   health = "OK";
            } else if (availability != null) {
                health = availability >= 0.999 ? "OK" : availability >= 0.99 ? "WARNING" : "CRITICAL";
            }

            ctx.result(health);
            return new SloStatus(availability, burnRate, errorRate, health, Instant.now());
        }
    }

    @Tool(name = "observe_latency_profile",
            description = "Returns P50 and P99 HTTP latency in milliseconds for every cache node and the " +
                    "load balancer, derived from Prometheus histograms over the last 5 minutes. " +
                    "Performance tiers: FAST (<5ms P99), OK (<20ms), SLOW (<100ms), CRITICAL (>=100ms). " +
                    "Use to identify which node is the latency hotspot.")
    public List<NodeLatencyProfile> getLatencyProfile() {
        try (var ctx = StructuredLogContext.create().operation("OBSERVE_LATENCY")) {
            ClusterSnapshot snapshot = getSnapshot();
            List<NodeLatencyProfile> profiles = snapshot.nodes().stream()
                    .map(n -> new NodeLatencyProfile(
                            n.nodeId(), n.p50LatencyMs(), n.p99LatencyMs(),
                            latencyTier(n.p99LatencyMs())))
                    .toList();
            ctx.result("SUCCESS");
            return profiles;
        }
    }

    @Tool(name = "observe_hash_ring",
            description = "Returns consistent hash ring topology: node count, ring size, virtual nodes per node, " +
                    "hash algorithm, and the list of active node IDs in the ring. " +
                    "Use to understand key distribution and detect ring imbalance.")
    public HashRingInfo getHashRing() {
        try (var ctx = StructuredLogContext.create().operation("OBSERVE_HASH_RING")) {
            JsonNode ringInfo = loadBalancerClient.getRingInfo();
            if (ringInfo == null) {
                return new HashRingInfo("UNAVAILABLE", 0, 0, 0, "unknown", List.of(), Instant.now());
            }
            List<String> activeNodeIds = new ArrayList<>();
            JsonNode nodesArray = ringInfo.path("activeNodes");
            if (nodesArray.isArray()) {
                nodesArray.forEach(n -> activeNodeIds.add(n.asText()));
            }
            ctx.result("SUCCESS");
            return new HashRingInfo(
                    "UP",
                    ringInfo.path("nodeCount").asInt(0),
                    ringInfo.path("ringSize").asInt(0),
                    ringInfo.path("virtualNodesPerNode").asInt(0),
                    ringInfo.path("hashAlgorithm").asText("unknown"),
                    activeNodeIds,
                    Instant.now());
        }
    }

    @Tool(name = "observe_self_healing_status",
            description = "Returns self-healing and anti-entropy metrics per node: total self-healing attempts, " +
                    "anti-entropy repairs issued, migration queue size, and drain state. " +
                    "Non-zero repair counts indicate data inconsistency was detected and corrected. " +
                    "Migration queue > 0 means a ring rebalance is in progress.")
    public SelfHealingStatus getSelfHealingStatus() {
        try (var ctx = StructuredLogContext.create().operation("OBSERVE_SELF_HEALING")) {
            ClusterSnapshot snapshot = getSnapshot();
            List<NodeHealingStats> nodeStats = snapshot.nodes().stream()
                    .map(n -> new NodeHealingStats(
                            n.nodeId(), n.selfHealingAttempts(), n.antiEntropyRepairs(),
                            n.migrationQueueSize(), n.drainActive()))
                    .toList();
            long totalRepairs = nodeStats.stream()
                    .mapToLong(s -> s.antiEntropyRepairs() != null ? s.antiEntropyRepairs() : 0)
                    .sum();
            ctx.result("SUCCESS");
            return new SelfHealingStatus(nodeStats, totalRepairs, Instant.now());
        }
    }

    @Tool(name = "observe_cluster_logs",
            description = "Returns recent log lines from Loki for a given service and log level. " +
                    "service examples: 'edgefabric-caching', 'edgefabric-loadbalancer'. " +
                    "level examples: 'ERROR', 'WARN', 'INFO'. lookbackMinutes: how far back to search (default 10). " +
                    "Use to investigate errors or warnings correlated with unhealthy nodes.")
    public List<LokiClient.LogLine> getClusterLogs(String service, int lookbackMinutes) {
        try (var ctx = StructuredLogContext.create().operation("OBSERVE_LOGS")) {
            String svc = (service == null || service.isBlank()) ? "edgefabric-caching" : service;
            List<LokiClient.LogLine> lines = lokiClient.fetchRecentLogs(svc, "ERROR", lookbackMinutes, 100);
            if (lines.isEmpty()) {
                lines = lokiClient.fetchRecentLogs(svc, "WARN", lookbackMinutes, 100);
            }
            ctx.put("lines", String.valueOf(lines.size())).result("SUCCESS");
            return lines;
        }
    }

    @Tool(name = "observe_metrics_summary",
            description = "Returns a lightweight metrics summary: P99 latency (Prometheus range), " +
                    "error log count from Loki, and slow trace IDs from Tempo.")
    public MetricsSummary getMetricsSummary(int lookbackMinutes) {
        try (var ctx = StructuredLogContext.create().operation("OBSERVE_METRICS_SUMMARY")) {
            String now   = String.valueOf(Instant.now().getEpochSecond());
            String start = String.valueOf(Instant.now().getEpochSecond() - (long) lookbackMinutes * 60);
            String p99Promql = "histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[1m]))";
            var p99Map = prometheusClient.queryRange(p99Promql, start, now, "30s");
            Double p99 = p99Map.values().stream().findFirst()
                    .map(v -> { try { return Double.parseDouble(v); } catch (Exception e) { return null; } })
                    .orElse(null);
            int errorCount = lokiClient.countErrors("edgefabric-caching", "ERROR", lookbackMinutes);
            List<String> slowTraces = tempoClient.searchSlowTraces("edgefabric-loadbalancer", 500, 5);
            ctx.result("SUCCESS");
            return new MetricsSummary(lookbackMinutes, p99, errorCount, slowTraces, Instant.now());
        }
    }

    // ── Core snapshot assembly ─────────────────────────────────────────────

    private ClusterSnapshot buildSnapshot() {
        JsonNode dashboardExport = loadBalancerClient.getDashboardExport();
        if (dashboardExport == null) {
            log.warn("LB dashboard export unavailable — returning empty snapshot");
            return ClusterSnapshot.empty();
        }

        List<NodeDiscoveryEntry> discovered = parseDiscoveredNodes(dashboardExport.path("nodes"));

        // Per-node + ring + SLO fetches in parallel
        List<CompletableFuture<NodeSnapshot>> nodeFutures = discovered.stream()
                .map(entry -> CompletableFuture.supplyAsync(() -> buildNodeSnapshot(entry), runAsync()))
                .toList();

        CompletableFuture<JsonNode>  ringFuture  = CompletableFuture.supplyAsync(loadBalancerClient::getRingInfo, runAsync());
        CompletableFuture<Double>    sloAv       = CompletableFuture.supplyAsync(prometheusClient::querySloAvailability, runAsync());
        CompletableFuture<Double>    burnRate    = CompletableFuture.supplyAsync(prometheusClient::queryBurnRate1h, runAsync());
        CompletableFuture<Double>    errorRate   = CompletableFuture.supplyAsync(prometheusClient::queryErrorRate5m, runAsync());

        try {
            CompletableFuture.allOf(nodeFutures.toArray(new CompletableFuture[0]))
                    .get(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Some per-node fetches timed out — proceeding with partial data");
        }

        List<NodeSnapshot> nodes = new ArrayList<>();
        for (CompletableFuture<NodeSnapshot> f : nodeFutures) {
            if (f.isDone() && !f.isCompletedExceptionally()) {
                try { nodes.add(f.get()); } catch (Exception ignored) {}
            }
        }

        JsonNode ringInfo = null;
        try { ringInfo = ringFuture.get(500, TimeUnit.MILLISECONDS); } catch (Exception e) { log.warn("Ring info timed out"); }

        Double sloAvailability = getSafe(sloAv);
        Double sloBurnRate     = getSafe(burnRate);
        Double sloErrorRate    = getSafe(errorRate);

        // Cluster-wide aggregates
        long totalHits   = nodes.stream().mapToLong(n -> n.totalHits()   != null ? n.totalHits()   : 0).sum();
        long totalMisses = nodes.stream().mapToLong(n -> n.totalMisses() != null ? n.totalMisses() : 0).sum();
        double avgHitRate = nodes.stream()
                .filter(n -> n.hitRate() != null)
                .mapToDouble(NodeSnapshot::hitRate)
                .average().orElse(0.0);

        int healthy = (int) nodes.stream().filter(n -> "HEALTHY".equals(n.status())).count();
        int suspect = (int) nodes.stream().filter(n -> "SUSPECT".equals(n.status())).count();
        int dead    = (int) nodes.stream().filter(n -> "DEAD".equals(n.status()) || "UNREACHABLE".equals(n.status())).count();

        LoadBalancerSnapshot lbSnapshot = buildLbSnapshot(dashboardExport, ringInfo);

        return new ClusterSnapshot(nodes.size(), healthy, suspect, dead, Instant.now(),
                lbSnapshot, nodes, totalHits, totalMisses, avgHitRate,
                sloAvailability, sloBurnRate, sloErrorRate);
    }

    private NodeSnapshot buildNodeSnapshot(NodeDiscoveryEntry entry) {
        String nodeId   = entry.nodeId();
        String host     = entry.host();
        int port        = entry.port();
        String promInst = entry.prometheusInstance();

        try (var ctx = StructuredLogContext.create().operation("OBSERVE_NODE").nodeId(nodeId)) {
            JsonNode stats       = cacheNodeClient.getStats(host, port);
            JsonNode gossipTable = cacheNodeClient.getGossipTable(host, port);

            // Cache stats
            Double hitRate    = CacheNodeClient.extractDouble(stats, "hitRate");
            Double missRate   = (hitRate != null) ? 100.0 - hitRate : null;
            Long memoryUsed   = CacheNodeClient.extractLong(stats, "memoryUsed");
            Long totalHits    = CacheNodeClient.extractLong(stats, "totalHits");
            Long totalMisses  = CacheNodeClient.extractLong(stats, "totalMisses");
            Long cacheSize    = CacheNodeClient.extractLong(stats, "cacheSize");

            // Gossip
            String gossipStatus     = CacheNodeClient.extractSelfGossipStatus(gossipTable);
            Long gossipHeartbeat    = CacheNodeClient.extractSelfHeartbeat(gossipTable);
            Long secondsSinceUpdate = CacheNodeClient.extractSelfSecondsSinceUpdate(gossipTable);
            Integer incarnation     = CacheNodeClient.extractSelfIncarnation(gossipTable);
            Integer aliveCount      = CacheNodeClient.extractGossipCount(gossipTable, "aliveCount");
            Integer suspectCount    = CacheNodeClient.extractGossipCount(gossipTable, "suspectCount");
            Integer deadCount       = CacheNodeClient.extractGossipCount(gossipTable, "deadCount");

            // Prometheus per-node enrichment (uses Prometheus instance label)
            Double memoryUsedRatio  = prometheusClient.queryMemoryUsedRatio(promInst);
            Double evictionsPerMin  = prometheusClient.queryEvictionsPerMin(promInst);
            Double p50LatencyMs     = prometheusClient.queryP50LatencyMs(promInst);
            Double p99LatencyMs     = prometheusClient.queryP99LatencyMs(promInst);
            Boolean drainActive     = prometheusClient.queryDrainActive(promInst);
            Long selfHealing        = prometheusClient.querySelfHealingAttempts(promInst);
            Long antiEntropy        = prometheusClient.queryAntiEntropyRepairs(promInst);
            Long migrationQueue     = prometheusClient.queryMigrationQueueSize(promInst);

            String status = deriveStatus(stats, gossipStatus);
            log.debug("Node snapshot built [nodeId={}, status={}]", nodeId, status);

            return new NodeSnapshot(
                    nodeId, host, port, status, entry.lbReachable(),
                    hitRate, missRate, totalHits, totalMisses, cacheSize, memoryUsed,
                    memoryUsedRatio, evictionsPerMin, p50LatencyMs, p99LatencyMs,
                    drainActive, selfHealing, antiEntropy, migrationQueue,
                    gossipStatus, gossipHeartbeat, secondsSinceUpdate,
                    incarnation, aliveCount, suspectCount, deadCount);
        }
    }

    private LoadBalancerSnapshot buildLbSnapshot(JsonNode dashboardExport, JsonNode ringInfo) {
        String lbStatus = dashboardExport.path("loadBalancerStatus").asText("UNKNOWN");
        List<String> activeNodeIds = new ArrayList<>();

        if (ringInfo != null) {
            JsonNode nodesArr = ringInfo.path("activeNodes");
            if (nodesArr.isArray()) nodesArr.forEach(n -> activeNodeIds.add(n.asText()));

            return new LoadBalancerSnapshot(
                    lbStatus,
                    ringInfo.path("nodeCount").asInt(0),
                    ringInfo.path("ringSize").asInt(0),
                    ringInfo.path("virtualNodesPerNode").asInt(0),
                    ringInfo.path("hashAlgorithm").asText("unknown"),
                    activeNodeIds);
        }
        int nodeCount = dashboardExport.path("nodes").size();
        return new LoadBalancerSnapshot(lbStatus, nodeCount, 0, 0, "unknown", activeNodeIds);
    }

    private String deriveStatus(JsonNode stats, String gossipStatus) {
        if (stats == null) return "UNREACHABLE";
        if ("DEAD".equalsIgnoreCase(gossipStatus)) return "DEAD";
        if ("SUSPECT".equalsIgnoreCase(gossipStatus) || "DRAINING".equalsIgnoreCase(gossipStatus)) return "SUSPECT";
        return "HEALTHY";
    }

    private List<NodeDiscoveryEntry> parseDiscoveredNodes(JsonNode nodesArray) {
        List<NodeDiscoveryEntry> out = new ArrayList<>();
        if (nodesArray == null || !nodesArray.isArray()) return out;

        for (JsonNode node : nodesArray) {
            String nodeId  = node.path("sourceNodeId").asText(null);
            String host    = node.path("sourceHost").asText(null);
            int port       = node.path("sourcePort").asInt(0);
            boolean reachable = node.path("reachable").asBoolean(true);

            if (nodeId != null && host != null && port > 0) {
                // Resolve Prometheus instance label BEFORE remapping (using Docker IP as key)
                String prometheusInstance = prometheusInstanceMap.get(host);

                // Remap Docker-internal IP → reachable host:port for HTTP calls
                String override = dockerNodeHostMap.get(host);
                if (override != null) {
                    String[] parts = override.split(":");
                    host = parts[0];
                    port = Integer.parseInt(parts[1]);
                    log.debug("Remapped {} to {}, prometheusInstance={}", nodeId, override, prometheusInstance);
                }
                out.add(new NodeDiscoveryEntry(nodeId, host, port, reachable, prometheusInstance));
            }
        }
        return out;
    }

    private String latencyTier(Double p99Ms) {
        if (p99Ms == null) return "UNKNOWN";
        if (p99Ms < 5)    return "FAST";
        if (p99Ms < 20)   return "OK";
        if (p99Ms < 100)  return "SLOW";
        return "CRITICAL";
    }

    private <T> T getSafe(CompletableFuture<T> future) {
        try { return future.get(SLO_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
        catch (Exception e) { return null; }
    }

    private java.util.concurrent.Executor runAsync() {
        return java.util.concurrent.ForkJoinPool.commonPool();
    }

    // ── Inner records ──────────────────────────────────────────────────────

    record NodeDiscoveryEntry(String nodeId, String host, int port, boolean lbReachable, String prometheusInstance) {}

    public record MetricsSummary(
            int lookbackMinutes, Double p99LatencySeconds,
            int errorLogCount, List<String> slowTraceIds, Instant snapshotTakenAt) {}

    public record SloStatus(
            Double availabilityRatio, Double burnRate1h, Double errorRate5m,
            String overallHealth, Instant assessedAt) {}

    public record NodeLatencyProfile(
            String nodeId, Double p50LatencyMs, Double p99LatencyMs, String performanceTier) {}

    public record HashRingInfo(
            String status, int nodeCount, int ringSize, int virtualNodesPerNode,
            String hashAlgorithm, List<String> activeNodeIds, Instant assessedAt) {}

    public record SelfHealingStatus(
            List<NodeHealingStats> nodes, long totalRepairsAcrossCluster, Instant assessedAt) {}

    public record NodeHealingStats(
            String nodeId, Long selfHealingAttempts, Long antiEntropyRepairs,
            Long migrationQueueSize, Boolean drainActive) {}
}
