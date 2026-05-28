package com.edgefabric.agentops.approval;

import com.edgefabric.agentops.act.ActionRepository;
import com.edgefabric.agentops.act.ActionStatus;
import com.edgefabric.agentops.act.ActionType;
import com.edgefabric.agentops.act.AgentAction;
import com.edgefabric.agentops.audit.AuditLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApprovalServiceTest {

    @Mock
    private ActionRepository actionRepository;

    @Mock
    private AuditLogger auditLogger;

    @InjectMocks
    private ApprovalService approvalService;

    @Test
    void approve_happyPath_transitionsToApproved() {
        AgentAction action = buildPendingAction(Instant.now().plusSeconds(900));
        when(actionRepository.findById(action.getId())).thenReturn(Optional.of(action));
        when(actionRepository.save(action)).thenReturn(action);

        AgentAction result = approvalService.approve(action.getId(), "operator1", "looks good");

        assertThat(result.getStatus()).isEqualTo(ActionStatus.APPROVED);
        assertThat(result.getApprovedBy()).isEqualTo("operator1");
        assertThat(result.getComment()).isEqualTo("looks good");
        assertThat(result.getDecidedAt()).isNotNull();
        verify(auditLogger).logDecision(eq(action), eq("APPROVED"), eq("operator1"), eq("looks good"));
    }

    @Test
    void approve_alreadyExpired_throws409() {
        AgentAction action = buildPendingAction(Instant.now().minusSeconds(1));
        when(actionRepository.findById(action.getId())).thenReturn(Optional.of(action));
        when(actionRepository.save(action)).thenReturn(action);

        assertThatThrownBy(() -> approvalService.approve(action.getId(), "operator1", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(409));

        verify(auditLogger).logDecision(eq(action), eq("TIMED_OUT"), eq("system"), eq("TTL exceeded"));
    }

    @Test
    void approve_notPendingApproval_throws409() {
        AgentAction action = buildActionWithStatus(ActionStatus.APPROVED, Instant.now().plusSeconds(900));
        when(actionRepository.findById(action.getId())).thenReturn(Optional.of(action));

        assertThatThrownBy(() -> approvalService.approve(action.getId(), "operator1", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(409));

        verifyNoInteractions(auditLogger);
    }

    @Test
    void approve_notFound_throws404() {
        String id = UUID.randomUUID().toString();
        when(actionRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> approvalService.approve(id, "operator1", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void reject_happyPath_transitionsToRejected() {
        AgentAction action = buildPendingAction(Instant.now().plusSeconds(900));
        when(actionRepository.findById(action.getId())).thenReturn(Optional.of(action));
        when(actionRepository.save(action)).thenReturn(action);

        AgentAction result = approvalService.reject(action.getId(), "operator2", "too risky");

        assertThat(result.getStatus()).isEqualTo(ActionStatus.REJECTED);
        assertThat(result.getApprovedBy()).isEqualTo("operator2");
        assertThat(result.getRejectionReason()).isEqualTo("too risky");
        assertThat(result.getDecidedAt()).isNotNull();
        verify(auditLogger).logDecision(eq(action), eq("REJECTED"), eq("operator2"), eq("too risky"));
    }

    @Test
    void reject_alreadyExpired_throws409() {
        AgentAction action = buildPendingAction(Instant.now().minusSeconds(1));
        when(actionRepository.findById(action.getId())).thenReturn(Optional.of(action));
        when(actionRepository.save(action)).thenReturn(action);

        assertThatThrownBy(() -> approvalService.reject(action.getId(), "operator2", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(409));

        verify(auditLogger).logDecision(eq(action), eq("TIMED_OUT"), eq("system"), eq("TTL exceeded"));
    }

    @Test
    void reject_notPendingApproval_throws409() {
        AgentAction action = buildActionWithStatus(ActionStatus.APPROVED, Instant.now().plusSeconds(900));
        when(actionRepository.findById(action.getId())).thenReturn(Optional.of(action));

        assertThatThrownBy(() -> approvalService.reject(action.getId(), "operator2", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(409));

        verifyNoInteractions(auditLogger);
    }

    @Test
    void reject_notFound_throws404() {
        String id = UUID.randomUUID().toString();
        when(actionRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> approvalService.reject(id, "operator2", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void expireStale_expiresOverdueActions() {
        AgentAction overdue = buildPendingAction(Instant.now().minusSeconds(1));
        AgentAction fresh = buildPendingAction(Instant.now().plusSeconds(900));

        when(actionRepository.findByStatus(ActionStatus.PENDING_APPROVAL))
                .thenReturn(List.of(overdue, fresh));
        when(actionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        approvalService.expireStale();

        assertThat(overdue.getStatus()).isEqualTo(ActionStatus.EXPIRED);
        assertThat(fresh.getStatus()).isEqualTo(ActionStatus.PENDING_APPROVAL);
        verify(auditLogger, times(1))
                .logDecision(eq(overdue), eq("TIMED_OUT"), eq("system"), eq("TTL exceeded"));
        verify(auditLogger, never())
                .logDecision(eq(fresh), any(), any(), any());
    }

    private AgentAction buildPendingAction(Instant expiresAt) {
        return AgentAction.builder()
                .id(UUID.randomUUID().toString())
                .type(ActionType.DRAIN)
                .target("node-1")
                .status(ActionStatus.PENDING_APPROVAL)
                .reasoning("test reason")
                .proposedAt(Instant.now().minusSeconds(60))
                .expiresAt(expiresAt)
                .build();
    }

    private AgentAction buildActionWithStatus(ActionStatus status, Instant expiresAt) {
        return AgentAction.builder()
                .id(UUID.randomUUID().toString())
                .type(ActionType.DRAIN)
                .target("node-1")
                .status(status)
                .reasoning("test reason")
                .proposedAt(Instant.now().minusSeconds(60))
                .expiresAt(expiresAt)
                .build();
    }
}
