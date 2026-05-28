package com.edgefabric.loadbalancer.controller;

import com.edgefabric.loadbalancer.config.CacheProperties;
import com.edgefabric.loadbalancer.dto.export.DashboardExportResponse;
import com.edgefabric.loadbalancer.dto.export.NodeGossipSnapshot;
import com.edgefabric.loadbalancer.dto.export.RingSnapshot;
import com.edgefabric.loadbalancer.exception.GlobalExceptionHandler;
import com.edgefabric.loadbalancer.service.DashboardCsvFormatter;
import com.edgefabric.loadbalancer.service.DashboardExportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(GlobalExceptionHandler.class)
@WebMvcTest(controllers = DashboardExportController.class)
class DashboardExportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardExportService exportService;

    @MockitoBean
    private DashboardCsvFormatter csvFormatter;

    @MockitoBean
    private CacheProperties cacheProperties;

    private DashboardExportResponse sampleResponse() {
        return DashboardExportResponse.builder()
                .exportTimestamp("2026-04-20T10:00:00Z")
                .loadBalancerStatus("UP")
                .loadBalancerStatusCode(200)
                .ring(new RingSnapshot(3, 450, 150, "murmur", List.of("node-1", "node-2", "node-3")))
                .nodes(List.of(
                        new NodeGossipSnapshot("node-1", "10.0.0.1", 8082, true,
                                "2026-04-20T10:00:00Z", 3, 3, 0, 0, List.of())
                ))
                .build();
    }

    @Test
    void export_defaultFormat_returnsJson() throws Exception {
        when(exportService.buildSnapshot()).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/dashboard/export"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("Content-Disposition",
                        matchesPattern("attachment; filename=dashboard-export_\\d{8}T\\d{6}Z\\.json")))
                .andExpect(jsonPath("$.exportTimestamp").value("2026-04-20T10:00:00Z"))
                .andExpect(jsonPath("$.loadBalancerStatus").value("UP"))
                .andExpect(jsonPath("$.loadBalancerStatusCode").value(200))
                .andExpect(jsonPath("$.ring.nodeCount").value(3))
                .andExpect(jsonPath("$.nodes").isArray());
    }

    @Test
    void export_jsonFormat_returnsJson() throws Exception {
        when(exportService.buildSnapshot()).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/dashboard/export").param("format", "json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("Content-Disposition",
                        matchesPattern("attachment; filename=dashboard-export_\\d{8}T\\d{6}Z\\.json")));
    }

    @Test
    void export_csvFormat_returnsCsv() throws Exception {
        when(exportService.buildSnapshot()).thenReturn(sampleResponse());
        when(csvFormatter.format(any())).thenReturn("header\nrow1\n");

        mockMvc.perform(get("/api/v1/dashboard/export").param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition",
                        matchesPattern("attachment; filename=dashboard-export_\\d{8}T\\d{6}Z\\.csv")))
                .andExpect(content().string("header\nrow1\n"));
    }

    @Test
    void export_invalidFormat_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/export").param("format", "xml"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }
}
