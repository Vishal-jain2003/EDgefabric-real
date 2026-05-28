package com.edgefabric.agentops.act;

import java.util.List;

/**
 * Result of a guardrail evaluation: whether all constraints passed and any violation messages.
 */
public record GuardrailResult(
        boolean passed,
        List<String> violations
) {
    public static GuardrailResult pass() {
        return new GuardrailResult(true, List.of());
    }

    public static GuardrailResult fail(List<String> violations) {
        return new GuardrailResult(false, violations);
    }
}
