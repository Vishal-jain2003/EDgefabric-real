package com.edgefabric.loadbalancer.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionClassesTest {


    @Test
    void cacheKeyExpired_shouldStoreKeyAndExpiresAt() {
        CacheKeyExpiredException ex = new CacheKeyExpiredException("myKey", 1700000000L);

        assertEquals("myKey", ex.getKey());
        assertEquals(1700000000L, ex.getExpiresAt());
        assertTrue(ex.getMessage().contains("myKey"));
        assertTrue(ex.getMessage().contains("1700000000"));
    }



    @Test
    void cacheKeyNotFound_shouldStoreKeyAndFormatMessage() {
        CacheKeyNotFoundException ex = new CacheKeyNotFoundException("absent-key");

        assertEquals("absent-key", ex.getKey());
        assertEquals("Cache key not found: absent-key", ex.getMessage());
    }



    @Test
    void cacheValidation_shouldExposeActualAndMax() {
        CacheValidationException ex =
                new CacheValidationException("too big", 5000, 2000);

        assertEquals("too big", ex.getMessage());
        assertEquals(5000, ex.getActualValue());
        assertEquals(2000, ex.getMaxAllowedValue());
    }



    @Test
    void clusterComm_messageOnlyConstructor() {
        ClusterCommunicationException ex =
                new ClusterCommunicationException("timeout");

        assertEquals("timeout", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void clusterComm_messageAndCauseConstructor() {
        RuntimeException cause = new RuntimeException("root");
        ClusterCommunicationException ex =
                new ClusterCommunicationException("failed", cause);

        assertEquals("failed", ex.getMessage());
        assertSame(cause, ex.getCause());
    }



    @Test
    void quorumNotMet_shouldExposeAllFields() {
        QuorumNotMetException ex = new QuorumNotMetException("WRITE", 3, 1);

        assertEquals("WRITE", ex.getOperation());
        assertEquals(3, ex.getRequired());
        assertEquals(1, ex.getAchieved());
        assertTrue(ex.getMessage().contains("WRITE"));
        assertTrue(ex.getMessage().contains("3"));
        assertTrue(ex.getMessage().contains("1"));
    }



    @Test
    void clusterBootstrap_messageOnlyConstructor() {
        ClusterBootstrapException ex =
                new ClusterBootstrapException("no nodes found");

        assertEquals("no nodes found", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void clusterBootstrap_messageAndCauseConstructor() {
        RuntimeException cause = new RuntimeException("DNS unavailable");
        ClusterBootstrapException ex =
                new ClusterBootstrapException("bootstrap failed", cause);

        assertEquals("bootstrap failed", ex.getMessage());
        assertSame(cause, ex.getCause());
    }



    @Test
    void errorCode_allValuesExist() {
        ErrorCode[] codes = ErrorCode.values();
        assertEquals(11, codes.length);
        assertNotNull(ErrorCode.valueOf("QUORUM_NOT_MET"));
        assertNotNull(ErrorCode.valueOf("NOT_FOUND"));
        assertNotNull(ErrorCode.valueOf("GONE"));
        assertNotNull(ErrorCode.valueOf("INTERNAL_ERROR"));
        assertNotNull(ErrorCode.valueOf("CLUSTER_BOOTSTRAP_FAILED"));
        assertNotNull(ErrorCode.valueOf("CLUSTER_COMMUNICATION_FAILED"));
    }
}

