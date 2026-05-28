package com.edgefabric.agentops.observe;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fetches log data from Loki using the log query API.
 *
 * <p>Used to count error log occurrences (e.g. "quorum write timeout") over a
 * recent time window. Failures are caught and logged so a Loki outage does not
 * break the cluster snapshot.</p>
 */
@Component
public class LokiClient {

    private static final Logger log = LoggerFactory.getLogger(LokiClient.class);

    private final RestClient restClient;

    public LokiClient(@Qualifier("lokiRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Queries Loki for log lines matching the given LogQL expression over a time range.
     *
     * @param logql  LogQL stream selector + filter (e.g. {@code {service="edgefabric-caching"} |= "ERROR"})
     * @param start  nanosecond Unix timestamp string for range start
     * @param end    nanosecond Unix timestamp string for range end
     * @param limit  max number of log lines to return (Loki default: 100)
     * @return list of raw log line strings; empty on any error
     */
    public List<String> queryRange(String logql, String start, String end, int limit) {
        try {
            JsonNode body = restClient.get()
                    .uri(b -> b.path("/loki/api/v1/query_range")
                            .queryParam("query", "{q}")
                            .queryParam("start", start)
                            .queryParam("end", end)
                            .queryParam("limit", limit)
                            .build(Map.of("q", logql)))
                    .retrieve()
                    .body(JsonNode.class);

            return parseLogLines(body);
        } catch (RestClientException e) {
            log.warn("Loki query failed [query={}]: {}", logql, e.getMessage());
            return List.of();
        }
    }

    /**
     * Counts the number of error log lines matching {@code errorPattern} in the last {@code lookbackMinutes}.
     *
     * @param service         Loki service label (e.g. "edgefabric-caching")
     * @param errorPattern    substring to search for (e.g. "quorum write timeout")
     * @param lookbackMinutes number of minutes to look back
     * @return count of matching log lines; 0 on any error
     */
    public int countErrors(String service, String errorPattern, int lookbackMinutes) {
        long nowNs    = System.currentTimeMillis() * 1_000_000L;
        long startNs  = nowNs - (long) lookbackMinutes * 60 * 1_000_000_000L;
        String logql  = String.format("{service=\"%s\"} |= \"%s\"", service, errorPattern);

        List<String> lines = queryRange(logql, String.valueOf(startNs), String.valueOf(nowNs), 1000);
        return lines.size();
    }

    /**
     * Fetches recent log lines from Loki with timestamps.
     *
     * @param service         service label (e.g. "edgefabric-caching")
     * @param level           log level filter string (e.g. "ERROR", "WARN")
     * @param lookbackMinutes number of minutes to look back
     * @param limit           max lines to return
     * @return list of {@link LogLine} records, newest last; empty on any error
     */
    public List<LogLine> fetchRecentLogs(String service, String level, int lookbackMinutes, int limit) {
        long nowNs   = System.currentTimeMillis() * 1_000_000L;
        long startNs = nowNs - (long) lookbackMinutes * 60 * 1_000_000_000L;
        String logql = String.format("{service=\"%s\"} |= \"%s\"", service, level);

        try {
            JsonNode body = restClient.get()
                    .uri(b -> b.path("/loki/api/v1/query_range")
                            .queryParam("query", "{q}")
                            .queryParam("start", startNs)
                            .queryParam("end", nowNs)
                            .queryParam("limit", limit)
                            .build(Map.of("q", logql)))
                    .retrieve()
                    .body(JsonNode.class);
            return parseLogLinesWithTimestamp(body);
        } catch (RestClientException e) {
            log.warn("Loki fetchRecentLogs failed [service={}, level={}]: {}", service, level, e.getMessage());
            return List.of();
        }
    }

    /** A single timestamped log line from Loki. */
    public record LogLine(String timestamp, String message) {}

    // ── private helpers ────────────────────────────────────────────────────

    private List<String> parseLogLines(JsonNode body) {
        List<String> lines = new ArrayList<>();
        if (body == null || !"success".equals(body.path("status").asText())) {
            return lines;
        }
        JsonNode results = body.path("data").path("result");
        for (JsonNode stream : results) {
            JsonNode values = stream.path("values");
            for (JsonNode entry : values) {
                if (entry.isArray() && entry.size() > 1) {
                    lines.add(entry.get(1).asText());
                }
            }
        }
        return lines;
    }

    private List<LogLine> parseLogLinesWithTimestamp(JsonNode body) {
        List<LogLine> lines = new ArrayList<>();
        if (body == null || !"success".equals(body.path("status").asText())) return lines;
        JsonNode results = body.path("data").path("result");
        for (JsonNode stream : results) {
            JsonNode values = stream.path("values");
            for (JsonNode entry : values) {
                if (entry.isArray() && entry.size() > 1) {
                    long tsNs = entry.get(0).asLong();
                    String ts = java.time.Instant.ofEpochSecond(
                            tsNs / 1_000_000_000L, tsNs % 1_000_000_000L).toString();
                    lines.add(new LogLine(ts, entry.get(1).asText()));
                }
            }
        }
        return lines;
    }
}
