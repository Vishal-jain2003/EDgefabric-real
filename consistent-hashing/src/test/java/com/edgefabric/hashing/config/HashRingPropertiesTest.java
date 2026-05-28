package com.edgefabric.hashing.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("HashRingProperties")
class HashRingPropertiesTest {

    // ── Default Constructor ───────────────────────────────────────────────────

    @Test
    @DisplayName("default constructor → virtualNodes=150, hashAlgorithm=xxhash")
    void defaultConstructorHasCorrectDefaults() {
        HashRingProperties props = new HashRingProperties();

        assertThat(props.getVirtualNodes()).isEqualTo(150);
        assertThat(props.getHashAlgorithm()).isEqualTo("xxhash");
    }

    // ── Parameterized Constructor ─────────────────────────────────────────────

    @Test
    @DisplayName("parameterized constructor → sets both values correctly")
    void parameterizedConstructorSetsValues() {
        HashRingProperties props = new HashRingProperties(200, "murmur");

        assertThat(props.getVirtualNodes()).isEqualTo(200);
        assertThat(props.getHashAlgorithm()).isEqualTo("murmur");
    }

    @Test
    @DisplayName("parameterized constructor → zero virtualNodes throws")
    void parameterizedConstructorZeroVirtualNodesThrows() {
        assertThatThrownBy(() -> new HashRingProperties(0, "xxhash"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0");
    }

    @Test
    @DisplayName("parameterized constructor → negative virtualNodes throws")
    void parameterizedConstructorNegativeVirtualNodesThrows() {
        assertThatThrownBy(() -> new HashRingProperties(-1, "xxhash"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── setVirtualNodes ───────────────────────────────────────────────────────
    // JaCoCo flagged this — it has a branch (if <= 0) that needs both paths tested

    @Test
    @DisplayName("setVirtualNodes → valid value is stored")
    void setVirtualNodesStoresValue() {
        HashRingProperties props = new HashRingProperties();
        props.setVirtualNodes(300);

        assertThat(props.getVirtualNodes()).isEqualTo(300);
    }

    @Test
    @DisplayName("setVirtualNodes → zero throws IllegalArgumentException")
    void setVirtualNodesZeroThrows() {
        HashRingProperties props = new HashRingProperties();

        assertThatThrownBy(() -> props.setVirtualNodes(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0");
    }

    @Test
    @DisplayName("setVirtualNodes → negative throws IllegalArgumentException")
    void setVirtualNodesNegativeThrows() {
        HashRingProperties props = new HashRingProperties();

        assertThatThrownBy(() -> props.setVirtualNodes(-100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── setHashAlgorithm ──────────────────────────────────────────────────────

    @Test
    @DisplayName("setHashAlgorithm → value is stored")
    void setHashAlgorithmStoresValue() {
        HashRingProperties props = new HashRingProperties();
        props.setHashAlgorithm("murmur");

        assertThat(props.getHashAlgorithm()).isEqualTo("murmur");
    }

    @Test
    @DisplayName("setHashAlgorithm → null is allowed (factory will default to xxhash)")
    void setHashAlgorithmAllowsNull() {
        HashRingProperties props = new HashRingProperties();
        props.setHashAlgorithm(null); // no exception

        assertThat(props.getHashAlgorithm()).isNull();
    }

    @Test
    @DisplayName("setHashAlgorithm → blank is allowed (factory will default to xxhash)")
    void setHashAlgorithmAllowsBlank() {
        HashRingProperties props = new HashRingProperties();
        props.setHashAlgorithm("   "); // no exception

        assertThat(props.getHashAlgorithm()).isEqualTo("   ");
    }

    // ── toString ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("toString → contains virtualNodes and hashAlgorithm values")
    void toStringContainsValues() {
        HashRingProperties props = new HashRingProperties(100, "murmur");

        assertThat(props.toString())
                .contains("100")
                .contains("murmur");
    }
}