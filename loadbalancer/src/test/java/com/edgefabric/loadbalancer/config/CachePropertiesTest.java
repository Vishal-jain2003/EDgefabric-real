package com.edgefabric.loadbalancer.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CachePropertiesTest {

    @Test
    void settersAndGetters() {
        CacheProperties props = new CacheProperties();
        props.setMaxCacheEntrySizeBytes(2097152L);
        props.setPattern("^[a-zA-Z0-9:_-]+$");

        assertEquals(2097152L, props.getMaxCacheEntrySizeBytes());
        assertEquals("^[a-zA-Z0-9:_-]+$", props.getPattern());
    }

    @Test
    void defaultValuesAreZeroOrNull() {
        CacheProperties props = new CacheProperties();
        assertEquals(0L, props.getMaxCacheEntrySizeBytes());
        assertNull(props.getPattern());
    }
}

