package com.edgefabric.agentops.act;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class GuardrailEvaluatorTest {

    // maxBlastRadiusPercent=50, costCapUsd=100.0, blocked ops: SCALE_IN
    private GuardrailEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new GuardrailEvaluator(50, 100.0, List.of(ActionType.SCALE_IN));
    }

    private AgentAction buildAction(ActionType type, String target) {
        return AgentAction.builder()
                .id("guard-1")
                .type(type)
                .target(target)
                .status(ActionStatus.APPROVED)
                .reasoning("test")
                .proposedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }

    @Test
    void evaluate_normalDrain_passes() {
        AgentAction action = buildAction(ActionType.DRAIN, "node-1");

        // 1 node drained out of 3 = 33% blast radius — under 50% threshold
        GuardrailResult result = evaluator.evaluate(action, 3, 1, 10.0);

        assertThat(result.passed()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void evaluate_blastRadiusExceeded_fails() {
        AgentAction action = buildAction(ActionType.DRAIN, "node-1");

        // 2 nodes drained out of 3 = 67% — exceeds 50% threshold
        GuardrailResult result = evaluator.evaluate(action, 3, 2, 10.0);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.toLowerCase().contains("blast radius"));
    }

    @Test
    void evaluate_costCapExceeded_fails() {
        AgentAction action = buildAction(ActionType.SCALE_OUT, "cluster");

        // Estimated cost $150 > cap $100
        GuardrailResult result = evaluator.evaluate(action, 3, 1, 150.0);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.toLowerCase().contains("cost"));
    }

    @Test
    void evaluate_blockedOperation_fails() {
        AgentAction action = buildAction(ActionType.SCALE_IN, "cluster");

        GuardrailResult result = evaluator.evaluate(action, 5, 1, 10.0);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.toLowerCase().contains("blocked") || v.toLowerCase().contains("scale_in"));
    }

    @Test
    void evaluate_multipleViolations_reportsAll() {
        AgentAction action = buildAction(ActionType.SCALE_IN, "cluster");

        // Both blocked operation and cost cap exceeded
        GuardrailResult result = evaluator.evaluate(action, 3, 2, 150.0);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void evaluate_zeroCost_withinCap_passes() {
        AgentAction action = buildAction(ActionType.DRAIN, "node-1");

        GuardrailResult result = evaluator.evaluate(action, 4, 1, 0.0);

        assertThat(result.passed()).isTrue();
    }

    @Test
    void evaluate_exactBlastRadiusThreshold_passes() {
        AgentAction action = buildAction(ActionType.DRAIN, "node-1");

        // Exactly 50% blast radius (1 out of 2) — should pass (not exceed)
        GuardrailResult result = evaluator.evaluate(action, 2, 1, 5.0);

        assertThat(result.passed()).isTrue();
    }
}
