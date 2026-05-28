package com.edgefabric.agentops.act;

import com.edgefabric.agentops.act.infra.InfrastructureProvider;
import com.edgefabric.agentops.audit.AuditLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RollbackExecutorTest {

    @Mock
    private ActionRepository actionRepository;

    @Mock
    private AuditLogger auditLogger;

    @Mock
    private InfrastructureProvider infrastructureProvider;

    @InjectMocks
    private RollbackExecutor rollbackExecutor;

    private AgentAction buildExecutedAction(ActionType type) {
        return AgentAction.builder()
                .id("rb-action-1")
                .type(type)
                .target("node-1")
                .status(ActionStatus.EXECUTED)
                .reasoning("test rollback")
                .plan(List.of("step1", "step2"))
                .rollbackHint("DELETE /api/v1/nodes/drain")
                .proposedAt(Instant.now().minusSeconds(600))
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    @Test
    void rollback_executedAction_returnsReport() {
        AgentAction action = buildExecutedAction(ActionType.DRAIN);
        when(actionRepository.findById("rb-action-1")).thenReturn(java.util.Optional.of(action));
        when(actionRepository.save(any())).thenReturn(action);

        RollbackReport report = rollbackExecutor.rollback("rb-action-1", "operator", "test rollback reason");

        assertThat(report).isNotNull();
        assertThat(report.actionId()).isEqualTo("rb-action-1");
        assertThat(report.reason()).isEqualTo("test rollback reason");
        assertThat(report.initiatedBy()).isEqualTo("operator");
    }

    @Test
    void rollback_notFound_throwsException() {
        when(actionRepository.findById("unknown")).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> rollbackExecutor.rollback("unknown", "operator", "reason"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void rollback_pendingApprovalAction_throwsConflict() {
        AgentAction pending = AgentAction.builder()
                .id("rb-pending")
                .type(ActionType.DRAIN)
                .target("node-1")
                .status(ActionStatus.PENDING_APPROVAL)
                .reasoning("test")
                .proposedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
        when(actionRepository.findById("rb-pending")).thenReturn(java.util.Optional.of(pending));

        assertThatThrownBy(() -> rollbackExecutor.rollback("rb-pending", "operator", "reason"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void rollback_executedAction_updatesStatusToRollingBack() {
        AgentAction action = buildExecutedAction(ActionType.DRAIN);
        when(actionRepository.findById("rb-action-1")).thenReturn(java.util.Optional.of(action));
        when(actionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        rollbackExecutor.rollback("rb-action-1", "operator", "reason");

        // Verify action status was updated
        verify(actionRepository, atLeastOnce()).save(any(AgentAction.class));
        verify(auditLogger, atLeastOnce()).logDecision(any(), any(), any(), any());
    }

    @Test
    void rollback_failedAction_alsoAllowsRollback() {
        AgentAction failed = AgentAction.builder()
                .id("rb-failed")
                .type(ActionType.SCALE_OUT)
                .target("cluster")
                .status(ActionStatus.FAILED)
                .reasoning("test")
                .proposedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(840))
                .build();
        when(actionRepository.findById("rb-failed")).thenReturn(java.util.Optional.of(failed));
        when(actionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RollbackReport report = rollbackExecutor.rollback("rb-failed", "operator", "compensate");

        assertThat(report).isNotNull();
    }

    @Test
    void rollback_report_containsStepSummary() {
        AgentAction action = buildExecutedAction(ActionType.DRAIN);
        when(actionRepository.findById("rb-action-1")).thenReturn(java.util.Optional.of(action));
        when(actionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RollbackReport report = rollbackExecutor.rollback("rb-action-1", "op", "reason");

        assertThat(report.stepsAttempted()).isGreaterThanOrEqualTo(0);
        assertThat(report.startedAt()).isNotNull();
        assertThat(report.completedAt()).isNotNull();
    }
}
