package com.edgefabric.agentops.act;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActionConflictGuardTest {

    @Mock
    private ActionRepository actionRepository;

    @InjectMocks
    private ActionConflictGuard conflictGuard;

    private AgentAction buildAction(String id, ActionType type, String target, ActionStatus status) {
        return AgentAction.builder()
                .id(id)
                .type(type)
                .target(target)
                .status(status)
                .reasoning("test")
                .proposedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }

    @Test
    void check_noConflicts_returnsNoConflict() {
        when(actionRepository.findAll()).thenReturn(List.of());

        ActionConflictGuard.ConflictResult result =
                conflictGuard.check(ActionType.DRAIN, "node-1", null);

        assertThat(result.hasConflict()).isFalse();
    }

    @Test
    void check_sameTargetExecuting_returnsConflict() {
        AgentAction executing = buildAction("a1", ActionType.DRAIN, "node-1", ActionStatus.EXECUTING);
        when(actionRepository.findAll()).thenReturn(List.of(executing));

        ActionConflictGuard.ConflictResult result =
                conflictGuard.check(ActionType.DRAIN, "node-1", null);

        assertThat(result.hasConflict()).isTrue();
        assertThat(result.conflictingActionId()).isEqualTo("a1");
        assertThat(result.reason()).contains("EXECUTING");
    }

    @Test
    void check_sameTargetApproved_returnsConflict() {
        AgentAction approved = buildAction("a2", ActionType.WARM, "node-1", ActionStatus.APPROVED);
        when(actionRepository.findAll()).thenReturn(List.of(approved));

        ActionConflictGuard.ConflictResult result =
                conflictGuard.check(ActionType.DRAIN, "node-1", null);

        assertThat(result.hasConflict()).isTrue();
        assertThat(result.conflictingActionId()).isEqualTo("a2");
    }

    @Test
    void check_differentTarget_noConflict() {
        AgentAction executing = buildAction("a3", ActionType.DRAIN, "node-2", ActionStatus.EXECUTING);
        when(actionRepository.findAll()).thenReturn(List.of(executing));

        ActionConflictGuard.ConflictResult result =
                conflictGuard.check(ActionType.DRAIN, "node-1", null);

        assertThat(result.hasConflict()).isFalse();
    }

    @Test
    void check_completedAction_noConflict() {
        AgentAction executed = buildAction("a4", ActionType.DRAIN, "node-1", ActionStatus.EXECUTED);
        when(actionRepository.findAll()).thenReturn(List.of(executed));

        ActionConflictGuard.ConflictResult result =
                conflictGuard.check(ActionType.DRAIN, "node-1", null);

        assertThat(result.hasConflict()).isFalse();
    }

    @Test
    void check_sameTargetRollingBack_returnsConflict() {
        AgentAction rollingBack = buildAction("a5", ActionType.DRAIN, "node-1", ActionStatus.ROLLING_BACK);
        when(actionRepository.findAll()).thenReturn(List.of(rollingBack));

        ActionConflictGuard.ConflictResult result =
                conflictGuard.check(ActionType.SCALE_OUT, "node-1", null);

        assertThat(result.hasConflict()).isTrue();
    }

    @Test
    void check_multipleActions_picksFirstConflict() {
        AgentAction executing1 = buildAction("a6", ActionType.DRAIN, "node-1", ActionStatus.EXECUTING);
        AgentAction executing2 = buildAction("a7", ActionType.WARM, "node-1", ActionStatus.EXECUTING);
        when(actionRepository.findAll()).thenReturn(List.of(executing1, executing2));

        ActionConflictGuard.ConflictResult result =
                conflictGuard.check(ActionType.SCALE_OUT, "node-1", null);

        assertThat(result.hasConflict()).isTrue();
        assertThat(result.conflictingActionId()).isIn("a6", "a7");
    }

    @Test
    void check_excludesOwnId_noSelfConflict() {
        AgentAction self = buildAction("a8", ActionType.DRAIN, "node-1", ActionStatus.APPROVED);
        when(actionRepository.findAll()).thenReturn(List.of(self));

        ActionConflictGuard.ConflictResult result =
                conflictGuard.check(ActionType.DRAIN, "node-1", "a8");

        assertThat(result.hasConflict()).isFalse();
    }
}
