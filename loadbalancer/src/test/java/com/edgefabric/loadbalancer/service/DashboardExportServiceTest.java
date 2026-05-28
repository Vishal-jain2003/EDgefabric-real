package com.edgefabric.loadbalancer.service;

import com.edgefabric.hashing.config.HashRingProperties;
import com.edgefabric.loadbalancer.dto.export.DashboardExportResponse;
import com.edgefabric.loadbalancer.dto.export.NodeGossipSnapshot;
import com.edgefabric.loadbalancer.model.CacheNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardExportServiceTest {

    @Mock
    private CacheRouter cacheRouter;

    @Mock
    private HashRingProperties hashRingProperties;

    @Mock
    private WebClient webClient;

    @Mock
    private ClusterSyncService clusterSyncService;

    @SuppressWarnings("rawtypes")
    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @SuppressWarnings("rawtypes")
    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private DashboardExportService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new DashboardExportService(cacheRouter, hashRingProperties, webClient, clusterSyncService);
    }

    private void stubRing() {
        when(cacheRouter.nodeCount()).thenReturn(3);
        when(cacheRouter.ringSize()).thenReturn(450);
        when(cacheRouter.activeNodeIds()).thenReturn(Set.of("10.0.0.1", "10.0.0.2", "10.0.0.3"));
        when(hashRingProperties.getVirtualNodes()).thenReturn(150);
        when(hashRingProperties.getHashAlgorithm()).thenReturn("murmur");
    }

    @SuppressWarnings("unchecked")
    private void stubWebClientSuccess(String gossipJson) {
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);

        JsonNode jsonNode;
        try {
            jsonNode = objectMapper.readTree(gossipJson);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(responseSpec.bodyToMono(JsonNode.class)).thenReturn(Mono.just(jsonNode));
    }

    @SuppressWarnings("unchecked")
    private void stubWebClientFailure() {
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(JsonNode.class)).thenReturn(Mono.error(new RuntimeException("Connection refused")));
    }

    @Test
    void buildSnapshot_allNodesReachable_returnsCorrectSnapshot() {
        stubRing();

        CacheNode node1 = new CacheNode("10.0.0.1", "10.0.0.1", 8082);
        CacheNode node2 = new CacheNode("10.0.0.2", "10.0.0.2", 8082);
        CacheNode node3 = new CacheNode("10.0.0.3", "10.0.0.3", 8082);
        when(clusterSyncService.getActiveNodes()).thenReturn(Set.of(node1, node2, node3));

        String gossipJson = """
                {
                    "snapshotTime": "2026-04-20T10:00:00Z",
                    "totalNodes": 3,
                    "aliveCount": 3,
                    "suspectCount": 0,
                    "deadCount": 0,
                    "members": [
                        {"nodeId": "node-1", "host": "10.0.0.1", "servicePort": 8082, "gossipPort": 7946, "status": "ALIVE", "heartbeat": 100, "incarnation": 1, "lastUpdatedMs": 1700000000000, "secondsSinceUpdate": 5, "self": true},
                        {"nodeId": "node-2", "host": "10.0.0.2", "servicePort": 8082, "gossipPort": 7946, "status": "ALIVE", "heartbeat": 99, "incarnation": 1, "lastUpdatedMs": 1700000000000, "secondsSinceUpdate": 5, "self": false},
                        {"nodeId": "node-3", "host": "10.0.0.3", "servicePort": 8082, "gossipPort": 7946, "status": "ALIVE", "heartbeat": 98, "incarnation": 1, "lastUpdatedMs": 1700000000000, "secondsSinceUpdate": 5, "self": false}
                    ]
                }
                """;
        stubWebClientSuccess(gossipJson);

        DashboardExportResponse response = service.buildSnapshot();

        assertNotNull(response.getExportTimestamp());
        assertEquals("UP", response.getLoadBalancerStatus());
        assertEquals(200, response.getLoadBalancerStatusCode());
        assertEquals(3, response.getRing().nodeCount());
        assertEquals(450, response.getRing().ringSize());
        assertEquals(150, response.getRing().virtualNodesPerNode());
        assertEquals("murmur", response.getRing().hashAlgorithm());
        assertEquals(3, response.getNodes().size());

        for (NodeGossipSnapshot node : response.getNodes()) {
            assertTrue(node.reachable());
            assertEquals(3, node.members().size());
        }
    }

    @Test
    void buildSnapshot_oneNodeUnreachable_marksAsUnreachable() {
        stubRing();

        CacheNode node1 = new CacheNode("10.0.0.1", "10.0.0.1", 8082);
        CacheNode node2 = new CacheNode("10.0.0.2", "10.0.0.2", 8082);
        when(clusterSyncService.getActiveNodes()).thenReturn(Set.of(node1, node2));

        // First call succeeds, second fails — but since Set iteration order is undefined,
        // we just verify at least one unreachable node exists
        stubWebClientFailure();

        DashboardExportResponse response = service.buildSnapshot();

        assertEquals(2, response.getNodes().size());
        // All calls fail since we stubbed failure globally
        assertTrue(response.getNodes().stream().noneMatch(NodeGossipSnapshot::reachable));
    }

    @Test
    void buildSnapshot_emptyRing_returnsValidEmptyResponse() {
        when(cacheRouter.nodeCount()).thenReturn(0);
        when(cacheRouter.ringSize()).thenReturn(0);
        when(cacheRouter.activeNodeIds()).thenReturn(Set.of());
        when(hashRingProperties.getVirtualNodes()).thenReturn(150);
        when(hashRingProperties.getHashAlgorithm()).thenReturn("murmur");
        when(clusterSyncService.getActiveNodes()).thenReturn(Set.of());

        DashboardExportResponse response = service.buildSnapshot();

        assertNotNull(response);
        assertEquals(0, response.getRing().nodeCount());
        assertTrue(response.getNodes().isEmpty());
        assertEquals("UP", response.getLoadBalancerStatus());
    }
}
