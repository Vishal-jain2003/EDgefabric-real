package com.edgefabric.caching.exception;

import com.edgefabric.caching.dto.ApiResponseDTO;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void testHandleNotFound() {
        CacheNotFoundException ex = new CacheNotFoundException("Cache not found");
        ResponseEntity<ApiResponseDTO> response = handler.handleNotFound(ex);

        assertEquals(404, response.getStatusCode().value());
        assertFalse(response.getBody().isSuccess());
        assertEquals("Cache not found", response.getBody().getMessage());
    }

    @Test
    void testHandleValidation() {
        ConstraintViolationException ex = new ConstraintViolationException("Invalid input", null);
        ResponseEntity<ApiResponseDTO> response = handler.handleValidation(ex);

        assertEquals(400, response.getStatusCode().value());
        assertFalse(response.getBody().isSuccess());
        // Matches the updated "Validation Error: " prefix
        assertEquals("Validation Error: Invalid input", response.getBody().getMessage());
    }

    @Test
    void testHandleIllegalArgs() {
        IllegalArgumentException ex = new IllegalArgumentException("Illegal argument passed");
        ResponseEntity<ApiResponseDTO> response = handler.handleIllegalArgs(ex);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Illegal argument passed", response.getBody().getMessage());
    }

    @Test
    void testHandleTypeMismatch() {
        MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
        org.mockito.Mockito.when(ex.getName()).thenReturn("ttl");
        ResponseEntity<ApiResponseDTO> response = handler.handleTypeMismatch(ex);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Invalid value for parameter: ttl", response.getBody().getMessage());
    }

    @Test
    void testHandleMissingVersion() {
        MissingVersionException ex = new MissingVersionException("my-key");
        ResponseEntity<ApiResponseDTO> response = handler.handleMissingVersion(ex);

        assertEquals(400, response.getStatusCode().value());
        assertFalse(response.getBody().isSuccess());
        assertTrue(response.getBody().getMessage().contains("my-key"));
    }

    @Test
    void testHandleCacheExpired() {
        CacheExpiredException ex = new CacheExpiredException("Entry has expired");
        ResponseEntity<ApiResponseDTO> response = handler.handleCacheExpired(ex);

        assertEquals(410, response.getStatusCode().value());
        assertFalse(response.getBody().isSuccess());
        assertEquals("Entry has expired", response.getBody().getMessage());
    }

    @Test
    void testHandleNoHandlerFound() {
        NoHandlerFoundException ex = mock(NoHandlerFoundException.class);
        org.mockito.Mockito.when(ex.getRequestURL()).thenReturn("/unknown/path");
        ResponseEntity<ApiResponseDTO> response = handler.handleNoHandlerFound(ex);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("Endpoint not found", response.getBody().getMessage());
    }

    @Test
    void testHandleGeneralException() {
        Exception ex = new RuntimeException("Something went wrong");
        ResponseEntity<ApiResponseDTO> response = handler.handleGeneralException(ex);

        assertEquals(500, response.getStatusCode().value());
        assertFalse(response.getBody().isSuccess());
        assertEquals("Unexpected internal server error", response.getBody().getMessage());
    }
}