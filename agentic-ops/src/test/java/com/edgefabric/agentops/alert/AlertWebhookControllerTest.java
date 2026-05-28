package com.edgefabric.agentops.alert;

import com.edgefabric.agentops.chat.ChatResponse;
import com.edgefabric.agentops.chat.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AlertWebhookController.class)
class AlertWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AlertStore alertStore;

    @MockBean
    private ChatService chatService;

    @Test
    void webhook_validPayload_storesAlertsAndReturns200() throws Exception {
        Map<String, Object> payload = Map.of(
                "alerts", List.of(
                        Map.of(
                                "fingerprint", "fp1",
                                "status", "firing",
                                "labels", Map.of("alertname", "HighMemory", "severity", "warning"),
                                "annotations", Map.of("summary", "Memory high"),
                                "startsAt", Instant.now().toString()
                        )
                )
        );

        mockMvc.perform(post("/api/v1/alerts/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        verify(alertStore, times(1)).add(any());
    }

    @Test
    void webhook_criticalFiringAlert_triggersAsyncAnalysis() throws Exception {
        when(chatService.chat(any())).thenReturn(new ChatResponse("analysis", null));

        Map<String, Object> payload = Map.of(
                "alerts", List.of(
                        Map.of(
                                "fingerprint", "fp-crit",
                                "status", "firing",
                                "labels", Map.of("alertname", "CacheNodeDown", "severity", "critical"),
                                "annotations", Map.of("summary", "Node down"),
                                "startsAt", Instant.now().toString()
                        )
                )
        );

        mockMvc.perform(post("/api/v1/alerts/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        verify(alertStore).add(any());
    }

    @Test
    void webhook_noAlertsArray_returns400() throws Exception {
        Map<String, Object> payload = Map.of("version", "4");

        mockMvc.perform(post("/api/v1/alerts/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recentAlerts_returnsStoreContents() throws Exception {
        AlertStore.AlertEntry entry = new AlertStore.AlertEntry(
                "fp1", "TestAlert", "warning", "firing",
                "localhost:8082", "edgefabric", "summary", "desc",
                null, Instant.now(), Instant.now());
        when(alertStore.getRecent(50)).thenReturn(List.of(entry));

        mockMvc.perform(get("/api/v1/alerts/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].alertName").value("TestAlert"))
                .andExpect(jsonPath("$[0].severity").value("warning"));
    }

    @Test
    void recentAlerts_customLimit_passesLimitToStore() throws Exception {
        when(alertStore.getRecent(10)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/alerts/recent").param("limit", "10"))
                .andExpect(status().isOk());

        verify(alertStore).getRecent(10);
    }

    @Test
    void webhook_multipleAlerts_storesAll() throws Exception {
        Map<String, Object> payload = Map.of(
                "alerts", List.of(
                        Map.of("fingerprint", "fp1", "status", "firing",
                                "labels", Map.of("alertname", "A1", "severity", "info"),
                                "annotations", Map.of()),
                        Map.of("fingerprint", "fp2", "status", "resolved",
                                "labels", Map.of("alertname", "A2", "severity", "warning"),
                                "annotations", Map.of())
                )
        );

        mockMvc.perform(post("/api/v1/alerts/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        verify(alertStore, times(2)).add(any());
    }
}
