package com.edgefabric.loadbalancer.controller;

import com.edgefabric.loadbalancer.config.CacheProperties;
import com.edgefabric.loadbalancer.dto.response.CacheResponse;
import com.edgefabric.loadbalancer.exception.CacheKeyNotFoundException;
import com.edgefabric.loadbalancer.exception.CacheValidationException;
import com.edgefabric.loadbalancer.exception.GlobalExceptionHandler;
import com.edgefabric.loadbalancer.service.CacheGatewayService;
import com.edgefabric.loadbalancer.validation.CacheEntryValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.test.web.servlet.result.ContentResultMatchers;

@WebMvcTest(CacheController.class)
@Import(GlobalExceptionHandler.class)
class CacheControllerTest {

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
    @DisplayName("Should return 201 Created when valid file is uploaded")
    void putCache_Success() throws Exception {
        doNothing().when(cacheEntryValidator).validateData(any());

        byte[] fileContent = "ValidData".getBytes();

        mockMvc.perform(put("/api/v1/cache/abcd123456")
                        .header("X-TTL-MS", 120000)
                        .header("X-Tenant", TENANT)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(fileContent))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Cache entry stored successfully"))
                .andExpect(jsonPath("$.key").value("abcd123456"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.expiresAt").exists());

        verify(cacheEntryValidator).validateData(any());
        verify(gatewayService).put(
                eq(TENANT),
                eq("abcd123456"),
                any(byte[].class),
                longThat(expiresAt -> expiresAt >= System.currentTimeMillis() + 119000L),
                startsWith(MediaType.APPLICATION_OCTET_STREAM_VALUE)
        );
    }

    @Test
    @DisplayName("Should use default TTL if header is missing")
    void putCache_DefaultTTL() throws Exception {
        doNothing().when(cacheEntryValidator).validateData(any());

        mockMvc.perform(put("/api/v1/cache/abcd123456")
                        .header("X-Tenant", TENANT)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("data".getBytes()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").exists());

        verify(gatewayService).put(
                eq(TENANT),
                eq("abcd123456"),
                any(byte[].class),
                longThat(expiresAt -> expiresAt >= System.currentTimeMillis() + 59000L),
                startsWith("text/plain")
        );
    }

    @Test
    void putCache_DefaultContentType() throws Exception {
        doNothing().when(cacheEntryValidator).validateData(any());

        mockMvc.perform(put("/api/v1/cache/abcd123456")
                        .header("X-Tenant", TENANT)
                        .header("X-TTL-MS", 1000)
                        .content("data".getBytes()))
                .andExpect(status().isCreated());

        verify(gatewayService).put(
                eq(TENANT),
                eq("abcd123456"),
                any(),
                longThat(expiresAt -> expiresAt >= System.currentTimeMillis() + 500L),
                eq("application/octet-stream")
        );
    }

    @Test
    @DisplayName("Should return 400 when IllegalArgumentException is thrown")
    void shouldReturnBadRequest_WhenIllegalArgumentException() throws Exception {
        String key = "abcd123456";
        byte[] dataBytes = "data".getBytes();

        doThrow(new IllegalArgumentException("Cache value must not be null or empty."))
                .when(cacheEntryValidator)
                .validateData(any(byte[].class));

        mockMvc.perform(put("/api/v1/cache/{key}", key)
                        .header("X-Tenant", TENANT)
                        .content(dataBytes)
                        .header("X-TTL-MS", 60000)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("Cache value must not be null or empty."))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("Should return 413 when CacheValidationException is thrown")
    void shouldReturnPayloadTooLarge_WhenCacheValidationException() throws Exception {
        doThrow(new CacheValidationException("Cache value exceeds the allowed maximum cache value.", 5000, 2000))
                .when(cacheEntryValidator).validateData(any());

        mockMvc.perform(put("/api/v1/cache/{key}", "abcd123456")
                        .header("X-Tenant", TENANT)
                        .content("data".getBytes())
                        .header("X-TTL-MS", 60000L)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.details.actualValue").value(5000))
                .andExpect(jsonPath("$.details.maxAllowedValue").value(2000));
    }

    @Test
    @DisplayName("Should return 400 when Invalid key is given")
    void shouldReturnBadRequest_WhenInvalidKeyIsGiven() throws Exception {
        mockMvc.perform(put("/api/v1/cache/{key}", "invalid$key")
                        .header("X-Tenant", TENANT)
                        .content("data")
                        .header("X-TTL-MS", 60000)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  AC4: key must contain only alphanumeric, colon, hyphen, underscore (1–250 chars)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC4: PUT with exactly 10 alphanumeric chars should return 201")
    void putCache_exactlyTenAlphanumericChars_returns201() throws Exception {
        doNothing().when(cacheEntryValidator).validateData(any());

        mockMvc.perform(put("/api/v1/cache/{key}", "abcd123456")
                        .header("X-Tenant", TENANT)
                        .header("X-TTL-MS", 60000)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("data".getBytes()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("AC4: PUT with 9 alphanumeric chars should return 201 (no minimum length)")
    void putCache_nineAlphanumericChars_returns201() throws Exception {
        doNothing().when(cacheEntryValidator).validateData(any());

        mockMvc.perform(put("/api/v1/cache/{key}", "abcd12345")
                        .header("X-Tenant", TENANT)
                        .header("X-TTL-MS", 60000)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("data".getBytes()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("AC4: PUT with 11 alphanumeric chars should return 201 (max 250 chars)")
    void putCache_elevenAlphanumericChars_returns201() throws Exception {
        doNothing().when(cacheEntryValidator).validateData(any());

        mockMvc.perform(put("/api/v1/cache/{key}", "abcd1234567")
                        .header("X-Tenant", TENANT)
                        .header("X-TTL-MS", 60000)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("data".getBytes()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("AC4: PUT with symbols in key should return 400")
    void putCache_symbolsInKey_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/cache/{key}", "abcd12345!")
                        .header("X-Tenant", TENANT)
                        .header("X-TTL-MS", 60000)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("data".getBytes()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("AC4: PUT with colon/hyphen/underscore in key should return 201 (all allowed)")
    void putCache_colonHyphenUnderscoreInKey_returns201() throws Exception {
        doNothing().when(cacheEntryValidator).validateData(any());

        mockMvc.perform(put("/api/v1/cache/{key}", "user:sess-1_v2")
                        .header("X-Tenant", TENANT)
                        .header("X-TTL-MS", 60000)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("data".getBytes()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("AC4: PUT with key exceeding 250 chars should return 400")
    void putCache_keyExceeding250Chars_returns400() throws Exception {
        String longKey = "a".repeat(251);

        mockMvc.perform(put("/api/v1/cache/{key}", longKey)
                        .header("X-Tenant", TENANT)
                        .header("X-TTL-MS", 60000)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("data".getBytes()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("Should return 400 when Negative TTL is given")
    void shouldReturnBadRequest_WhenNegativeTtlIsGiven() throws Exception {
        mockMvc.perform(put("/api/v1/cache/{key}", "abcd123456")
                        .header("X-Tenant", TENANT)
                        .content("data")
                        .header("X-TTL-MS", -1)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("Should return 400 when type mismatch happens")
    void shouldReturnTypeMismatch_WhenTypeDiffers() throws Exception {
        mockMvc.perform(put("/api/v1/cache/{key}", "abcd123456")
                        .header("X-Tenant", TENANT)
                        .content("data")
                        .header("X-TTL-MS", "abc")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("TYPE_MISMATCH"));
    }

    @Test
    @DisplayName("Should Return 415 when unsupported media type has been given")
    void shouldReturn_UnsupportedMediaType() throws Exception {
        mockMvc.perform(put("/api/v1/cache/{key}", "abcd123456")
                        .header("X-Tenant", TENANT)
                        .content("data")
                        .header("X-TTL-MS", 60000)
                        .contentType("abc"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("PUT - MaxUploadSizeExceededException should return 413")
    void putCache_MaxUploadSizeExceeded() throws Exception {
        doNothing().when(cacheEntryValidator).validateData(any());
        doThrow(new MaxUploadSizeExceededException(2000))
                .when(gatewayService)
                .put(anyString(), anyString(), any(), anyLong(), anyString());

        mockMvc.perform(put("/api/v1/cache/{key}", "abcd123456")
                        .header("X-Tenant", TENANT)
                        .content("data")
                        .header("X-TTL-MS", 60000)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    @DisplayName("PUT - Generic exception should return 500")
    void putCache_InternalError() throws Exception {
        doNothing().when(cacheEntryValidator).validateData(any());
        doThrow(new RuntimeException("Boom"))
                .when(gatewayService)
                .put(anyString(), anyString(), any(), anyLong(), anyString());

        mockMvc.perform(put("/api/v1/cache/{key}", "abcd123456")
                        .header("X-Tenant", TENANT)
                        .content("data")
                        .header("X-TTL-MS", 60000)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"));
    }

    @Autowired
    private CacheController cacheController;

    @Test
    void getCache_shouldReturn200WithData_whenKeyExists() throws Exception {
        byte[] data = "Rayyan".getBytes();
        long expiresAt = System.currentTimeMillis() + 5000L;

        when(gatewayService.get(TENANT, "my-key"))
                .thenReturn(CacheResponse.builder()
                        .data(data)
                        .contentType("text/plain")
                        .expiresAt(expiresAt)
                        .build());

        mockMvc.perform(get("/api/v1/cache/my-key")
                        .header("X-Tenant", TENANT))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Expires-At", String.valueOf(expiresAt)))
                .andExpect(content().contentType("text/plain"))
                .andExpect(content().bytes(data));
    }

    @Test
    void getCache_shouldReturn404_whenKeyDoesNotExist() throws Exception {
        when(gatewayService.get(TENANT, "missing-key"))
                .thenThrow(new CacheKeyNotFoundException("missing-key"));

        mockMvc.perform(get("/api/v1/cache/missing-key")
                        .header("X-Tenant", TENANT))
                .andExpect(status().isNotFound());
    }
}

