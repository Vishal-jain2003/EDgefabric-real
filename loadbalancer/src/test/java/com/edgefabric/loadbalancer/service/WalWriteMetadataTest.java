package com.edgefabric.loadbalancer.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WalWriteMetadataTest {

    @Test
    void hasFailures_returnsTrueWhenFailedNodesPresent() {
        var metadata = new WalWriteMetadata(1L, Set.of("node1"), Set.of("node2"));
        assertThat(metadata.hasFailures()).isTrue();
    }

    @Test
    void hasFailures_returnsFalseWhenNoFailedNodes() {
        var metadata = new WalWriteMetadata(1L, Set.of("node1", "node2"), Set.of());
        assertThat(metadata.hasFailures()).isFalse();
    }

    @Test
    void hasFailures_returnsFalseWhenFailedNodesNull() {
        var metadata = new WalWriteMetadata(1L, Set.of("node1"), null);
        assertThat(metadata.hasFailures()).isFalse();
    }

    @Test
    void constructor_storesAllFields() {
        var successful = Set.of("node1", "node2");
        var failed = Set.of("node3");
        var metadata = new WalWriteMetadata(42L, successful, failed);

        assertThat(metadata.version()).isEqualTo(42L);
        assertThat(metadata.successfulNodes()).isEqualTo(successful);
        assertThat(metadata.failedNodes()).isEqualTo(failed);
    }

    @Test
    void hasFailures_returnsFalseWhenFailedNodesEmpty() {
        var metadata = new WalWriteMetadata(1L, Set.of("node1"), Set.of());
        assertThat(metadata.hasFailures()).isFalse();
    }
}
