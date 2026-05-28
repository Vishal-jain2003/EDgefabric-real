package com.edgefabric.agentops.mcp.tools;

import com.edgefabric.agentops.act.*;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * MCP tools for the ACT phase: propose and rollback cluster actions.
 * Tools: action.propose_drain, action.propose_warmup,
 *        action.propose_scale_out, action.propose_scale_in, action.rollback
 */
@Component
@RequiredArgsConstructor
public class ActTools {

    private static final String VERIFY_CLUSTER_HEALTH = "Verify cluster health";

    private final ActionProposer actionProposer;
    private final ActOrchestrator actOrchestrator;
    private final RollbackExecutor rollbackExecutor;

    @Tool(name = "action.propose_drain",
          description = "Propose a DRAIN action for a cache node. The action will require human approval before execution.")
    public AgentAction proposeDrain(String target, String reasoning) {
        validateTarget(target);
        return actionProposer.proposeManual("DRAIN", target, reasoning,
                List.of("Drain node " + target, "Wait for connection handoff", VERIFY_CLUSTER_HEALTH));
    }

    @Tool(name = "action.propose_warmup",
          description = "Propose a WARMUP action to warm a cache node back into service.")
    public AgentAction proposeWarmup(String target, String reasoning) {
        validateTarget(target);
        return actionProposer.proposeManual("WARM", target, reasoning,
                List.of("Re-register node " + target + " in the ring", "Trigger cache warm-up", VERIFY_CLUSTER_HEALTH));
    }

    @Tool(name = "action.propose_scale_out",
          description = "Propose a SCALE_OUT action to add a new cache node to the cluster.")
    public AgentAction proposeScaleOut(String target, String reasoning) {
        validateTarget(target);
        return actionProposer.proposeManual("SCALE_OUT", target, reasoning,
                List.of("Provision new node in " + target, "Register with Cloud Map", VERIFY_CLUSTER_HEALTH));
    }

    @Tool(name = "action.propose_scale_in",
          description = "Propose a SCALE_IN action to remove a cache node from the cluster.")
    public AgentAction proposeScaleIn(String target, String reasoning) {
        validateTarget(target);
        return actionProposer.proposeManual("SCALE_IN", target, reasoning,
                List.of("Drain node gracefully", "Terminate instance in " + target, VERIFY_CLUSTER_HEALTH));
    }

    @Tool(name = "action.rollback",
          description = "Roll back a completed or failed action by its ID. Dispatches to RollbackExecutor.")
    public RollbackReport rollbackAction(String actionId, String reason) {
        return rollbackExecutor.rollback(actionId, "mcp-operator", reason);
    }

    private void validateTarget(String target) {
        if (!StringUtils.hasText(target)) {
            throw new IllegalArgumentException("target must not be null or blank");
        }
    }
}
