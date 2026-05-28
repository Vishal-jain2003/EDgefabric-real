package com.edgefabric.loadbalancer.exception;

import com.edgefabric.loadbalancer.config.CacheProperties;
import com.edgefabric.loadbalancer.controller.CacheController;
import com.edgefabric.loadbalancer.service.CacheGatewayService;
import com.edgefabric.loadbalancer.validation.CacheEntryValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;


import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CacheController.class)
@Import(GlobalExceptionHandler.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CacheGatewayService gatewayService;

    @MockitoBean
    private CacheEntryValidator cacheEntryValidator;

    @MockitoBean
    private CacheProperties cacheProperties;

    private static final String TENANT = "testTenant";

    @Test
    void handleMaxSizeException_ShouldReturn413() throws Exception {

        doNothing().when(cacheEntryValidator)
                .validateData( any());

        doThrow(new MaxUploadSizeExceededException(2097152))
                .when(gatewayService) .put(anyString(), anyString(), any(byte[].class), anyLong(), anyString());

        mockMvc.perform(
                        put("/api/v1/cache/{key}", "abcd123456")
                                .header("X-Tenant", TENANT)
                                .content("data".getBytes())
                                .header("X-TTL-MS",60000L)
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                ).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("PAYLOAD_TOO_LARGE"));

    }

    @Test
    void handleIllegalArgumentException_ShouldReturn400() throws Exception {

        String key = "abcd123456";

        doThrow(new IllegalArgumentException("Cache value must not be null or empty."))
                .when(cacheEntryValidator)
                .validateData( any());

        mockMvc.perform(put("/api/v1/cache/{key}", key)
                        .header("X-Tenant", TENANT)
                        .content(new byte[0])
                        .header("X-TTL-MS",60000)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message")
                        .value("Cache value must not be null or empty."))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void handlePayloadTooLargeException_ShouldReturn413() throws Exception {
        doThrow(new CacheValidationException("Cache value exceeds the allowed maximum cache value.", 5000, 2000))
                .when(cacheEntryValidator).validateData(any());

        mockMvc.perform(
                        put("/api/v1/cache/{key}", "abcd123456")
                                .header("X-Tenant", TENANT)
                                .content("data".getBytes())
                                .header("X-TTL-MS",60000L)
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                ).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.details.actualValue").value(5000))
                .andExpect(jsonPath("$.details.maxAllowedValue").value(2000));

    }

    @Test
    void handleHttpClientNotFound_ShouldReturn404() throws Exception {
        when(gatewayService.get(TENANT, "missing-key"))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND,
                        "Not Found",
                        null,
                        null,
                        null
                ));

        mockMvc.perform(get("/api/v1/cache/{key}", "missing-key")
                        .header("X-Tenant", TENANT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Cache entry not found"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void handleRestClientException_ShouldReturn503() throws Exception {
        when(gatewayService.get(TENANT, "some-key"))
                .thenThrow(new ResourceAccessException("Connection refused"));

        mockMvc.perform(get("/api/v1/cache/{key}", "some-key")
                        .header("X-Tenant", TENANT))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Failed to communicate with cache node"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void handleRestClientException_OnPut_ShouldReturn503() throws Exception {
        doNothing().when(cacheEntryValidator).validateData(any());

        doThrow(new ResourceAccessException("Connection refused"))
                .when(gatewayService).put(anyString(), anyString(), any(byte[].class), anyLong(), anyString());

        mockMvc.perform(put("/api/v1/cache/{key}", "somekey001")
                        .header("X-Tenant", TENANT)
                        .content("data".getBytes())
                        .header("X-TTL-MS", 60000L)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Failed to communicate with cache node"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void handleQuorumNotMetException_ShouldReturn503() throws Exception {
        when(gatewayService.get(TENANT, "qkey"))
                .thenThrow(new QuorumNotMetException("READ", 2, 1));

        mockMvc.perform(get("/api/v1/cache/{key}", "qkey")
                        .header("X-Tenant", TENANT))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("QUORUM_NOT_MET"))
                .andExpect(jsonPath("$.details.operation").value("READ"))
                .andExpect(jsonPath("$.details.required").value(2))
                .andExpect(jsonPath("$.details.achieved").value(1));
    }

    @Test
    void handleCacheKeyNotFoundException_ShouldReturn404() throws Exception {
        when(gatewayService.get(TENANT, "ghost"))
                .thenThrow(new CacheKeyNotFoundException("ghost"));

        mockMvc.perform(get("/api/v1/cache/{key}", "ghost")
                        .header("X-Tenant", TENANT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Cache key not found: ghost"));
    }

    @Test
    void handleCacheKeyExpiredException_ShouldReturn410() throws Exception {
        when(gatewayService.get(TENANT, "old"))
                .thenThrow(new CacheKeyExpiredException("old", 1000L));

        mockMvc.perform(get("/api/v1/cache/{key}", "old")
                        .header("X-Tenant", TENANT))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("GONE"))
                .andExpect(jsonPath("$.message").value("Cache key has expired"))
                .andExpect(jsonPath("$.details.key").value("old"))
                .andExpect(jsonPath("$.details.expiredAt").value(1000));
    }

    @Test
    void handleGeneralException_ShouldReturn500() throws Exception {
        when(gatewayService.get(TENANT, "boom"))
                .thenThrow(new RuntimeException("unexpected"));

        mockMvc.perform(get("/api/v1/cache/{key}", "boom")
                        .header("X-Tenant", TENANT))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."));
    }

}