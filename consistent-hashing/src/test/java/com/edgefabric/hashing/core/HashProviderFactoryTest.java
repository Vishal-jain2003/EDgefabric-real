package com.edgefabric.hashing.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("HashProviderFactory")
class HashProviderFactoryTest {

    @Test
    @DisplayName("'xxhash' → XXHashProvider")
    void xxhashReturnsXXHashProvider() {
        assertThat(HashProviderFactory.create("xxhash"))
                .isInstanceOf(XXHashProvider.class);
    }

    @Test
    @DisplayName("'murmur' → MurmurHashProvider")
    void murmurReturnsMurmurHashProvider() {
        assertThat(HashProviderFactory.create("murmur"))
                .isInstanceOf(MurmurHashProvider.class);
    }

    @ParameterizedTest
    @DisplayName("case-insensitive: XXHASH, XxHash, xxhash all work")
    @ValueSource(strings = {"XXHASH", "XxHash", "xxhash", "XXHASH "})
    void xxhashIsCaseInsensitive(String algorithm) {
        /*
         * @ParameterizedTest runs this test once for EACH value in @ValueSource.
         * No copy-paste of test methods. Same assertion, different inputs.
         * Result in test report: "case-insensitive: XXHASH" (4 sub-tests)
         */
        assertThat(HashProviderFactory.create(algorithm))
                .isInstanceOf(XXHashProvider.class);
    }

    @ParameterizedTest
    @DisplayName("case-insensitive: MURMUR, Murmur, murmur all work")
    @ValueSource(strings = {"MURMUR", "Murmur", "murmur", " murmur"})
    void murmurIsCaseInsensitive(String algorithm) {
        assertThat(HashProviderFactory.create(algorithm))
                .isInstanceOf(MurmurHashProvider.class);
    }

    @Test
    @DisplayName("null → defaults to XXHash (no exception)")
    void nullDefaultsToXXHash() {
        assertThat(HashProviderFactory.create(null))
                .isInstanceOf(XXHashProvider.class);
    }

    @Test
    @DisplayName("blank → defaults to XXHash (no exception)")
    void blankDefaultsToXXHash() {
        assertThat(HashProviderFactory.create("   "))
                .isInstanceOf(XXHashProvider.class);
    }

    @Test
    @DisplayName("unknown algorithm → IllegalArgumentException with algorithm name in message")
    void unknownAlgorithmThrowsWithHelpfulMessage() {
        assertThatThrownBy(() -> HashProviderFactory.create("sha256"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sha256");   // message tells you WHAT was wrong
    }

    @Test
    @DisplayName("same algorithm → returns identical cached instance (not new object)")
    void returnsCachedInstance() {
        /*
         * isSameAs() checks reference equality (== in Java).
         * This is different from isEqualTo() which checks .equals().
         *
         * We want to verify the EXACT SAME OBJECT is returned,
         * not just an equal-looking one.
         * If factory creates new objects: isSameAs fails → bug caught.
         */
        HashProvider first  = HashProviderFactory.create("xxhash");
        HashProvider second = HashProviderFactory.create("xxhash");

        assertThat(first).isSameAs(second);
    }

    @Test
    @DisplayName("cached instance still produces correct hashes")
    void cachedInstanceProducesCorrectHashes() {
        /*
         * Caching is useless if the cached instance is broken.
         * Verify that the cached instance actually works correctly.
         */
        HashProvider provider = HashProviderFactory.create("xxhash");

        long hash1 = provider.generateHash("test-key");
        long hash2 = provider.generateHash("test-key");

        assertThat(hash1).isEqualTo(hash2);  // still deterministic
    }
}