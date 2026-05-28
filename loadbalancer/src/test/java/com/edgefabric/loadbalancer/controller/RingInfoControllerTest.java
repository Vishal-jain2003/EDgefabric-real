package com.edgefabric.loadbalancer.controller;

import com.edgefabric.hashing.config.HashRingProperties;
import com.edgefabric.loadbalancer.config.CacheProperties;
import com.edgefabric.loadbalancer.exception.GlobalExceptionHandler;
import com.edgefabric.loadbalancer.model.CacheNode;
import com.edgefabric.loadbalancer.service.CacheRouter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(GlobalExceptionHandler.class)
@WebMvcTest(controllers = RingInfoController.class)
class RingInfoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CacheRouter cacheRouter;

    @MockitoBean
    private HashRingProperties hashRingProperties;

    @MockitoBean
    private CacheProperties cacheProperties;

    // ── /ring/info ────────────────────────────────────────────────────────────

    @Test
    void ringInfo_returnsCorrectSnapshot() throws Exception {
        when(cacheRouter.nodeCount()).thenReturn(3);
        when(cacheRouter.ringSize()).thenReturn(450);
        when(cacheRouter.activeNodeIds()).thenReturn(Set.of("node-1", "node-2", "node-3"));
        when(hashRingProperties.getVirtualNodes()).thenReturn(150);
        when(hashRingProperties.getHashAlgorithm()).thenReturn("murmur");

        mockMvc.perform(get("/api/v1/internal/ring/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeCount").value(3))
                .andExpect(jsonPath("$.ringSize").value(450))
                .andExpect(jsonPath("$.virtualNodesPerNode").value(150))
                .andExpect(jsonPath("$.hashAlgorithm").value("murmur"))
                .andExpect(jsonPath("$.activeNodes").isArray());
    }

    @Test
    void ringInfo_emptyRing_returnsZeroCounts() throws Exception {
        when(cacheRouter.nodeCount()).thenReturn(0);
        when(cacheRouter.ringSize()).thenReturn(0);
        when(cacheRouter.activeNodeIds()).thenReturn(Set.of());
        when(hashRingProperties.getVirtualNodes()).thenReturn(150);
        when(hashRingProperties.getHashAlgorithm()).thenReturn("murmur");

        mockMvc.perform(get("/api/v1/internal/ring/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeCount").value(0))
                .andExpect(jsonPath("$.ringSize").value(0))
                .andExpect(jsonPath("$.activeNodes").isEmpty());
    }

    // ── /ring/route ───────────────────────────────────────────────────────────

    @Test
    void ringRoute_returnsPrimaryAndReplicas() throws Exception {
        CacheNode primary  = new CacheNode("node-2", "10.0.0.2", 8082);
        CacheNode replica1 = new CacheNode("node-2", "10.0.0.2", 8082);
        CacheNode replica2 = new CacheNode("node-3", "10.0.0.3", 8082);

        when(cacheRouter.nodeCount()).thenReturn(3);
        when(cacheRouter.route("user:123")).thenReturn(primary);
        when(cacheRouter.routeToReplicas(anyString(), anyInt()))
                .thenReturn(List.of(replica1, replica2));
        when(hashRingProperties.getVirtualNodes()).thenReturn(150);

        mockMvc.perform(get("/api/v1/internal/ring/route").param("key", "user:123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("user:123"))
                .andExpect(jsonPath("$.primaryNode.nodeId").value("node-2"))
                .andExpect(jsonPath("$.primaryNode.host").value("10.0.0.2"))
                .andExpect(jsonPath("$.primaryNode.port").value(8082))
                .andExpect(jsonPath("$.replicas").isArray())
                .andExpect(jsonPath("$.replicas.length()").value(2));
    }

    @Test
    void ringRoute_emptyRing_returnsNullPrimaryAndEmptyReplicas() throws Exception {
        when(cacheRouter.nodeCount()).thenReturn(0);
        when(hashRingProperties.getVirtualNodes()).thenReturn(150);

        mockMvc.perform(get("/api/v1/internal/ring/route").param("key", "some-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("some-key"))
                .andExpect(jsonPath("$.primaryNode").doesNotExist())
                .andExpect(jsonPath("$.replicas").isEmpty());
    }

    @Test
    void ringRoute_missingKeyParam_returnsErrorStatus() throws Exception {
        // Spring/GlobalExceptionHandler returns an error response when required param is absent
        mockMvc.perform(get("/api/v1/internal/ring/route"))
                .andExpect(status().is5xxServerError());
    }
}
