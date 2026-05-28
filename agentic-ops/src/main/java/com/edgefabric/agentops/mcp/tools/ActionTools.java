package com.edgefabric.agentops.mcp.tools;

import com.edgefabric.agentops.act.ActionRepository;
import com.edgefabric.agentops.act.ActionStatus;
import com.edgefabric.agentops.act.AgentAction;
import com.edgefabric.agentops.approval.ApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ActionTools {

    private final ApprovalService approvalService;
    private final ActionRepository actionRepository;

    @Tool(name = "action_approve", description = "Approve a pending agentic action by ID. Requires operator username.")
    public AgentAction approveAction(String actionId, String operatorUsername, String comment) {
        return approvalService.approve(actionId, operatorUsername, comment);
    }

    @Tool(name = "action_reject", description = "Reject a pending agentic action by ID with an optional reason.")
    public AgentAction rejectAction(String actionId, String operatorUsername, String reason) {
        return approvalService.reject(actionId, operatorUsername, reason);
    }

    @Tool(name = "action_status", description = "Get the current status and details of an agentic action by ID.")
    public AgentAction getActionStatus(String actionId) {
        return actionRepository.findById(actionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Action '" + actionId + "' not found"));
    }

    @Tool(name = "action_history", description = "List recent agentic actions. Optional limit (default 20).")
    public List<AgentAction> getActionHistory(Integer limit) {
        return actionRepository.findRecent(limit != null ? limit : 20);
    }

    @Tool(name = "action_list_pending", description = "List all actions currently pending human approval.")
    public List<AgentAction> listPendingApprovals() {
        return actionRepository.findByStatus(ActionStatus.PENDING_APPROVAL);
    }
}
