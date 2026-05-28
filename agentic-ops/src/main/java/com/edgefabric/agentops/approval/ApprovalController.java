package com.edgefabric.agentops.approval;

import com.edgefabric.agentops.act.ActionProposer;
import com.edgefabric.agentops.act.ActionRepository;
import com.edgefabric.agentops.act.ActionStatus;
import com.edgefabric.agentops.act.AgentAction;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Tag(name = "Actions")
@RestController
@RequestMapping("/api/v1/actions")
@RequiredArgsConstructor
public class ApprovalController {

    private final ActionRepository actionRepository;
    private final ApprovalService approvalService;
    private final ActionProposer actionProposer;

    @Operation(summary = "List all agentic actions", description = "Returns all actions, optionally filtered by status.")
    @GetMapping
    public ResponseEntity<List<AgentAction>> listActions(
            @RequestParam(required = false) String status) {
        if (status != null) {
            ActionStatus actionStatus;
            try {
                actionStatus = ActionStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: " + status);
            }
            return ResponseEntity.ok(actionRepository.findByStatus(actionStatus));
        }
        return ResponseEntity.ok(actionRepository.findAll());
    }

    @Operation(summary = "Get action by ID")
    @GetMapping("/{id}")
    public ResponseEntity<AgentAction> getAction(@PathVariable String id) {
        AgentAction action = actionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Action '" + id + "' not found"));
        return ResponseEntity.ok(action);
    }

    @Operation(summary = "Manually inject an action proposal")
    @PostMapping
    public ResponseEntity<AgentAction> createAction(@Valid @RequestBody ManualActionRequest request) {
        AgentAction action = actionProposer.proposeManual(
                request.type(), request.target(), request.reasoning(), request.plan());
        return ResponseEntity.status(HttpStatus.CREATED).body(action);
    }

    @Operation(summary = "Approve a pending action")
    @PostMapping("/{id}/approve")
    public ResponseEntity<AgentAction> approveAction(
            @PathVariable String id,
            @Valid @RequestBody ApproveRequest request) {
        AgentAction action = approvalService.approve(id, request.operatorUsername(), request.comment());
        return ResponseEntity.ok(action);
    }

    @Operation(summary = "Reject a pending action")
    @PostMapping("/{id}/reject")
    public ResponseEntity<AgentAction> rejectAction(
            @PathVariable String id,
            @Valid @RequestBody RejectRequest request) {
        AgentAction action = approvalService.reject(id, request.operatorUsername(), request.reason());
        return ResponseEntity.ok(action);
    }

    record ManualActionRequest(
            @NotBlank String type,
            @NotBlank String target,
            String reasoning,
            List<String> plan
    ) {}

    record ApproveRequest(
            @NotBlank String operatorUsername,
            String comment
    ) {}

    record RejectRequest(
            @NotBlank String operatorUsername,
            String reason
    ) {}
}
