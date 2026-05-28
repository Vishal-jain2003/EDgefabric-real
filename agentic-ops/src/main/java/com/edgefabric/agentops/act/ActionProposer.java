package com.edgefabric.agentops.act;

import com.edgefabric.agentops.audit.AuditLogger;
import com.edgefabric.agentops.config.AgentOpsProperties;
import com.edgefabric.agentops.explain.Diagnosis;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ActionProposer {

    private final ActionRepository actionRepository;
    private final AgentOpsProperties agentOpsProperties;
    private final AuditLogger auditLogger;

    public AgentAction proposeFromDiagnosis(Diagnosis diagnosis) {
        String severity = diagnosis.severity();
        if (!"CRITICAL".equals(severity) && !"WARNING".equals(severity)) {
            return null;
        }

        String failureMode = diagnosis.failureMode();
        if (failureMode == null) {
            return null;
        }

        ActionType actionType = switch (failureMode) {
            case "node_isolation" -> ActionType.DRAIN;
            case "quorum_failure" -> ActionType.DRAIN;
            case "cache_memory_pressure" -> ActionType.DRAIN;
            case "ring_rebalancing_lag" -> ActionType.SCALE_OUT;
            default -> null;
        };

        if (actionType == null) {
            return null;
        }

        String rollbackHint = switch (failureMode) {
            case "node_isolation", "quorum_failure", "cache_memory_pressure" ->
                    "DELETE /api/v1/nodes/drain — cancels drain, node re-enters ring";
            case "ring_rebalancing_lag" ->
                    "Drain the newly provisioned node and terminate it via AWS EC2";
            default -> null;
        };

        String target = diagnosis.affectedComponent() != null ? diagnosis.affectedComponent() : "cluster";

        String snapshotRef = "snapshot@" + diagnosis.snapshotTime()
                + (diagnosis.affectedComponent() != null ? ", component=" + diagnosis.affectedComponent() : "");

        Instant proposedAt = Instant.now();
        int timeoutMinutes = agentOpsProperties.actions() != null
                ? agentOpsProperties.actions().approvalTimeoutMinutes()
                : 15;
        Instant expiresAt = proposedAt.plusSeconds(timeoutMinutes * 60L);

        AgentAction action = AgentAction.builder()
                .id(UUID.randomUUID().toString())
                .type(actionType)
                .target(target)
                .status(ActionStatus.PENDING_APPROVAL)
                .reasoning(diagnosis.rootCause())
                .plan(diagnosis.recommendations())
                .rollbackHint(rollbackHint)
                .clusterSnapshotRef(snapshotRef)
                .proposedAt(proposedAt)
                .expiresAt(expiresAt)
                .build();

        actionRepository.save(action);
        auditLogger.logProposal(action);
        return action;
    }

    public AgentAction proposeManual(String type, String target, String reasoning, List<String> plan) {
        ActionType actionType = ActionType.valueOf(type.toUpperCase());

        Instant proposedAt = Instant.now();
        int timeoutMinutes = agentOpsProperties.actions() != null
                ? agentOpsProperties.actions().approvalTimeoutMinutes()
                : 15;
        Instant expiresAt = proposedAt.plusSeconds(timeoutMinutes * 60L);

        AgentAction action = AgentAction.builder()
                .id(UUID.randomUUID().toString())
                .type(actionType)
                .target(target)
                .status(ActionStatus.PENDING_APPROVAL)
                .reasoning(reasoning)
                .plan(plan)
                .proposedAt(proposedAt)
                .expiresAt(expiresAt)
                .build();

        actionRepository.save(action);
        auditLogger.logProposal(action);
        return action;
    }
}
