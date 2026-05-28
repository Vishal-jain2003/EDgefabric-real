package com.edgefabric.agentops.controller;

import com.edgefabric.agentops.observe.ClusterSnapshot;
import com.edgefabric.agentops.observe.LoadBalancerSnapshot;
import com.edgefabric.agentops.observe.NodeSnapshot;
import com.edgefabric.agentops.observe.ObserveService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ObserveController.class)
class ObserveControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ObserveService observeService;

    @Test
    void snapshot_returns200WithClusterSnapshot() throws Exception {
        NodeSnapshot node = new NodeSnapshot(
                "10.0.0.1:8082", "10.0.0.1", 8082, "HEALTHY", true,
                85.0, 15.0, 1000L, 150L, 512L, 1024L,
                0.65, 3.2, 2.1, 4.5, false, 0L, 0L, 0L,
                "ALIVE", 42L, 2L, 1, 1, 0, 0
        );
        LoadBalancerSnapshot lb = new LoadBalancerSnapshot("UP", 1, 150, 150, "xxhash", List.of("10.0.0.1:8082"));
        ClusterSnapshot snapshot = new ClusterSnapshot(1, 1, 0, 0, Instant.now(), lb, List.of(node),
                1000L, 150L, 0.87, null, null, null);

        when(observeService.getSnapshot()).thenReturn(snapshot);

        mockMvc.perform(get("/api/v1/observe/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalNodes").value(1))
                .andExpect(jsonPath("$.healthyNodes").value(1))
                .andExpect(jsonPath("$.loadBalancer.status").value("UP"))
                .andExpect(jsonPath("$.nodes[0].nodeId").value("10.0.0.1:8082"))
                .andExpect(jsonPath("$.nodes[0].status").value("HEALTHY"));
    }

    @Test
    void nodeHealth_returns200_whenNodeExists() throws Exception {
        NodeSnapshot node = new NodeSnapshot(
                "10.0.0.1:8082", "10.0.0.1", 8082, "HEALTHY", true,
                90.0, 10.0, 900L, 100L, 300L, 512L,
                0.5, 1.0, 1.8, 3.2, false, 0L, 0L, 0L,
                "ALIVE", 10L, 1L, 1, 1, 0, 0
        );
        when(observeService.getNodeHealth("10.0.0.1:8082")).thenReturn(node);

        mockMvc.perform(get("/api/v1/observe/node/10.0.0.1:8082"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value("10.0.0.1:8082"))
                .andExpect(jsonPath("$.status").value("HEALTHY"));
    }

    @Test
    void nodeHealth_returns404_whenNodeNotFound() throws Exception {
        when(observeService.getNodeHealth("unknown")).thenReturn(null);

        mockMvc.perform(get("/api/v1/observe/node/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void metricsSummary_returns200WithSummary() throws Exception {
        ObserveService.MetricsSummary summary = new ObserveService.MetricsSummary(
                5, 0.045, 3, List.of("trace-abc-123"), Instant.now()
        );
        when(observeService.getMetricsSummary(5)).thenReturn(summary);

        mockMvc.perform(get("/api/v1/observe/metrics").param("minutes", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lookbackMinutes").value(5))
                .andExpect(jsonPath("$.errorLogCount").value(3))
                .andExpect(jsonPath("$.p99LatencySeconds").value(0.045));
    }
}
