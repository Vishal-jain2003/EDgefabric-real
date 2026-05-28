package com.edgefabric.agentops.observe;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Fetches cluster topology data from the EdgeFabric load balancer.
 *
 * <p>The LB is the canonical source for node discovery — it already resolves
 * node IPs via {@code CloudMapDnsResolver} and maintains the consistent hash
 * ring. {@code agentic-ops} reads those results rather than duplicating that logic.</p>
 */
@Component
public class LoadBalancerClient {

    private static final Logger log = LoggerFactory.getLogger(LoadBalancerClient.class);

    private final RestClient restClient;

    public LoadBalancerClient(@Qualifier("loadBalancerRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Fetches the full cluster dashboard snapshot from {@code GET /api/v1/dashboard/export}.
     *
     * <p>This is the primary node-discovery source: the response contains a
     * {@code nodes} array with one entry per cache node, each with
     * {@code sourceNodeId}, {@code sourceHost}, {@code sourcePort}, and gossip members.</p>
     *
     * @return JSON node for the dashboard export response, or null if the LB is unreachable
     */
    public JsonNode getDashboardExport() {
        try {
            return restClient.get()
                    .uri("/api/v1/dashboard/export")
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.error("Failed to fetch LB dashboard export — node discovery unavailable: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fetches the consistent hash ring topology from {@code GET /api/v1/internal/ring/info}.
     *
     * @return JSON node with ring info (nodeCount, ringSize, virtualNodesPerNode, hashAlgorithm, activeNodes),
     *         or null if the LB is unreachable
     */
    public JsonNode getRingInfo() {
        try {
            return restClient.get()
                    .uri("/api/v1/internal/ring/info")
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("Failed to fetch ring info: {}", e.getMessage());
            return null;
        }
    }
}
