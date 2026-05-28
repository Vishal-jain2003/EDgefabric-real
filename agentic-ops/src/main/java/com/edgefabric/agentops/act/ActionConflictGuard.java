package com.edgefabric.agentops.act;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Detects duplicate or conflicting actions on the same target before execution starts.
 * Checked at propose time AND at execution start (double guard).
 */
@Component
@RequiredArgsConstructor
public class ActionConflictGuard {

    private static final Set<ActionStatus> BLOCKING_STATUSES = EnumSet.of(
            ActionStatus.APPROVED,
            ActionStatus.EXECUTING,
            ActionStatus.ROLLING_BACK,
            ActionStatus.RE_VALIDATING
    );

    private final ActionRepository actionRepository;

    /**
     * Check whether the given action type + target combination conflicts with any in-flight action.
     * The action identified by {@code excludeActionId} is excluded from the check (self-conflict guard).
     *
     * @param type            the proposed action type
     * @param target          the target node or cluster
     * @param excludeActionId the ID of the action currently being executed (excluded from the check)
     * @return a ConflictResult indicating whether a conflict was found
     */
    public ConflictResult check(ActionType type, String target, String excludeActionId) {
        List<AgentAction> allActions = actionRepository.findAll();
        for (AgentAction existing : allActions) {
            if (existing.getId().equals(excludeActionId)) {
                continue;
            }
            if (target.equals(existing.getTarget()) && BLOCKING_STATUSES.contains(existing.getStatus())) {
                return ConflictResult.conflict(
                        existing.getId(),
                        "Target '" + target + "' already has action " + existing.getId()
                                + " in status " + existing.getStatus().name());
            }
        }
        return ConflictResult.noConflict();
    }

    public record ConflictResult(
            boolean hasConflict,
            String conflictingActionId,
            String reason
    ) {
        public static ConflictResult noConflict() {
            return new ConflictResult(false, null, null);
        }

        public static ConflictResult conflict(String actionId, String reason) {
            return new ConflictResult(true, actionId, reason);
        }
    }
}
