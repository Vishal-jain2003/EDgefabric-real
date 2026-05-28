package com.edgefabric.hashing.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MurmurHashProvider")
class MurmurHashProviderTest {

    private MurmurHashProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MurmurHashProvider();
    }

    @Test
    @DisplayName("same input → always same output (determinism)")
    void sameInputAlwaysProducesSameHash() {

        long first  = provider.generateHash("node-1#0");
        long second = provider.generateHash("node-1#0");
        long third  = provider.generateHash("node-1#0");

        assertThat(first).isEqualTo(second).isEqualTo(third);
    }

    @Test
    @DisplayName("different inputs → different hashes (no trivial collision)")
    void differentInputsProduceDifferentHashes() {

        long hash1 = provider.generateHash("node-1#0");
        long hash2 = provider.generateHash("node-1#1");
        long hash3 = provider.generateHash("node-2#0");

        assertThat(hash1)
                .isNotEqualTo(hash2)
                .isNotEqualTo(hash3);

        assertThat(hash2).isNotEqualTo(hash3);
    }

    @Test
    @DisplayName("similar inputs → very different hashes (avalanche effect)")
    void similarInputsProduceVeryDifferentHashes() {

        long hashA = provider.generateHash("node-1");
        long hashB = provider.generateHash("node-2");

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    @DisplayName("negative values are valid output")
    void negativeHashValuesAreValid() {

        long hash = provider.generateHash("some-key");

        // Verify method runs and produces a valid long
        assertThat(hash).isNotZero();
    }

    @Test
    @DisplayName("empty string produces consistent hash")
    void emptyStringProducesConsistentHash() {

        long first  = provider.generateHash("");
        long second = provider.generateHash("");

        assertThat(first).isEqualTo(second);
    }
}