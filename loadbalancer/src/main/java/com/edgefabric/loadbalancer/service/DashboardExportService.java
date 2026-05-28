package com.edgefabric.loadbalancer.service;

import com.edgefabric.hashing.config.HashRingProperties;
import com.edgefabric.loadbalancer.dto.export.DashboardExportResponse;
import com.edgefabric.loadbalancer.dto.export.GossipMemberSnapshot;
import com.edgefabric.loadbalancer.dto.export.NodeGossipSnapshot;
import com.edgefabric.loadbalancer.dto.export.RingSnapshot;
import com.edgefabric.loadbalancer.model.CacheNode;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class DashboardExportService {

    private static final Logger log = LoggerFactory.getLogger(DashboardExportService.class);
    private static final Duration GOSSIP_TIMEOUT = Duration.ofMillis(5000);

    private final CacheRouter cacheRouter;
    private final HashRingProperties hashRingProperties;
    private final WebClient webClient;
    private final ClusterSyncService clusterSyncService;

    public DashboardExportService(CacheRouter cacheRouter,
                                  HashRingProperties hashRingProperties,
                                  WebClient webClient,
                                  ClusterSyncService clusterSyncService) {
        this.cacheRouter = cacheRouter;
        this.hashRingProperties = hashRingProperties;
        this.webClient = webClient;
        this.clusterSyncService = clusterSyncService;
    }

    public DashboardExportResponse buildSnapshot() {
        RingSnapshot ring = new RingSnapshot(
                cacheRouter.nodeCount(),
                cacheRouter.ringSize(),
                hashRingProperties.getVirtualNodes(),
                hashRingProperties.getHashAlgorithm(),
                List.copyOf(cacheRouter.activeNodeIds())
        );

        List<NodeGossipSnapshot> nodeSnapshots = new ArrayList<>();
        for (CacheNode node : clusterSyncService.getActiveNodes()) {
            nodeSnapshots.add(fetchGossipSnapshot(node));
        }

        return DashboardExportResponse.builder()
                .exportTimestamp(Instant.now().toString())
                .loadBalancerStatus("UP")
                .loadBalancerStatusCode(200)
                .ring(ring)
                .nodes(nodeSnapshots)
                .build();
    }

    private NodeGossipSnapshot fetchGossipSnapshot(CacheNode node) {
        try {
            String url = "http://" + node.getHost() + ":" + node.getPort() + "/internal/cluster/gossip";

            JsonNode body = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(GOSSIP_TIMEOUT);

            if (body == null) {
                return unreachableSnapshot(node);
            }

            List<GossipMemberSnapshot> members = new ArrayList<>();
            JsonNode membersNode = body.get("members");
            if (membersNode != null && membersNode.isArray()) {
                for (JsonNode m : membersNode) {
                    members.add(new GossipMemberSnapshot(
                            m.path("nodeId").asText(),
                            m.path("host").asText(),
                            m.path("servicePort").asInt(),
                            m.path("gossipPort").asInt(),
                            m.path("status").asText(),
                            m.path("heartbeat").asLong(),
                            m.path("incarnation").asLong(),
                            m.path("lastUpdatedMs").asLong(),
                            m.path("secondsSinceUpdate").asLong(),
                            m.path("self").asBoolean()
                    ));
                }
            }

            return new NodeGossipSnapshot(
                    node.getNodeId(),
                    node.getHost(),
                    node.getPort(),
                    true,
                    body.path("snapshotTime").asText(),
                    body.path("totalNodes").asInt(),
                    body.path("aliveCount").asLong(),
                    body.path("suspectCount").asLong(),
                    body.path("deadCount").asLong(),
                    members
            );

        } catch (Exception e) {
            log.warn("Failed to fetch gossip from node {}: {}", node, e.getMessage());
            return unreachableSnapshot(node);
        }
    }

    private NodeGossipSnapshot unreachableSnapshot(CacheNode node) {
        return new NodeGossipSnapshot(
                node.getNodeId(),
                node.getHost(),
                node.getPort(),
                false,
                null,
                0,
                0,
                0,
                0,
                List.of()
        );
    }
}
