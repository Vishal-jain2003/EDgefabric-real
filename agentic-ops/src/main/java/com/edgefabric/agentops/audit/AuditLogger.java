package com.edgefabric.agentops.audit;

import com.edgefabric.agentops.act.AgentAction;
import com.edgefabric.agentops.util.StructuredLogContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AuditLogger {

    public void logDecision(AgentAction action, String decision, String operatorUsername, String details) {
        try (var logCtx = StructuredLogContext.create()
                .operation("APPROVAL_DECISION")
                .put("actionId", action.getId())
                .put("actionType", action.getType().name())
                .put("target", action.getTarget())
                .put("decision", decision)
                .put("operatorUsername", operatorUsername)
                .put("details", details)
                .result(decision)) {
            log.info("Agentic action decision recorded");
        }
    }

    public void logProposal(AgentAction action) {
        try (var logCtx = StructuredLogContext.create()
                .operation("PROPOSAL_CREATED")
                .put("actionId", action.getId())
                .put("actionType", action.getType().name())
                .put("target", action.getTarget())
                .put("status", "PENDING_APPROVAL")
                .put("expiresAt", action.getExpiresAt() != null ? action.getExpiresAt().toString() : null)) {
            log.info("Agentic action proposal created");
        }
    }
}
