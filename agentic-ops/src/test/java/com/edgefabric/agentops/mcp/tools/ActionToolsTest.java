package com.edgefabric.agentops.mcp.tools;

import com.edgefabric.agentops.act.ActionRepository;
import com.edgefabric.agentops.act.ActionStatus;
import com.edgefabric.agentops.act.ActionType;
import com.edgefabric.agentops.act.AgentAction;
import com.edgefabric.agentops.approval.ApprovalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActionToolsTest {

    @Mock
    private ApprovalService approvalService;

    @Mock
    private ActionRepository actionRepository;

    @InjectMocks
    private ActionTools actionTools;

    @Test
    void approveAction_delegatesToApprovalService() {
        AgentAction approved = buildAction("a1", ActionStatus.APPROVED);
        when(approvalService.approve("a1", "suraj", "looks good")).thenReturn(approved);

        AgentAction result = actionTools.approveAction("a1", "suraj", "looks good");
        assertThat(result.getStatus()).isEqualTo(ActionStatus.APPROVED);
        verify(approvalService).approve("a1", "suraj", "looks good");
    }

    @Test
    void rejectAction_delegatesToApprovalService() {
        AgentAction rejected = buildAction("a1", ActionStatus.REJECTED);
        when(approvalService.reject("a1", "suraj", "too risky")).thenReturn(rejected);

        AgentAction result = actionTools.rejectAction("a1", "suraj", "too risky");
        assertThat(result.getStatus()).isEqualTo(ActionStatus.REJECTED);
        verify(approvalService).reject("a1", "suraj", "too risky");
    }

    @Test
    void getActionStatus_found_returnsAction() {
        AgentAction action = buildAction("a1", ActionStatus.PENDING_APPROVAL);
        when(actionRepository.findById("a1")).thenReturn(Optional.of(action));

        AgentAction result = actionTools.getActionStatus("a1");
        assertThat(result.getId()).isEqualTo("a1");
    }

    @Test
    void getActionStatus_notFound_throws404() {
        when(actionRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> actionTools.getActionStatus("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void getActionHistory_withLimit_usesLimit() {
        List<AgentAction> actions = List.of(buildAction("a1", ActionStatus.APPROVED));
        when(actionRepository.findRecent(5)).thenReturn(actions);

        List<AgentAction> result = actionTools.getActionHistory(5);
        assertThat(result).hasSize(1);
        verify(actionRepository).findRecent(5);
    }

    @Test
    void getActionHistory_nullLimit_usesDefault20() {
        when(actionRepository.findRecent(20)).thenReturn(List.of());

        actionTools.getActionHistory(null);
        verify(actionRepository).findRecent(20);
    }

    @Test
    void listPendingApprovals_returnsOnlyPendingActions() {
        List<AgentAction> pending = List.of(buildAction("a1", ActionStatus.PENDING_APPROVAL));
        when(actionRepository.findByStatus(ActionStatus.PENDING_APPROVAL)).thenReturn(pending);

        List<AgentAction> result = actionTools.listPendingApprovals();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(ActionStatus.PENDING_APPROVAL);
    }

    private AgentAction buildAction(String id, ActionStatus status) {
        AgentAction action = AgentAction.builder()
                .id(id)
                .type(ActionType.DRAIN)
                .target("node-1")
                .status(status)
                .reasoning("memory high")
                .plan(List.of("drain node-1"))
                .rollbackHint("DELETE /api/v1/nodes/drain")
                .clusterSnapshotRef("snapshot@" + Instant.now())
                .proposedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
        return action;
    }
}
