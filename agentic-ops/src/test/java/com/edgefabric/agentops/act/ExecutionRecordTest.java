package com.edgefabric.agentops.act;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ExecutionRecordTest {

    private AgentAction buildApprovedAction() {
        return AgentAction.builder()
                .id("action-001")
                .type(ActionType.DRAIN)
                .target("node-1")
                .status(ActionStatus.APPROVED)
                .reasoning("Node isolated")
                .plan(List.of("drain node", "wait for handoff"))
                .rollbackHint("DELETE /api/v1/nodes/drain")
                .proposedAt(Instant.now().minusSeconds(300))
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
    }

    @Test
    void toAuditPayload_containsAllRequiredFields() {
        AgentAction action = buildApprovedAction();
        ExecutionStep step = new ExecutionStep("step1_pre_validation", ExecutionStatus.SUCCESS, 120L, null);

        ExecutionRecord execRecord = ExecutionRecord.builder()
                .actionId(action.getId())
                .actionType(action.getType())
                .target(action.getTarget())
                .mode(ExecutionMode.AUTOMATIC)
                .status(ExecutionStatus.SUCCESS)
                .startedAt(Instant.now().minusSeconds(10))
                .completedAt(Instant.now())
                .steps(List.of(step))
                .rollbackStrategy(RollbackStrategy.UNDO)
                .build();

        Map<String, Object> payload = execRecord.toAuditPayload();

        assertThat(payload).containsKey("actionId");
        assertThat(payload).containsKey("actionType");
        assertThat(payload).containsKey("target");
        assertThat(payload).containsKey("mode");
        assertThat(payload).containsKey("status");
        assertThat(payload).containsKey("startedAt");
        assertThat(payload).containsKey("completedAt");
        assertThat(payload).containsKey("stepCount");
        assertThat(payload).containsKey("rollbackStrategy");
        assertThat(payload.get("actionId")).isEqualTo("action-001");
        assertThat(payload.get("actionType")).isEqualTo("DRAIN");
        assertThat(payload.get("target")).isEqualTo("node-1");
        assertThat(payload.get("status")).isEqualTo("SUCCESS");
        assertThat(payload.get("stepCount")).isEqualTo(1);
    }

    @Test
    void toAuditPayload_failureIncludesErrorInfo() {
        AgentAction action = buildApprovedAction();
        ExecutionStep failedStep = new ExecutionStep("step3_guardrail", ExecutionStatus.FAILURE, 50L, "blast radius exceeded");

        ExecutionRecord execRecord = ExecutionRecord.builder()
                .actionId(action.getId())
                .actionType(action.getType())
                .target(action.getTarget())
                .mode(ExecutionMode.AUTOMATIC)
                .status(ExecutionStatus.FAILURE)
                .startedAt(Instant.now().minusSeconds(5))
                .completedAt(Instant.now())
                .steps(List.of(failedStep))
                .rollbackStrategy(RollbackStrategy.COMPENSATE)
                .failureReason("blast radius exceeded")
                .build();

        Map<String, Object> payload = execRecord.toAuditPayload();

        assertThat(payload).containsKey("failureReason");
        assertThat(payload.get("failureReason")).isEqualTo("blast radius exceeded");
        assertThat(payload.get("status")).isEqualTo("FAILURE");
    }

    @Test
    void executionStep_storesAllFields() {
        ExecutionStep step = new ExecutionStep("step5_approval_gate", ExecutionStatus.SKIPPED, 0L, null);

        assertThat(step.name()).isEqualTo("step5_approval_gate");
        assertThat(step.status()).isEqualTo(ExecutionStatus.SKIPPED);
        assertThat(step.durationMs()).isEqualTo(0L);
        assertThat(step.errorMessage()).isNull();
    }

    @Test
    void executionRecord_totalDurationMs_sumOfSteps() {
        ExecutionStep s1 = new ExecutionStep("s1", ExecutionStatus.SUCCESS, 100L, null);
        ExecutionStep s2 = new ExecutionStep("s2", ExecutionStatus.SUCCESS, 200L, null);

        ExecutionRecord execRecord = ExecutionRecord.builder()
                .actionId("a1")
                .actionType(ActionType.SCALE_OUT)
                .target("cluster")
                .mode(ExecutionMode.SUPERVISED)
                .status(ExecutionStatus.SUCCESS)
                .startedAt(Instant.now().minusSeconds(1))
                .completedAt(Instant.now())
                .steps(List.of(s1, s2))
                .rollbackStrategy(RollbackStrategy.UNDO)
                .build();

        assertThat(execRecord.totalDurationMs()).isEqualTo(300L);
    }
}
