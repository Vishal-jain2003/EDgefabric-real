package com.edgefabric.caching.util;

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
    void sanitize_shouldStripVerticalTab_whenInputContainsVT() {
        // Given - vertical tab (ASCII 11)
        String maliciousInput = "key1\u000Bfake";

        // When
        String result = LogSanitizer.sanitize(maliciousInput);

        // Then
        assertThat(result).isEqualTo("key1_fake");
        assertThat(result).doesNotContain("\u000B");
    }

    @Test
    void sanitize_shouldStripFormFeed_whenInputContainsFF() {
        // Given - form feed (ASCII 12)
        String maliciousInput = "key1\u000Cfake";

        // When
        String result = LogSanitizer.sanitize(maliciousInput);

        // Then
        assertThat(result).isEqualTo("key1_fake");
        assertThat(result).doesNotContain("\u000C");
    }

    @Test
    void sanitize_shouldPreserveSpaces_whenInputContainsSpaces() {
        // Given
        String inputWithSpaces = "my cache key";

        // When
        String result = LogSanitizer.sanitize(inputWithSpaces);

        // Then
        assertThat(result).isEqualTo("my cache key");
    }

    @Test
    void sanitize_shouldStripBackspace_whenInputContainsBackspace() {
        // Given - backspace (ASCII 8)
        String maliciousInput = "key1\bfake";

        // When
        String result = LogSanitizer.sanitize(maliciousInput);

        // Then
        assertThat(result).isEqualTo("key1_fake");
        assertThat(result).doesNotContain("\b");
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

    @Test
    void sanitize_shouldStripBell_whenInputContainsBellCharacter() {
        // Given - bell character (ASCII 7)
        String maliciousInput = "key1\u0007fake";

        // When
        String result = LogSanitizer.sanitize(maliciousInput);

        // Then
        assertThat(result).isEqualTo("key1_fake");
        assertThat(result).doesNotContain("\u0007");
    }

    @Test
    void sanitize_shouldStripEscape_whenInputContainsEscapeSequence() {
        // Given - escape character (ASCII 27)
        String maliciousInput = "key1\u001Bfake";

        // When
        String result = LogSanitizer.sanitize(maliciousInput);

        // Then
        assertThat(result).isEqualTo("key1_fake");
        assertThat(result).doesNotContain("\u001B");
    }

    @Test
    void sanitize_shouldPreserveUnicodeCharacters_whenInputContainsUnicode() {
        // Given
        String unicodeInput = "key-日本語-αβγ";

        // When
        String result = LogSanitizer.sanitize(unicodeInput);

        // Then
        assertThat(result).isEqualTo("key-日本語-αβγ");
    }

    @Test
    void sanitize_shouldStripAllControlCharsExceptSpace_whenInputContainsASCIIControlChars() {
        // Given - multiple ASCII control characters (0-31 except space, plus 127)
        StringBuilder input = new StringBuilder("key");
        for (int i = 0; i < 32; i++) {
            if (i != 32) { // skip space
                input.append((char) i);
            }
        }
        input.append((char) 127); // DEL
        input.append("end");

        // When
        String result = LogSanitizer.sanitize(input.toString());

        // Then
        // All control chars should be replaced with underscore
        assertThat(result).startsWith("key");
        assertThat(result).endsWith("end");
        assertThat(result).doesNotContain("\n", "\r", "\t");
        // Verify no ASCII control characters remain (except space)
        for (char c : result.toCharArray()) {
            assertThat(c).isGreaterThanOrEqualTo((char) 32).isNotEqualTo((char) 127);
        }
    }
}
