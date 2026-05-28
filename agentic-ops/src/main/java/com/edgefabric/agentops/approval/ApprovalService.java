package com.edgefabric.agentops.approval;

import com.edgefabric.agentops.act.ActionRepository;
import com.edgefabric.agentops.act.ActionStatus;
import com.edgefabric.agentops.act.AgentAction;
import com.edgefabric.agentops.audit.AuditLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final ActionRepository actionRepository;
    private final AuditLogger auditLogger;

    public AgentAction approve(String id, String operatorUsername, String comment) {
        AgentAction action = actionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Action '" + id + "' not found"));

        if (action.getStatus() != ActionStatus.PENDING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Action is not pending approval — current status: " + action.getStatus());
        }

        if (Instant.now().isAfter(action.getExpiresAt())) {
            action.updateStatus(ActionStatus.EXPIRED);
            actionRepository.save(action);
            auditLogger.logDecision(action, "TIMED_OUT", "system", "TTL exceeded");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Action '" + id + "' has expired and cannot be approved");
        }

        action.updateStatus(ActionStatus.APPROVED);
        action.setApprovedBy(operatorUsername);
        action.setDecidedAt(Instant.now());
        action.setComment(comment);
        auditLogger.logDecision(action, "APPROVED", operatorUsername, comment);
        return actionRepository.save(action);
    }

    public AgentAction reject(String id, String operatorUsername, String reason) {
        AgentAction action = actionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Action '" + id + "' not found"));

        if (action.getStatus() != ActionStatus.PENDING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Action is not pending approval — current status: " + action.getStatus());
        }

        if (Instant.now().isAfter(action.getExpiresAt())) {
            action.updateStatus(ActionStatus.EXPIRED);
            actionRepository.save(action);
            auditLogger.logDecision(action, "TIMED_OUT", "system", "TTL exceeded");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Action '" + id + "' has expired and cannot be rejected");
        }

        action.updateStatus(ActionStatus.REJECTED);
        action.setApprovedBy(operatorUsername);
        action.setDecidedAt(Instant.now());
        action.setRejectionReason(reason);
        auditLogger.logDecision(action, "REJECTED", operatorUsername, reason);
        return actionRepository.save(action);
    }

    public void expireStale() {
        List<AgentAction> pending = actionRepository.findByStatus(ActionStatus.PENDING_APPROVAL);
        Instant now = Instant.now();
        for (AgentAction action : pending) {
            if (now.isAfter(action.getExpiresAt())) {
                action.updateStatus(ActionStatus.EXPIRED);
                actionRepository.save(action);
                auditLogger.logDecision(action, "TIMED_OUT", "system", "TTL exceeded");
            }
        }
    }
}
