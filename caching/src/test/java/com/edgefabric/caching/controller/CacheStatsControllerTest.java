package com.edgefabric.caching.controller;

import com.edgefabric.caching.dto.CacheStatsDTO;
import com.edgefabric.caching.model.HealthStatus;
import com.edgefabric.caching.service.CacheStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CacheStatsController.class)
class CacheStatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CacheStatsService cacheStatsService;

    @Test
    void getStats_Returns200WithJsonBody() throws Exception {
        CacheStatsDTO dto = new CacheStatsDTO(85.7, 600L, 100L, 1024L, 10_485_760L, HealthStatus.UP);
        when(cacheStatsService.getStats()).thenReturn(dto);

        mockMvc.perform(get("/api/v1/cache/stats"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    @Test
    void getStats_JsonContainsAllRequiredFields() throws Exception {
        CacheStatsDTO dto = new CacheStatsDTO(85.7, 600L, 100L, 1024L, 10_485_760L, HealthStatus.UP);
        when(cacheStatsService.getStats()).thenReturn(dto);

        mockMvc.perform(get("/api/v1/cache/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hitRate").value(85.7))
                .andExpect(jsonPath("$.totalHits").value(600))
                .andExpect(jsonPath("$.totalMisses").value(100))
                .andExpect(jsonPath("$.cacheSize").value(1024))
                .andExpect(jsonPath("$.memoryUsed").value(10_485_760))
                .andExpect(jsonPath("$.healthStatus").value("UP"));
    }
}
