package com.edgefabric.loadbalancer.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for LogSanitizer - preventing log injection attacks.
 * Tests written FIRST following TDD methodology.
 */
@ExtendWith(MockitoExtension.class)
class LogSanitizerTest {

    @Test
    void sanitize_shouldReturnSameString_whenNoControlCharacters() {
        // Given
        String cleanInput = "valid-cache-key-123";

        // When
        String result = LogSanitizer.sanitize(cleanInput);

        // Then
        assertThat(result).isEqualTo(cleanInput);
    }

    @Test
    void sanitize_shouldStripNewline_whenInputContainsLineFeed() {
        // Given
        String maliciousInput = "key1\nfake-log-entry";

        // When
        String result = LogSanitizer.sanitize(maliciousInput);

        // Then
        assertThat(result).isEqualTo("key1_fake-log-entry");
        assertThat(result).doesNotContain("\n");
    }

    @Test
    void sanitize_shouldStripCarriageReturn_whenInputContainsCR() {
        // Given
        String maliciousInput = "key1\rfake-log-entry";

        // When
        String result = LogSanitizer.sanitize(maliciousInput);

        // Then
        assertThat(result).isEqualTo("key1_fake-log-entry");
        assertThat(result).doesNotContain("\r");
    }

    @Test
    void sanitize_shouldStripCRLF_whenInputContainsWindowsLineEnding() {
        // Given
        String maliciousInput = "key1\r\nfake-log-entry";

        // When
        String result = LogSanitizer.sanitize(maliciousInput);

        // Then
        assertThat(result).isEqualTo("key1__fake-log-entry");
        assertThat(result).doesNotContain("\r");
        assertThat(result).doesNotContain("\n");
    }

    @Test
    void sanitize_shouldStripTab_whenInputContainsTab() {
        // Given
        String maliciousInput = "key1\tfake-column";

        // When
        String result = LogSanitizer.sanitize(maliciousInput);

        // Then
        assertThat(result).isEqualTo("key1_fake-column");
        assertThat(result).doesNotContain("\t");
    }

    @Test
    void sanitize_shouldStripMultipleControlCharacters_whenInputContainsMixed() {
        // Given
        String maliciousInput = "key1\n\r\tfake\nlog";

        // When
        String result = LogSanitizer.sanitize(maliciousInput);

        // Then
        assertThat(result).isEqualTo("key1___fake_log");
        assertThat(result).doesNotContain("\n", "\r", "\t");
    }

    @Test
    void sanitize_shouldHandleNull_whenInputIsNull() {
        // Given
        String nullInput = null;

        // When
        String result = LogSanitizer.sanitize(nullInput);

        // Then
        assertThat(result).isEqualTo("null");
    }

    @Test
    void sanitize_shouldHandleEmptyString_whenInputIsEmpty() {
        // Given
        String emptyInput = "";

        // When
        String result = LogSanitizer.sanitize(emptyInput);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void sanitize_shouldHandleComplexLogInjectionAttack() {
        // Given - realistic attack attempt
        String attackInput = "user-key\n2026-04-26 12:00:00 [INFO] Fake authorized access from admin\nreal-key";

        // When
        String result = LogSanitizer.sanitize(attackInput);

        // Then
        assertThat(result).doesNotContain("\n");
        assertThat(result).isEqualTo("user-key_2026-04-26 12:00:00 [INFO] Fake authorized access from admin_real-key");
    }
}
