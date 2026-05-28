package com.edgefabric.caching.util;

/**
 * Utility class to sanitize user-supplied inputs before logging.
 * <p>
 * Prevents log injection attacks by stripping or encoding control characters
 * (newline, carriage return, tab, etc.) that could allow attackers to forge
 * log entries or corrupt log analysis.
 * </p>
 *
 * <h3>Security Context</h3>
 * <p>Without sanitization, an attacker could inject strings like:</p>
 * <pre>
 * "user-key\n2026-04-26 12:00:00 [INFO] Fake authorized access from admin"
 * </pre>
 * <p>which would appear as two separate log lines, hiding malicious activity.</p>
 *
 * @see <a href="https://owasp.org/www-community/attacks/Log_Injection">OWASP Log Injection</a>
 */
public final class LogSanitizer {

    private LogSanitizer() {
        // Utility class — prevent instantiation
    }

    /**
     * Sanitizes a string for safe logging by replacing all ASCII control characters
     * with underscores.
     * <p>
     * Control characters include all characters with ASCII codes 0-31 (except space)
     * and 127 (DEL). This includes newline (\n), carriage return (\r), tab (\t),
     * backspace (\b), form feed (\f), vertical tab (\u000B), bell (\u0007),
     * escape (\u001B), and others.
     * </p>
     * <p>
     * Spaces (ASCII 32) are preserved as they are safe for logging.
     * Unicode characters above 127 are preserved.
     * </p>
     *
     * @param input the user-supplied string to sanitize (may be null)
     * @return sanitized string safe for logging (returns "null" for null input)
     */
    public static String sanitize(String input) {
        if (input == null) {
            return "null";
        }

        if (input.isEmpty()) {
            return input;
        }

        // Fast path: if no control characters, return original
        boolean hasControlChars = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (isControlCharacter(c)) {
                hasControlChars = true;
                break;
            }
        }

        if (!hasControlChars) {
            return input;
        }

        // Slow path: replace control characters
        StringBuilder sanitized = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (isControlCharacter(c)) {
                sanitized.append('_');
            } else {
                sanitized.append(c);
            }
        }

        return sanitized.toString();
    }

    /**
     * Checks if a character is an ASCII control character.
     * <p>
     * Returns true for:
     * <ul>
     *   <li>ASCII 0-31 (except 32 = space)</li>
     *   <li>ASCII 127 (DEL)</li>
     * </ul>
     * </p>
     *
     * @param c the character to check
     * @return true if c is a control character, false otherwise
     */
    private static boolean isControlCharacter(char c) {
        // Control characters: 0-31 (excluding space=32) and 127 (DEL)
        return c < 32 || c == 127;
    }
}
