package com.edgefabric.agentops.act;

import com.edgefabric.agentops.audit.AuditLogger;
import com.edgefabric.agentops.config.AgentOpsProperties;
import com.edgefabric.agentops.explain.Diagnosis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActionProposerTest {

    @Mock
    private ActionRepository actionRepository;

    @Mock
    private AgentOpsProperties agentOpsProperties;

    @Mock
    private AgentOpsProperties.ActionsProperties actionsProperties;

    @Mock
    private AuditLogger auditLogger;

    @InjectMocks
    private ActionProposer actionProposer;

    @Test
    void proposeFromDiagnosis_criticalNodeIsolation_createsDrainAction() {
        // Setup
        when(agentOpsProperties.actions()).thenReturn(actionsProperties);
        when(actionsProperties.approvalTimeoutMinutes()).thenReturn(15);

        Diagnosis diagnosis = new Diagnosis(
                "analyzer",
                "CRITICAL",
                "node_isolation",
                "Node node-1 is isolated from cluster",
                List.of("network unreachable"),
                List.of("drain node-1", "investigate network", "rejoin cluster"),
                "Node isolation detected",
                Instant.now(),
                "node-1"
        );

        // Execute
        AgentAction action = actionProposer.proposeFromDiagnosis(diagnosis);

        // Verify
        assertThat(action).isNotNull();
        assertThat(action.getType()).isEqualTo(ActionType.DRAIN);
        assertThat(action.getTarget()).isEqualTo("node-1");
        assertThat(action.getStatus()).isEqualTo(ActionStatus.PENDING_APPROVAL);
        assertThat(action.getReasoning()).isEqualTo("Node node-1 is isolated from cluster");
        assertThat(action.getExpiresAt()).isAfter(action.getProposedAt());

        verify(actionRepository).save(action);
        verify(auditLogger).logProposal(action);
    }

    @Test
    void proposeFromDiagnosis_warningCacheMemoryPressure_createsDrainAction() {
        when(agentOpsProperties.actions()).thenReturn(actionsProperties);
        when(actionsProperties.approvalTimeoutMinutes()).thenReturn(15);

        Diagnosis diagnosis = new Diagnosis(
                "analyzer",
                "WARNING",
                "cache_memory_pressure",
                "Memory usage on node-2 exceeds 85%",
                List.of("high memory"),
                List.of("drain node-2", "evict cold entries"),
                "Memory pressure detected",
                Instant.now(),
                "node-2"
        );

        AgentAction action = actionProposer.proposeFromDiagnosis(diagnosis);

        assertThat(action).isNotNull();
        assertThat(action.getType()).isEqualTo(ActionType.DRAIN);
        assertThat(action.getTarget()).isEqualTo("node-2");
        assertThat(action.getRollbackHint()).contains("DELETE /api/v1/nodes/drain");

        verify(actionRepository).save(action);
        verify(auditLogger).logProposal(action);
    }

    @Test
    void proposeFromDiagnosis_quorumFailure_createsDrainAction() {
        when(agentOpsProperties.actions()).thenReturn(actionsProperties);
        when(actionsProperties.approvalTimeoutMinutes()).thenReturn(15);

        Diagnosis diagnosis = new Diagnosis(
                "analyzer",
                "CRITICAL",
                "quorum_failure",
                "Lost quorum with only 2 of 5 nodes responding",
                List.of("quorum lost"),
                List.of("drain unhealthy nodes", "restore network"),
                "Quorum failure detected",
                Instant.now(),
                "cluster"
        );

        AgentAction action = actionProposer.proposeFromDiagnosis(diagnosis);

        assertThat(action).isNotNull();
        assertThat(action.getType()).isEqualTo(ActionType.DRAIN);
        assertThat(action.getTarget()).isEqualTo("cluster");

        verify(actionRepository).save(action);
    }

    @Test
    void proposeFromDiagnosis_ringRebalancingLag_createsScaleOutAction() {
        when(agentOpsProperties.actions()).thenReturn(actionsProperties);
        when(actionsProperties.approvalTimeoutMinutes()).thenReturn(15);

        Diagnosis diagnosis = new Diagnosis(
                "analyzer",
                "CRITICAL",
                "ring_rebalancing_lag",
                "Ring rebalancing lag > 10 minutes",
                List.of("rebalancing lag"),
                List.of("provision new node", "trigger rebalancing"),
                "Ring rebalancing lag detected",
                Instant.now(),
                "node-3"
        );

        AgentAction action = actionProposer.proposeFromDiagnosis(diagnosis);

        assertThat(action).isNotNull();
        assertThat(action.getType()).isEqualTo(ActionType.SCALE_OUT);
        assertThat(action.getRollbackHint()).contains("Drain the newly provisioned node");

        verify(actionRepository).save(action);
    }

    @Test
    void proposeFromDiagnosis_unknownFailureMode_returnsNull() {
        Diagnosis diagnosis = new Diagnosis(
                "analyzer",
                "CRITICAL",
                "unknown_mode",
                "Unknown failure",
                List.of(),
                List.of(),
                "Unknown",
                Instant.now(),
                "node-1"
        );

        AgentAction action = actionProposer.proposeFromDiagnosis(diagnosis);

        assertThat(action).isNull();
    }

    @Test
    void proposeFromDiagnosis_nonCriticalSeverity_returnsNull() {
        Diagnosis diagnosis = new Diagnosis(
                "analyzer",
                "INFO",
                "node_isolation",
                "Node isolated",
                List.of(),
                List.of(),
                "Info",
                Instant.now(),
                "node-1"
        );

        AgentAction action = actionProposer.proposeFromDiagnosis(diagnosis);

        assertThat(action).isNull();
    }

    @Test
    void proposeFromDiagnosis_nullFailureMode_returnsNull() {
        Diagnosis diagnosis = new Diagnosis(
                "analyzer",
                "CRITICAL",
                null,
                "Unknown",
                List.of(),
                List.of(),
                "Unknown",
                Instant.now(),
                "node-1"
        );

        AgentAction action = actionProposer.proposeFromDiagnosis(diagnosis);

        assertThat(action).isNull();
    }

    @Test
    void proposeFromDiagnosis_nullAffectedComponent_usesClusterTarget() {
        when(agentOpsProperties.actions()).thenReturn(actionsProperties);
        when(actionsProperties.approvalTimeoutMinutes()).thenReturn(15);

        Diagnosis diagnosis = new Diagnosis(
                "analyzer",
                "CRITICAL",
                "node_isolation",
                "Network partition detected",
                List.of(),
                List.of(),
                "Network partition",
                Instant.now(),
                null
        );

        AgentAction action = actionProposer.proposeFromDiagnosis(diagnosis);

        assertThat(action).isNotNull();
        assertThat(action.getTarget()).isEqualTo("cluster");
    }

    @Test
    void proposeFromDiagnosis_defaultTimeoutWhenPropertiesNull_uses15Minutes() {
        when(agentOpsProperties.actions()).thenReturn(null);

        Diagnosis diagnosis = new Diagnosis(
                "analyzer",
                "CRITICAL",
                "node_isolation",
                "Node isolated",
                List.of(),
                List.of(),
                "Isolated",
                Instant.now(),
                "node-1"
        );

        AgentAction action = actionProposer.proposeFromDiagnosis(diagnosis);

        assertThat(action).isNotNull();
        Instant expected = action.getProposedAt().plusSeconds(15 * 60);
        assertThat(action.getExpiresAt()).isCloseTo(expected, within(2, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void proposeFromDiagnosis_snapshotRefIncludesComponent() {
        when(agentOpsProperties.actions()).thenReturn(actionsProperties);
        when(actionsProperties.approvalTimeoutMinutes()).thenReturn(15);

        Instant snapTime = Instant.now();
        Diagnosis diagnosis = new Diagnosis(
                "analyzer",
                "CRITICAL",
                "node_isolation",
                "Node isolated",
                List.of(),
                List.of(),
                "Isolated",
                snapTime,
                "node-5"
        );

        AgentAction action = actionProposer.proposeFromDiagnosis(diagnosis);

        assertThat(action.getClusterSnapshotRef())
                .contains(snapTime.toString())
                .contains("node-5");
    }

    @Test
    void proposeManual_createsActionWithProvidedDetails() {
        when(agentOpsProperties.actions()).thenReturn(actionsProperties);
        when(actionsProperties.approvalTimeoutMinutes()).thenReturn(20);

        List<String> plan = List.of("step1", "step2");
        AgentAction action = actionProposer.proposeManual("DRAIN", "manual-node", "Manual override requested", plan);

        assertThat(action).isNotNull();
        assertThat(action.getType()).isEqualTo(ActionType.DRAIN);
        assertThat(action.getTarget()).isEqualTo("manual-node");
        assertThat(action.getReasoning()).isEqualTo("Manual override requested");
        assertThat(action.getPlan()).isEqualTo(plan);
        assertThat(action.getStatus()).isEqualTo(ActionStatus.PENDING_APPROVAL);

        verify(actionRepository).save(action);
        verify(auditLogger).logProposal(action);
    }

    @Test
    void proposeManual_validatesActionTypeEnum() {
        assertThatThrownBy(() -> actionProposer.proposeManual("INVALID_TYPE", "node-1", "reason", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void proposeManual_defaultTimeoutWhenPropertiesNull() {
        when(agentOpsProperties.actions()).thenReturn(null);

        AgentAction action = actionProposer.proposeManual("SCALE_OUT", "node-1", "reason", List.of("scale"));

        Instant expected = action.getProposedAt().plusSeconds(15 * 60);
        assertThat(action.getExpiresAt()).isCloseTo(expected, within(2, java.time.temporal.ChronoUnit.SECONDS));
    }
}
