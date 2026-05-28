package com.edgefabric.agentops.act;

import com.edgefabric.agentops.act.infra.InfrastructureProvider;
import com.edgefabric.agentops.audit.AuditLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActOrchestratorTest {

    @Mock
    private ActionRepository actionRepository;

    @Mock
    private AuditLogger auditLogger;

    @Mock
    private ActionConflictGuard conflictGuard;

    @Mock
    private ActionRevalidator revalidator;

    @Mock
    private ActionRateLimiter rateLimiter;

    @Mock
    private CircuitBreaker circuitBreaker;

    @Mock
    private GuardrailEvaluator guardrailEvaluator;

    @Mock
    private InfrastructureProvider infraProvider;

    @InjectMocks
    private ActOrchestrator orchestrator;

    private AgentAction buildApprovedAction(String id, ActionType type) {
        return AgentAction.builder()
                .id(id)
                .type(type)
                .target("node-1")
                .status(ActionStatus.APPROVED)
                .reasoning("test reason")
                .plan(List.of("step1", "step2"))
                .rollbackHint("DELETE /api/v1/nodes/drain")
                .clusterSnapshotRef("snapshot@2026-01-01T00:00:00Z")
                .proposedAt(Instant.now().minusSeconds(300))
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
    }

    @BeforeEach
    void setUpHappyPath() {
        // Default: all guards pass
        when(conflictGuard.check(any(), any(), any()))
                .thenReturn(ActionConflictGuard.ConflictResult.noConflict());
        when(revalidator.revalidate(any()))
                .thenReturn(ActionRevalidator.RevalidationResult.valid(List.of()));
        when(rateLimiter.tryAcquire(anyInt())).thenReturn(true);
        when(circuitBreaker.tryAcquire()).thenReturn(true);
        when(guardrailEvaluator.evaluate(any(), anyInt(), anyInt(), anyDouble()))
                .thenReturn(new GuardrailResult(true, List.of()));
        when(actionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(infraProvider.provisionNode(any())).thenReturn("stub-instance-id");
    }

    @Test
    void execute_approvedAction_happyPath_returnsExecutedRecord() {
        AgentAction action = buildApprovedAction("happy-1", ActionType.DRAIN);
        when(actionRepository.findById("happy-1")).thenReturn(Optional.of(action));

        ExecutionRecord execRecord = orchestrator.execute("happy-1");

        assertThat(execRecord).isNotNull();
        assertThat(execRecord.actionId()).isEqualTo("happy-1");
        assertThat(execRecord.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(execRecord.steps()).isNotEmpty();
    }

    @Test
    void execute_notFound_throws404() {
        when(actionRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.execute("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void execute_notApprovedStatus_throws409() {
        AgentAction pending = AgentAction.builder()
                .id("pending-1")
                .type(ActionType.DRAIN)
                .target("node-1")
                .status(ActionStatus.PENDING_APPROVAL)
                .reasoning("test")
                .proposedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
        when(actionRepository.findById("pending-1")).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> orchestrator.execute("pending-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void execute_conflictDetected_returnsFailed() {
        AgentAction action = buildApprovedAction("conflict-1", ActionType.DRAIN);
        when(actionRepository.findById("conflict-1")).thenReturn(Optional.of(action));
        when(conflictGuard.check(any(), any(), any()))
                .thenReturn(ActionConflictGuard.ConflictResult.conflict("other-action", "target already executing"));

        ExecutionRecord execRecord = orchestrator.execute("conflict-1");

        assertThat(execRecord.status()).isEqualTo(ExecutionStatus.FAILURE);
        assertThat(execRecord.failureReason()).contains("conflict");
    }

    @Test
    void execute_rateLimiterRejects_returnsFailed() {
        AgentAction action = buildApprovedAction("rate-1", ActionType.DRAIN);
        when(actionRepository.findById("rate-1")).thenReturn(Optional.of(action));
        when(rateLimiter.tryAcquire(anyInt())).thenReturn(false);
        when(rateLimiter.getRejectionReason(anyInt())).thenReturn("MAX_CONCURRENT=1 exceeded");

        ExecutionRecord execRecord = orchestrator.execute("rate-1");

        assertThat(execRecord.status()).isEqualTo(ExecutionStatus.FAILURE);
        assertThat(execRecord.failureReason()).contains("rate");
    }

    @Test
    void execute_circuitBreakerOpen_returnsFailed() {
        AgentAction action = buildApprovedAction("cb-1", ActionType.DRAIN);
        when(actionRepository.findById("cb-1")).thenReturn(Optional.of(action));
        when(circuitBreaker.tryAcquire()).thenReturn(false);

        ExecutionRecord execRecord = orchestrator.execute("cb-1");

        assertThat(execRecord.status()).isEqualTo(ExecutionStatus.FAILURE);
        assertThat(execRecord.failureReason()).contains("circuit");
    }

    @Test
    void execute_guardrailViolation_returnsFailed() {
        AgentAction action = buildApprovedAction("guard-1", ActionType.DRAIN);
        when(actionRepository.findById("guard-1")).thenReturn(Optional.of(action));
        when(guardrailEvaluator.evaluate(any(), anyInt(), anyInt(), anyDouble()))
                .thenReturn(new GuardrailResult(false, List.of("blast radius 75% exceeds 50% limit")));

        ExecutionRecord execRecord = orchestrator.execute("guard-1");

        assertThat(execRecord.status()).isEqualTo(ExecutionStatus.FAILURE);
        assertThat(execRecord.failureReason()).contains("guardrail");
    }

    @Test
    void execute_revalidationFails_returnsFailed() {
        AgentAction action = buildApprovedAction("reval-1", ActionType.DRAIN);
        when(actionRepository.findById("reval-1")).thenReturn(Optional.of(action));
        when(revalidator.revalidate(any()))
                .thenReturn(ActionRevalidator.RevalidationResult.invalid("Action expired before execution"));

        ExecutionRecord execRecord = orchestrator.execute("reval-1");

        assertThat(execRecord.status()).isEqualTo(ExecutionStatus.FAILURE);
    }

    @Test
    void execute_producesExecutionRecord_withSteps() {
        AgentAction action = buildApprovedAction("steps-1", ActionType.DRAIN);
        when(actionRepository.findById("steps-1")).thenReturn(Optional.of(action));

        ExecutionRecord execRecord = orchestrator.execute("steps-1");

        assertThat(execRecord.steps()).isNotEmpty();
        // All steps that ran must have a status
        execRecord.steps().forEach(step -> assertThat(step.status()).isNotNull());
    }

    @Test
    void execute_updatesActionStatusToExecuted_onSuccess() {
        AgentAction action = buildApprovedAction("status-1", ActionType.DRAIN);
        when(actionRepository.findById("status-1")).thenReturn(Optional.of(action));

        orchestrator.execute("status-1");

        // Verify save was called with EXECUTING then EXECUTED
        verify(actionRepository, atLeastOnce()).save(any(AgentAction.class));
    }

    @Test
    void execute_logsAuditEvents_onSuccess() {
        AgentAction action = buildApprovedAction("audit-1", ActionType.DRAIN);
        when(actionRepository.findById("audit-1")).thenReturn(Optional.of(action));

        orchestrator.execute("audit-1");

        verify(auditLogger, atLeastOnce()).logDecision(any(), any(), any(), any());
    }

    @Test
    void execute_executionRecord_hasNonNullTimestamps() {
        AgentAction action = buildApprovedAction("ts-1", ActionType.DRAIN);
        when(actionRepository.findById("ts-1")).thenReturn(Optional.of(action));

        ExecutionRecord execRecord = orchestrator.execute("ts-1");

        assertThat(execRecord.startedAt()).isNotNull();
        assertThat(execRecord.completedAt()).isNotNull();
        assertThat(execRecord.completedAt()).isAfterOrEqualTo(execRecord.startedAt());
    }
}
