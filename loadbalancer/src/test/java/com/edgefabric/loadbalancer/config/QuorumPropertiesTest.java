package com.edgefabric.loadbalancer.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuorumPropertiesTest {

    @Test
    @DisplayName("validate() succeeds with valid R + W > N config")
    void validate_validConfig() {
        QuorumProperties props = new QuorumProperties();
        props.setReplicationFactor(3);
        props.setWrite(2);
        props.setRead(2);
        props.setTimeoutMs(2000);

        assertDoesNotThrow(props::validate);
    }

    @Test
    @DisplayName("validate() throws when replicationFactor <= 0")
    void validate_zeroReplicationFactor() {
        QuorumProperties props = new QuorumProperties();
        props.setReplicationFactor(0);
        props.setWrite(1);
        props.setRead(1);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, props::validate);
        assertTrue(ex.getMessage().contains("replication-factor"));
    }

    @Test
    @DisplayName("validate() throws when replicationFactor is negative")
    void validate_negativeReplicationFactor() {
        QuorumProperties props = new QuorumProperties();
        props.setReplicationFactor(-1);
        props.setWrite(1);
        props.setRead(1);

        assertThrows(IllegalArgumentException.class, props::validate);
    }

    @Test
    @DisplayName("validate() throws when write <= 0")
    void validate_zeroWrite() {
        QuorumProperties props = new QuorumProperties();
        props.setReplicationFactor(3);
        props.setWrite(0);
        props.setRead(2);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, props::validate);
        assertTrue(ex.getMessage().contains("write"));
    }

    @Test
    @DisplayName("validate() throws when write > replicationFactor")
    void validate_writeTooLarge() {
        QuorumProperties props = new QuorumProperties();
        props.setReplicationFactor(3);
        props.setWrite(4);
        props.setRead(2);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, props::validate);
        assertTrue(ex.getMessage().contains("write"));
    }

    @Test
    @DisplayName("validate() throws when read <= 0")
    void validate_zeroRead() {
        QuorumProperties props = new QuorumProperties();
        props.setReplicationFactor(3);
        props.setWrite(2);
        props.setRead(0);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, props::validate);
        assertTrue(ex.getMessage().contains("read"));
    }

    @Test
    @DisplayName("validate() throws when read > replicationFactor")
    void validate_readTooLarge() {
        QuorumProperties props = new QuorumProperties();
        props.setReplicationFactor(3);
        props.setWrite(2);
        props.setRead(4);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, props::validate);
        assertTrue(ex.getMessage().contains("read"));
    }

    @Test
    @DisplayName("validate() throws when R + W <= N (quorum invariant violated)")
    void validate_quorumInvariantViolated() {
        QuorumProperties props = new QuorumProperties();
        props.setReplicationFactor(3);
        props.setWrite(1);
        props.setRead(1);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, props::validate);
        assertTrue(ex.getMessage().contains("Quorum invariant violated"));
    }

    @Test
    @DisplayName("validate() passes when R + W = N + 1 (minimum strong consistency)")
    void validate_minimumStrongConsistency() {
        QuorumProperties props = new QuorumProperties();
        props.setReplicationFactor(3);
        props.setWrite(2);
        props.setRead(2);

        assertDoesNotThrow(props::validate);
    }

    @Test
    @DisplayName("getters return correct values after setters")
    void gettersReturnSetValues() {
        QuorumProperties props = new QuorumProperties();
        props.setReplicationFactor(5);
        props.setWrite(3);
        props.setRead(3);
        props.setTimeoutMs(1000);

        assertEquals(5, props.getReplicationFactor());
        assertEquals(3, props.getWrite());
        assertEquals(3, props.getRead());
        assertEquals(1000, props.getTimeoutMs());
    }

    // ── cluster-size validation ──────────────────────────────────────────

    @Test
    @DisplayName("validate() throws when replicationFactor exceeds minClusterSize")
    void validate_replicationFactorExceedsMinClusterSize() {
        QuorumProperties props = new QuorumProperties();
        props.setReplicationFactor(5);
        props.setWrite(3);
        props.setRead(3);
        props.setMinClusterSize(3);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, props::validate);
        assertTrue(ex.getMessage().contains("min-cluster-size"));
        assertTrue(ex.getMessage().contains("replication-factor"));
    }

    @Test
    @DisplayName("validate() passes when replicationFactor equals minClusterSize")
    void validate_replicationFactorEqualsMinClusterSize() {
        QuorumProperties props = new QuorumProperties();
        props.setReplicationFactor(3);
        props.setWrite(2);
        props.setRead(2);
        props.setMinClusterSize(3);

        assertDoesNotThrow(props::validate);
    }

    @Test
    @DisplayName("validate() skips cluster-size check when minClusterSize is 0 (default)")
    void validate_minClusterSizeZeroSkipsCheck() {
        QuorumProperties props = new QuorumProperties();
        props.setReplicationFactor(10);
        props.setWrite(6);
        props.setRead(6);
        props.setMinClusterSize(0);

        assertDoesNotThrow(props::validate);
    }

    @Test
    @DisplayName("validate() passes when replicationFactor is less than minClusterSize")
    void validate_replicationFactorLessThanMinClusterSize() {
        QuorumProperties props = new QuorumProperties();
        props.setReplicationFactor(3);
        props.setWrite(2);
        props.setRead(2);
        props.setMinClusterSize(5);

        assertDoesNotThrow(props::validate);
    }

    // ── timeout validation ───────────────────────────────────────────────

    @Test
    @DisplayName("validate() throws when timeoutMs is zero")
    void validate_zeroTimeout() {
        QuorumProperties props = new QuorumProperties();
        props.setReplicationFactor(3);
        props.setWrite(2);
        props.setRead(2);
        props.setTimeoutMs(0);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, props::validate);
        assertTrue(ex.getMessage().contains("timeout-ms"));
    }

    @Test
    @DisplayName("validate() throws when timeoutMs is negative")
    void validate_negativeTimeout() {
        QuorumProperties props = new QuorumProperties();
        props.setReplicationFactor(3);
        props.setWrite(2);
        props.setRead(2);
        props.setTimeoutMs(-500);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, props::validate);
        assertTrue(ex.getMessage().contains("timeout-ms"));
    }
}

