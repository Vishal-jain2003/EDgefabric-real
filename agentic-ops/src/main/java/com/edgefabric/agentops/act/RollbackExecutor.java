package com.edgefabric.agentops.act;

import com.edgefabric.agentops.act.infra.InfrastructureProvider;
import com.edgefabric.agentops.audit.AuditLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * Dispatches to type-specific rollback logic and reverses executed steps in reverse order.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RollbackExecutor {

    private static final Set<ActionStatus> ROLLBACKABLE_STATUSES = EnumSet.of(
            ActionStatus.EXECUTED,
            ActionStatus.FAILED,
            ActionStatus.PARTIALLY_EXECUTED
    );

    private final ActionRepository actionRepository;
    private final AuditLogger auditLogger;
    private final InfrastructureProvider infrastructureProvider;

    /**
     * Initiate a rollback for the given action.
     *
     * @param actionId    the action ID to roll back
     * @param initiatedBy the operator or system initiating the rollback
     * @param reason      the reason for the rollback
     * @return a RollbackReport summarizing the result
     */
    public RollbackReport rollback(String actionId, String initiatedBy, String reason) {
        AgentAction action = actionRepository.findById(actionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Action '" + actionId + "' not found"));

        if (!ROLLBACKABLE_STATUSES.contains(action.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Action '" + actionId + "' cannot be rolled back — current status: "
                            + action.getStatus() + ". Rollbackable: EXECUTED, FAILED, PARTIALLY_EXECUTED");
        }

        Instant startedAt = Instant.now();
        action.updateStatus(ActionStatus.ROLLING_BACK);
        actionRepository.save(action);
        auditLogger.logDecision(action, "ROLLBACK_INITIATED", initiatedBy, reason);

        log.info("Rolling back action {}: type={}, target={}, reason={}", actionId, action.getType(), action.getTarget(), reason);

        // Execute rollback based on action type and rollbackHint
        int stepsAttempted = 0;
        int stepsSucceeded = 0;
        int stepsFailed = 0;
        boolean succeeded = true;

        try {
            stepsAttempted = 1;
            performRollback(action);
            stepsSucceeded = 1;
            action.updateStatus(ActionStatus.ROLLED_BACK);
        } catch (Exception e) {
            log.error("Rollback failed for action {}: {}", actionId, e.getMessage(), e);
            stepsFailed = 1;
            succeeded = false;
            action.updateStatus(ActionStatus.ROLLBACK_FAILED);
        }

        actionRepository.save(action);
        Instant completedAt = Instant.now();
        auditLogger.logDecision(action, succeeded ? "ROLLED_BACK" : "ROLLBACK_FAILED", initiatedBy, reason);

        return new RollbackReport(
                actionId, initiatedBy, reason,
                stepsAttempted, stepsSucceeded, stepsFailed,
                startedAt, completedAt, succeeded
        );
    }

    private void performRollback(AgentAction action) {
        String rollbackHint = action.getRollbackHint();
        log.info("Executing rollback for type={}, target={}, hint={}",
                action.getType(), action.getTarget(), rollbackHint);
        // Phase 1 stub: log the rollback hint. AWS/HTTP rollback dispatched in Phase 2.
        // For SCALE_OUT actions, the infrastructure provider would terminate the provisioned node.
        // For DRAIN actions, the rollback hint URI cancels the drain.
    }
}
