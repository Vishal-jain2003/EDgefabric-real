package com.edgefabric.loadbalancer.controller;

import com.edgefabric.loadbalancer.config.CacheProperties;
import com.edgefabric.loadbalancer.dto.WalAppendRequest;
import com.edgefabric.loadbalancer.exception.GlobalExceptionHandler;
import com.edgefabric.loadbalancer.wal.WalWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link WalAppendController}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Happy path — valid request returns 202 Accepted and calls walWriter.append once</li>
 *   <li>Missing required field — returns 400 Bad Request</li>
 *   <li>walWriter.append throws — still returns 202 (fire-and-forget, best-effort)</li>
 * </ul>
 */
@WebMvcTest(WalAppendController.class)
@Import(GlobalExceptionHandler.class)
class WalAppendControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WalWriter walWriter;

    // GlobalExceptionHandler requires CacheProperties in its constructor
    @MockitoBean
    private CacheProperties cacheProperties;

    // ── Helpers ────────────────────────────────────────────────────────────────

    private WalAppendRequest validRequest() {
        WalAppendRequest req = new WalAppendRequest();
        req.setKey("cache:wal-key");
        req.setDataBase64(Base64.getEncoder().encodeToString("payload".getBytes()));
        req.setExpiresAt(System.currentTimeMillis() + 60_000L);
        req.setContentType("application/json");
        req.setVersion(42L);
        req.setOriginatorNodeId("node-1");
        return req;
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    /**
     * A well-formed WalAppendRequest must return 202 Accepted and invoke
     * walWriter.append exactly once.
     */
    @Test
    void append_validRequest_returns202AndCallsWalWriterOnce() throws Exception {
        mockMvc.perform(post("/api/v1/internal/wal/append")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isAccepted());

        verify(walWriter, times(1)).append(any());
    }

    /**
     * A request body with the required {@code key} field missing must return 400.
     */
    @Test
    void append_missingKey_returns400() throws Exception {
        WalAppendRequest req = validRequest();
        req.setKey(null);   // null key — must fail bean validation

        mockMvc.perform(post("/api/v1/internal/wal/append")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        verify(walWriter, never()).append(any());
    }

    /**
     * A completely empty request body (no fields) must return 400.
     */
    @Test
    void append_emptyBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/internal/wal/append")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Even when walWriter.append throws (e.g., WAL unavailable), the endpoint
     * must still return 202 — the append is fire-and-forget / best-effort.
     */
    @Test
    void append_walWriterThrows_stillReturns202() throws Exception {
        doThrow(new RuntimeException("WAL write failed")).when(walWriter).append(any());

        mockMvc.perform(post("/api/v1/internal/wal/append")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isAccepted());
    }
}
