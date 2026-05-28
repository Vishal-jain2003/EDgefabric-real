package com.edgefabric.loadbalancer.validation;

import com.edgefabric.loadbalancer.config.CacheProperties;
import com.edgefabric.loadbalancer.exception.CacheValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CacheEntryValidatorTest {

    private CacheProperties cacheProperties;
    private CacheEntryValidator validator;

    @BeforeEach
    void setUp() {
        cacheProperties = mock(CacheProperties.class);
        when(cacheProperties.getMaxCacheEntrySizeBytes()).thenReturn(10L);
        validator = new CacheEntryValidator(cacheProperties);
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when data is null")
    void validateData_Null_ShouldThrowException() {

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateData(null));

        assertEquals("Cache value must not be null or empty.",
                ex.getMessage());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when data is empty")
    void validateData_Empty_ShouldThrowException() {

        byte[] emptyData = new byte[0];

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateData(emptyData));

        assertEquals("Cache value must not be null or empty.",
                ex.getMessage());
    }


    @Test
    @DisplayName("Should throw CacheValidationException when data exceeds max size")
    void validateData_ExceedsLimit_ShouldThrowCacheValidationException() {

        byte[] largeData = new byte[20]; // > 10

        CacheValidationException ex =
                assertThrows(CacheValidationException.class,
                        () -> validator.validateData(largeData));

        assertEquals("Cache value exceeds the allowed maximum cache value.",
                ex.getMessage());
        assertEquals(20, ex.getActualValue());
        assertEquals(10, ex.getMaxAllowedValue());
    }


    @Test
    @DisplayName("Should not throw exception when data is valid")
    void validateData_Valid_ShouldPass() {

        byte[] validData = new byte[5]; // < 10

        assertDoesNotThrow(() ->
                validator.validateData(validData));
    }
}