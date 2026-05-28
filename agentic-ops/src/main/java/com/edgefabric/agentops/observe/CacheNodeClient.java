package com.edgefabric.agentops.observe;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/**
 * Fetches per-node cache statistics and gossip state from individual cache nodes.
 *
 * <p>All methods accept an explicit {@code host:port} so this component can target
 * any node discovered via the LB dashboard export. Failures are caught and logged.</p>
 */
@Component
public class CacheNodeClient {

    private static final Logger log = LoggerFactory.getLogger(CacheNodeClient.class);

    private final RestClient restClient;

    public CacheNodeClient(@Qualifier("cacheNodeRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Fetches cache statistics from {@code GET /api/v1/cache/stats} on a specific node.
     *
     * @param host node hostname or IP
     * @param port node service port
     * @return JSON node with stats fields, or null on any error
     */
    public JsonNode getStats(String host, int port) {
        String url = "http://" + host + ":" + port + "/api/v1/cache/stats";
        try {
            return restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("Cache stats fetch failed [node={}:{}]: {}", host, port, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches the full gossip membership table from {@code GET /internal/cluster/gossip}.
     *
     * @param host node hostname or IP
     * @param port node service port
     * @return JSON node with gossip table, or null on any error
     */
    public JsonNode getGossipTable(String host, int port) {
        String url = "http://" + host + ":" + port + "/internal/cluster/gossip";
        try {
            return restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("Gossip table fetch failed [node={}:{}]: {}", host, port, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches the alive member list from {@code GET /internal/cluster/members}.
     *
     * @param host node hostname or IP
     * @param port node service port
     * @return JSON node with members array, or null on any error
     */
    public JsonNode getMembers(String host, int port) {
        String url = "http://" + host + ":" + port + "/internal/cluster/members";
        try {
            return restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("Members fetch failed [node={}:{}]: {}", host, port, e.getMessage());
            return null;
        }
    }

    /**
     * Extracts a named double field from a stats response, returning null if absent.
     */
    public static Double extractDouble(JsonNode stats, String field) {
        if (stats == null || stats.path(field).isMissingNode()) {
            return null;
        }
        return stats.path(field).asDouble();
    }

    /**
     * Extracts a named long field from a stats response, returning null if absent.
     */
    public static Long extractLong(JsonNode stats, String field) {
        if (stats == null || stats.path(field).isMissingNode()) {
            return null;
        }
        return stats.path(field).asLong();
    }

    /** Extracts a root-level integer count from the gossip table (e.g. aliveCount, deadCount). */
    public static Integer extractGossipCount(JsonNode gossipTable, String field) {
        if (gossipTable == null || gossipTable.path(field).isMissingNode()) return null;
        return gossipTable.path(field).asInt();
    }

    /** Extracts the self member's gossip status, or null. */
    public static String extractSelfGossipStatus(JsonNode gossipTable) {
        return findSelfMember(gossipTable)
                .map(self -> self.path("status").asText(null))
                .orElse(null);
    }

    /** Extracts the self member's heartbeat counter, or null. */
    public static Long extractSelfHeartbeat(JsonNode gossipTable) {
        return findSelfMember(gossipTable)
                .map(self -> self.path("heartbeat").asLong())
                .orElse(null);
    }

    /** Extracts seconds since the self member's gossip entry was last updated, or null. */
    public static Long extractSelfSecondsSinceUpdate(JsonNode gossipTable) {
        return findSelfMember(gossipTable)
                .map(self -> self.path("secondsSinceUpdate").asLong())
                .orElse(null);
    }

    /** Extracts the incarnation number from the self member (increments on restart), or null. */
    public static Integer extractSelfIncarnation(JsonNode gossipTable) {
        return findSelfMember(gossipTable)
                .map(self -> {
                    JsonNode n = self.path("incarnation");
                    return n.isMissingNode() ? null : n.asInt();
                })
                .orElse(null);
    }

    private static Optional<JsonNode> findSelfMember(JsonNode gossipTable) {
        if (gossipTable == null) {
            return Optional.empty();
        }
        JsonNode members = gossipTable.path("members");
        for (JsonNode member : members) {
            if (member.path("self").asBoolean(false)) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
    }
}
