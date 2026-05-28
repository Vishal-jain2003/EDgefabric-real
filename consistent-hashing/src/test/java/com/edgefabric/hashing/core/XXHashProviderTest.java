package com.edgefabric.hashing.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("XXHashProvider")
class XXHashProviderTest {

    private XXHashProvider provider;

    @BeforeEach
    void setUp() {
        provider = new XXHashProvider();
    }

    @Test
    @DisplayName("same input → always same output (determinism)")
    void sameInputAlwaysProducesSameHash() {
        long first  = provider.generateHash("node-1#0");
        long second = provider.generateHash("node-1#0");

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("different inputs → different hashes")
    void differentInputsProduceDifferentHashes() {

        long hash1 = provider.generateHash("node-1#0");
        long hash2 = provider.generateHash("node-1#1");
        long hash3 = provider.generateHash("node-2#0");

        assertThat(hash1)
                .isNotEqualTo(hash2)
                .isNotEqualTo(hash3);
    }

    @Test
    @DisplayName("multiple instances produce same hash (static INSTANCE is correct)")
    void multipleInstancesProduceSameHash() {

        XXHashProvider provider1 = new XXHashProvider();
        XXHashProvider provider2 = new XXHashProvider();

        String key = "test-key-12345";

        assertThat(provider1.generateHash(key))
                .isEqualTo(provider2.generateHash(key));
    }

    @Test
    @DisplayName("XXHash and MurmurHash produce DIFFERENT hashes for same input")
    void xxhashAndMurmurProduceDifferentHashes() {

        MurmurHashProvider murmur = new MurmurHashProvider();

        String key = "node-1#0";

        assertThat(provider.generateHash(key))
                .isNotEqualTo(murmur.generateHash(key));
    }
}