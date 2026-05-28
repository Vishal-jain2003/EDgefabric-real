package com.edgefabric.agentops.act;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Re-runs pre-execution condition checks just before execution starts (step 6).
 * Returns a RevalidationResult: valid (with optional warnings) or invalid (execution must halt).
 */
@Component
@RequiredArgsConstructor
public class ActionRevalidator {

    private final ActionRepository actionRepository;

    /**
     * Revalidate an action that is about to execute.
     *
     * @param action the action to revalidate
     * @return RevalidationResult with validity flag and any warnings
     */
    public RevalidationResult revalidate(AgentAction action) {
        List<String> warnings = new ArrayList<>();

        // Check TTL
        if (action.getExpiresAt() != null && Instant.now().isAfter(action.getExpiresAt())) {
            return RevalidationResult.invalid("Action " + action.getId() + " has expired (expiresAt="
                    + action.getExpiresAt() + ")");
        }

        // Check status is still valid for execution (APPROVED or RE_VALIDATING)
        ActionStatus status = action.getStatus();
        if (status != ActionStatus.APPROVED && status != ActionStatus.RE_VALIDATING) {
            return RevalidationResult.invalid("Action status is " + status.name()
                    + " — expected APPROVED or RE_VALIDATING");
        }

        // Warn if another action on the same target has recently EXECUTED (condition may have resolved)
        List<AgentAction> all = actionRepository.findAll();
        boolean priorExecutionFound = all.stream()
                .filter(a -> !a.getId().equals(action.getId()))
                .anyMatch(a -> action.getTarget().equals(a.getTarget())
                        && a.getStatus() == ActionStatus.EXECUTED);
        if (priorExecutionFound) {
            warnings.add("A prior action on target '" + action.getTarget()
                    + "' has EXECUTED — original condition may have been resolved");
        }

        return RevalidationResult.valid(warnings);
    }

    public record RevalidationResult(
            boolean isValid,
            List<String> warnings,
            String invalidReason
    ) {
        public static RevalidationResult valid(List<String> warnings) {
            return new RevalidationResult(true, warnings, null);
        }

        public static RevalidationResult invalid(String reason) {
            return new RevalidationResult(false, List.of(reason), reason);
        }
    }
}
