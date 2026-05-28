package com.edgefabric.agentops.observe;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.Map;

/**
 * Fetches metrics from Prometheus using the HTTP query API.
 *
 * <p>Supports instant vector queries ({@code /api/v1/query}) and range queries
 * ({@code /api/v1/query_range}). All failures are caught and logged; callers
 * receive an empty map rather than a propagated exception.</p>
 */
@Component
public class PrometheusClient {

    private static final Logger log = LoggerFactory.getLogger(PrometheusClient.class);

    private final RestClient restClient;

    public PrometheusClient(@Qualifier("prometheusRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Runs a PromQL instant query and returns all result samples as a map of
     * {@code metric-labels → scalar-value}.
     *
     * @param promql PromQL expression
     * @return map of metric label string → value string; empty on any error
     */
    public Map<String, String> query(String promql) {
        try {
            JsonNode body = restClient.get()
                    .uri(b -> b.path("/api/v1/query").queryParam("query", "{q}").build(Map.of("q", promql)))
                    .retrieve()
                    .body(JsonNode.class);

            return parseInstantResult(body);
        } catch (RestClientException e) {
            log.warn("Prometheus query failed [query={}]: {}", promql, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Runs a PromQL range query and returns the last sample for each metric series.
     *
     * @param promql PromQL expression
     * @param start  RFC-3339 / Unix timestamp for range start
     * @param end    RFC-3339 / Unix timestamp for range end
     * @param step   step interval (e.g. "30s", "1m")
     * @return map of metric label string → last value string; empty on any error
     */
    public Map<String, String> queryRange(String promql, String start, String end, String step) {
        try {
            JsonNode body = restClient.get()
                    .uri(b -> b.path("/api/v1/query_range")
                            .queryParam("query", "{q}")
                            .queryParam("start", start)
                            .queryParam("end", end)
                            .queryParam("step", step)
                            .build(Map.of("q", promql)))
                    .retrieve()
                    .body(JsonNode.class);

            return parseRangeResult(body);
        } catch (RestClientException e) {
            log.warn("Prometheus range query failed [query={}]: {}", promql, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Convenience method: queries the JVM heap used ratio for a given instance.
     */
    public Double queryMemoryUsedRatio(String instance) {
        String promql = String.format(
                "jvm_memory_used_bytes{instance=\"%s\",area=\"heap\"} / jvm_memory_max_bytes{instance=\"%s\",area=\"heap\"}",
                instance, instance);
        return querySingleDouble(promql);
    }

    /**
     * Convenience method: queries the cache eviction rate (per minute) for a given instance.
     */
    public Double queryEvictionsPerMin(String instance) {
        String promql = String.format(
                "rate(edgefabric_cache_evictions_total{instance=\"%s\"}[1m]) * 60",
                instance);
        return querySingleDouble(promql);
    }

    /** Cluster-wide SLO availability ratio (recording rule). 1.0 = 100% available. */
    public Double querySloAvailability() {
        return querySingleDouble("job:edgefabric_availability:ratio_1m");
    }

    /** SLO error budget burn rate over the last 1 hour (recording rule). Values > 1 are bad. */
    public Double queryBurnRate1h() {
        return querySingleDouble("job:edgefabric_burn_rate:1h");
    }

    /** Cluster-wide error rate ratio over the last 5 minutes (recording rule). */
    public Double queryErrorRate5m() {
        return querySingleDouble("job:edgefabric_error_rate:ratio_5m");
    }

    /**
     * P99 HTTP server latency in milliseconds for a specific Prometheus instance.
     *
     * @param prometheusInstance Prometheus instance label (e.g. "cache-node-1:8082")
     */
    public Double queryP99LatencyMs(String prometheusInstance) {
        if (prometheusInstance == null) return null;
        String promql = String.format(
                "histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{instance=\"%s\"}[5m]))) * 1000",
                prometheusInstance);
        return querySingleDouble(promql);
    }

    /**
     * P50 HTTP server latency in milliseconds for a specific Prometheus instance.
     */
    public Double queryP50LatencyMs(String prometheusInstance) {
        if (prometheusInstance == null) return null;
        String promql = String.format(
                "histogram_quantile(0.50, sum by (le) (rate(http_server_requests_seconds_bucket{instance=\"%s\"}[5m]))) * 1000",
                prometheusInstance);
        return querySingleDouble(promql);
    }

    /** Whether the node is currently draining (0 = no, 1 = yes). Returns null if unavailable. */
    public Boolean queryDrainActive(String prometheusInstance) {
        if (prometheusInstance == null) return null;
        Double val = querySingleDouble(
                String.format("edgefabric_node_drain_active{instance=\"%s\"}", prometheusInstance));
        return val != null ? val > 0 : null;
    }

    /** Total self-healing attempts since node start. */
    public Long querySelfHealingAttempts(String prometheusInstance) {
        if (prometheusInstance == null) return null;
        Double val = querySingleDouble(
                String.format("edgefabric_self_healing_attempts_total{instance=\"%s\"}", prometheusInstance));
        return val != null ? val.longValue() : null;
    }

    /** Total anti-entropy repair operations issued. */
    public Long queryAntiEntropyRepairs(String prometheusInstance) {
        if (prometheusInstance == null) return null;
        Double val = querySingleDouble(
                String.format("edgefabric_anti_entropy_repairs_issued_total{instance=\"%s\"}", prometheusInstance));
        return val != null ? val.longValue() : null;
    }

    /** Current number of keys queued for migration (consistent-hashing rebalance). */
    public Long queryMigrationQueueSize(String prometheusInstance) {
        if (prometheusInstance == null) return null;
        Double val = querySingleDouble(
                String.format("cache_migration_queue_size{instance=\"%s\"}", prometheusInstance));
        return val != null ? val.longValue() : null;
    }

    // ── private helpers ────────────────────────────────────────────────────

    /** Runs an instant PromQL query and returns the first valid scalar value, or null. */
    private Double querySingleDouble(String promql) {
        Map<String, String> result = query(promql);
        return result.values().stream()
                .filter(v -> v != null && !v.equals("NaN") && !v.equals("+Inf") && !v.equals("-Inf"))
                .findFirst()
                .map(this::parseDouble)
                .orElse(null);
    }

    private Map<String, String> parseInstantResult(JsonNode body) {
        Map<String, String> out = new HashMap<>();
        if (body == null || !"success".equals(body.path("status").asText())) {
            return out;
        }
        JsonNode results = body.path("data").path("result");
        for (JsonNode item : results) {
            String key = item.path("metric").toString();
            JsonNode valueNode = item.path("value");
            if (valueNode.isArray() && valueNode.size() > 1) {
                out.put(key, valueNode.get(1).asText());
            }
        }
        return out;
    }

    private Map<String, String> parseRangeResult(JsonNode body) {
        Map<String, String> out = new HashMap<>();
        if (body == null || !"success".equals(body.path("status").asText())) {
            return out;
        }
        JsonNode results = body.path("data").path("result");
        for (JsonNode item : results) {
            String key = item.path("metric").toString();
            JsonNode values = item.path("values");
            if (values.isArray() && !values.isEmpty()) {
                JsonNode last = values.get(values.size() - 1);
                if (last.isArray() && last.size() > 1) {
                    out.put(key, last.get(1).asText());
                }
            }
        }
        return out;
    }

    private Double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
