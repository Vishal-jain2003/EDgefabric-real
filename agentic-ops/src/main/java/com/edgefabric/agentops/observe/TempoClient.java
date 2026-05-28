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

/**
 * Fetches distributed trace data from Tempo.
 *
 * <p>Used to identify slow-trace root causes per node. Failures are caught and
 * logged so a Tempo outage does not break the cluster snapshot.</p>
 */
@Component
public class TempoClient {

    private static final Logger log = LoggerFactory.getLogger(TempoClient.class);

    private final RestClient restClient;

    public TempoClient(@Qualifier("tempoRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Searches Tempo for recent traces matching the given service and minimum duration.
     *
     * @param serviceName     service name label (e.g. "edgefabric-loadbalancer")
     * @param minDurationMs   minimum span duration in milliseconds to consider "slow"
     * @param limit           max number of trace results
     * @return list of trace IDs (strings); empty on any error
     */
    public List<String> searchSlowTraces(String serviceName, long minDurationMs, int limit) {
        try {
            JsonNode body = restClient.get()
                    .uri(uri -> uri.path("/api/search")
                            .queryParam("service.name", serviceName)
                            .queryParam("minDuration", minDurationMs + "ms")
                            .queryParam("limit", limit)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);

            return parseTraceIds(body);
        } catch (RestClientException e) {
            log.warn("Tempo search failed [service={}, minDuration={}ms]: {}",
                    serviceName, minDurationMs, e.getMessage());
            return List.of();
        }
    }

    /**
     * Fetches a specific trace by ID.
     *
     * @param traceId the trace ID to retrieve
     * @return raw JSON node for the trace, or null on any error
     */
    public JsonNode getTrace(String traceId) {
        try {
            return restClient.get()
                    .uri("/api/traces/{id}", traceId)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("Tempo get trace failed [traceId={}]: {}", traceId, e.getMessage());
            return null;
        }
    }

    // ── private helpers ────────────────────────────────────────────────────

    private List<String> parseTraceIds(JsonNode body) {
        List<String> ids = new ArrayList<>();
        if (body == null) {
            return ids;
        }
        JsonNode traces = body.path("traces");
        for (JsonNode trace : traces) {
            String id = trace.path("traceID").asText(null);
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }
}
