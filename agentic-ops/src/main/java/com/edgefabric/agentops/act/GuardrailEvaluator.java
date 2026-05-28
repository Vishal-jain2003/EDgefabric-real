package com.edgefabric.agentops.act;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates safety guardrails before action execution.
 * Checks: blast radius, cost cap, blocked operations list.
 */
@Component
public class GuardrailEvaluator {

    private final int maxBlastRadiusPercent;
    private final double costCapUsd;
    private final List<ActionType> blockedOperations;

    /**
     * Constructor for Spring injection with defaults.
     * maxBlastRadiusPercent=50, costCapUsd=100.0, no blocked ops.
     */
    public GuardrailEvaluator() {
        this(50, 100.0, List.of());
    }

    /**
     * Constructor for tests or custom configuration.
     */
    public GuardrailEvaluator(int maxBlastRadiusPercent, double costCapUsd, List<ActionType> blockedOperations) {
        this.maxBlastRadiusPercent = maxBlastRadiusPercent;
        this.costCapUsd = costCapUsd;
        this.blockedOperations = List.copyOf(blockedOperations);
    }

    /**
     * Evaluate guardrails for the proposed action.
     *
     * @param action             the action to evaluate
     * @param totalNodes         total number of nodes in the cluster
     * @param affectedNodeCount  number of nodes that will be affected by this action
     * @param estimatedCostUsd   estimated cost of this operation in USD
     * @return GuardrailResult with passed=true and empty violations if all pass
     */
    public GuardrailResult evaluate(AgentAction action, int totalNodes,
                                    int affectedNodeCount, double estimatedCostUsd) {
        List<String> violations = new ArrayList<>();

        // Check blocked operations
        if (blockedOperations.contains(action.getType())) {
            violations.add("Operation " + action.getType().name()
                    + " is in the blocked operations list");
        }

        // Check blast radius
        if (totalNodes > 0) {
            int blastRadiusPercent = (int) Math.round((100.0 * affectedNodeCount) / totalNodes);
            if (blastRadiusPercent > maxBlastRadiusPercent) {
                violations.add("Blast radius " + blastRadiusPercent + "% exceeds maximum allowed "
                        + maxBlastRadiusPercent + "%");
            }
        }

        // Check cost cap
        if (estimatedCostUsd > costCapUsd) {
            violations.add("Estimated cost $" + String.format("%.2f", estimatedCostUsd)
                    + " exceeds cost cap $" + String.format("%.2f", costCapUsd));
        }

        return violations.isEmpty() ? GuardrailResult.pass() : GuardrailResult.fail(violations);
    }
}
