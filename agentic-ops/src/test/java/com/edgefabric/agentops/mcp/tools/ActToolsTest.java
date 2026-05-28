package com.edgefabric.agentops.mcp.tools;

import com.edgefabric.agentops.act.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActToolsTest {

    @Mock
    private ActionProposer actionProposer;

    @Mock
    private ActOrchestrator actOrchestrator;

    @Mock
    private RollbackExecutor rollbackExecutor;

    @InjectMocks
    private ActTools actTools;

    private AgentAction buildAction(ActionType type, String target) {
        return AgentAction.builder()
                .id("tool-action-1")
                .type(type)
                .target(target)
                .status(ActionStatus.PENDING_APPROVAL)
                .reasoning("tool initiated")
                .proposedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }

    @Test
    void proposeDrain_delegatesToActionProposer() {
        AgentAction action = buildAction(ActionType.DRAIN, "node-1");
        when(actionProposer.proposeManual(eq("DRAIN"), eq("node-1"), any(), any()))
                .thenReturn(action);

        AgentAction result = actTools.proposeDrain("node-1", "Node under memory pressure");

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(ActionType.DRAIN);
        assertThat(result.getTarget()).isEqualTo("node-1");
        verify(actionProposer).proposeManual(eq("DRAIN"), eq("node-1"), any(), any());
    }

    @Test
    void proposeWarmup_delegatesToActionProposer() {
        AgentAction action = buildAction(ActionType.WARM, "node-2");
        when(actionProposer.proposeManual(eq("WARM"), eq("node-2"), any(), any()))
                .thenReturn(action);

        AgentAction result = actTools.proposeWarmup("node-2", "Node came back online");

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(ActionType.WARM);
        verify(actionProposer).proposeManual(eq("WARM"), eq("node-2"), any(), any());
    }

    @Test
    void proposeScaleOut_delegatesToActionProposer() {
        AgentAction action = buildAction(ActionType.SCALE_OUT, "cluster");
        when(actionProposer.proposeManual(eq("SCALE_OUT"), eq("cluster"), any(), any()))
                .thenReturn(action);

        AgentAction result = actTools.proposeScaleOut("cluster", "Traffic spike expected");

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(ActionType.SCALE_OUT);
        verify(actionProposer).proposeManual(eq("SCALE_OUT"), eq("cluster"), any(), any());
    }

    @Test
    void proposeScaleIn_delegatesToActionProposer() {
        AgentAction action = buildAction(ActionType.SCALE_IN, "cluster");
        when(actionProposer.proposeManual(eq("SCALE_IN"), eq("cluster"), any(), any()))
                .thenReturn(action);

        AgentAction result = actTools.proposeScaleIn("cluster", "Low utilization");

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(ActionType.SCALE_IN);
        verify(actionProposer).proposeManual(eq("SCALE_IN"), eq("cluster"), any(), any());
    }

    @Test
    void rollbackAction_delegatesToRollbackExecutor() {
        RollbackReport report = new RollbackReport(
                "tool-action-1", "mcp-operator", "MCP rollback",
                2, 2, 0,
                Instant.now().minusSeconds(1), Instant.now(), true
        );
        when(rollbackExecutor.rollback(eq("tool-action-1"), any(), eq("MCP rollback")))
                .thenReturn(report);

        RollbackReport result = actTools.rollbackAction("tool-action-1", "MCP rollback");

        assertThat(result).isNotNull();
        assertThat(result.actionId()).isEqualTo("tool-action-1");
        assertThat(result.succeeded()).isTrue();
        verify(rollbackExecutor).rollback(eq("tool-action-1"), any(), eq("MCP rollback"));
    }

    @Test
    void proposeDrain_nullTarget_throwsException() {
        assertThatThrownBy(() -> actTools.proposeDrain(null, "reason"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void proposeDrain_blankTarget_throwsException() {
        assertThatThrownBy(() -> actTools.proposeDrain("", "reason"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
