package com.edgefabric.loadbalancer.controller;

import com.edgefabric.hashing.config.HashRingProperties;
import com.edgefabric.loadbalancer.dto.RingInfoResponse;
import com.edgefabric.loadbalancer.dto.RingRouteResponse;
import com.edgefabric.loadbalancer.model.CacheNode;
import com.edgefabric.loadbalancer.service.CacheRouter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal diagnostic endpoints for inspecting consistent hash ring state.
 * Useful for debugging routing behaviour, verifying node distribution,
 * and driving E2E tests without needing a Prometheus scraper.
 *
 * Base path: /api/v1/internal/ring
 */
@RestController
@RequestMapping("/api/v1/internal/ring")
public class RingInfoController {

    private final CacheRouter cacheRouter;
    private final HashRingProperties hashRingProperties;

    public RingInfoController(CacheRouter cacheRouter, HashRingProperties hashRingProperties) {
        this.cacheRouter = cacheRouter;
        this.hashRingProperties = hashRingProperties;
    }

    /**
     * Returns a snapshot of the current ring state.
     *
     * <pre>
     * GET /api/v1/internal/ring/info
     * {
     *   "nodeCount": 3,
     *   "ringSize": 450,
     *   "virtualNodesPerNode": 150,
     *   "hashAlgorithm": "xxhash",
     *   "activeNodes": ["10.0.0.1", "10.0.0.2", "10.0.0.3"]
     * }
     * </pre>
     */
    @GetMapping("/info")
    public ResponseEntity<RingInfoResponse> ringInfo() {
        int nodeCount = cacheRouter.nodeCount();
        int ringSize  = cacheRouter.ringSize();
        int vNodes    = hashRingProperties.getVirtualNodes();
        String algo   = hashRingProperties.getHashAlgorithm();
        List<String> activeNodes = List.copyOf(cacheRouter.activeNodeIds());

        return ResponseEntity.ok(new RingInfoResponse(nodeCount, ringSize, vNodes, algo, activeNodes));
    }

    /**
     * Shows which nodes the ring would route a key to.
     *
     * <pre>
     * GET /api/v1/internal/ring/route?key=user:123
     * {
     *   "key": "user:123",
     *   "primaryNode": {"nodeId": "10.0.0.2", "host": "10.0.0.2", "port": 8082},
     *   "replicas": [
     *     {"nodeId": "10.0.0.2", "host": "10.0.0.2", "port": 8082},
     *     {"nodeId": "10.0.0.3", "host": "10.0.0.3", "port": 8082},
     *     {"nodeId": "10.0.0.1", "host": "10.0.0.1", "port": 8082}
     *   ]
     * }
     * </pre>
     *
     * @param key the routing key to look up (must not be blank)
     */
    @GetMapping("/route")
    public ResponseEntity<RingRouteResponse> ringRoute(@RequestParam String key) {
        if (cacheRouter.nodeCount() == 0) {
            return ResponseEntity.ok(new RingRouteResponse(key, null, List.of()));
        }

        CacheNode primary = cacheRouter.route(key);
        List<CacheNode> replicaNodes = cacheRouter.routeToReplicas(key, hashRingProperties.getVirtualNodes());

        RingRouteResponse.NodeInfo primaryInfo = toNodeInfo(primary);
        List<RingRouteResponse.NodeInfo> replicaInfos = replicaNodes.stream()
                .map(this::toNodeInfo)
                .toList();

        return ResponseEntity.ok(new RingRouteResponse(key, primaryInfo, replicaInfos));
    }

    private RingRouteResponse.NodeInfo toNodeInfo(CacheNode node) {
        return new RingRouteResponse.NodeInfo(node.getNodeId(), node.getHost(), node.getPort());
    }
}
