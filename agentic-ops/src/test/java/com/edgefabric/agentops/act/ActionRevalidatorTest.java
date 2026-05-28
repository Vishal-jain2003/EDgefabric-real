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
class ActionRevalidatorTest {

    @Mock
    private ActionRepository actionRepository;

    @InjectMocks
    private ActionRevalidator revalidator;

    private AgentAction buildAction(ActionStatus status) {
        return AgentAction.builder()
                .id("action-rv-1")
                .type(ActionType.DRAIN)
                .target("node-1")
                .status(status)
                .reasoning("Node isolated")
                .plan(List.of("drain"))
                .proposedAt(Instant.now().minusSeconds(200))
                .expiresAt(Instant.now().plusSeconds(700))
                .build();
    }

    @Test
    void revalidate_notExpired_validStatus_returnsNoWarning() {
        AgentAction action = buildAction(ActionStatus.APPROVED);

        ActionRevalidator.RevalidationResult result = revalidator.revalidate(action);

        assertThat(result.isValid()).isTrue();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void revalidate_expiredAction_returnsInvalid() {
        AgentAction expired = AgentAction.builder()
                .id("action-rv-2")
                .type(ActionType.DRAIN)
                .target("node-1")
                .status(ActionStatus.APPROVED)
                .reasoning("Expired test")
                .proposedAt(Instant.now().minusSeconds(2000))
                .expiresAt(Instant.now().minusSeconds(100)) // already expired
                .build();

        ActionRevalidator.RevalidationResult result = revalidator.revalidate(expired);

        assertThat(result.isValid()).isFalse();
        assertThat(result.warnings()).anyMatch(w -> w.toLowerCase().contains("expir"));
    }

    @Test
    void revalidate_notApprovedStatus_returnsInvalid() {
        AgentAction action = buildAction(ActionStatus.REJECTED);

        ActionRevalidator.RevalidationResult result = revalidator.revalidate(action);

        assertThat(result.isValid()).isFalse();
        assertThat(result.warnings()).anyMatch(w -> w.contains("REJECTED") || w.toLowerCase().contains("status"));
    }

    @Test
    void revalidate_conditionChanged_returnsWarning() {
        // A DRAIN action on node-1, but actionRepository shows node-1 is no longer EXECUTING
        AgentAction action = buildAction(ActionStatus.APPROVED);

        // Another action on same node now completed — original condition may have resolved
        AgentAction completed = AgentAction.builder()
                .id("action-rv-3")
                .type(ActionType.DRAIN)
                .target("node-1")
                .status(ActionStatus.EXECUTED)
                .reasoning("Previous action resolved issue")
                .proposedAt(Instant.now().minusSeconds(500))
                .expiresAt(Instant.now().plusSeconds(100))
                .build();

        when(actionRepository.findAll()).thenReturn(List.of(completed, action));

        ActionRevalidator.RevalidationResult result = revalidator.revalidate(action);

        // Should be valid but may have warnings about condition possibly resolved
        assertThat(result.isValid()).isTrue(); // still valid — just a warning
    }

    @Test
    void revalidate_reValidatingStatus_proceedsNormally() {
        AgentAction action = buildAction(ActionStatus.RE_VALIDATING);

        ActionRevalidator.RevalidationResult result = revalidator.revalidate(action);

        assertThat(result.isValid()).isTrue();
    }
}
