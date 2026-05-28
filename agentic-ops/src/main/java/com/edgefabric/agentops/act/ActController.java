package com.edgefabric.agentops.act;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for the ACT phase.
 * POST /api/v1/actions/{id}/execute — trigger execution of an approved action
 * POST /api/v1/actions/{id}/rollback — trigger rollback of an executed/failed action
 */
@Tag(name = "Actions")
@RestController
@RequestMapping("/api/v1/actions")
@RequiredArgsConstructor
public class ActController {

    private final ActOrchestrator orchestrator;
    private final RollbackExecutor rollbackExecutor;

    @Operation(summary = "Execute an approved action",
               description = "Triggers the 9-step execution pipeline for an APPROVED action. Returns ExecutionRecord.")
    @PostMapping("/{id}/execute")
    public ResponseEntity<ExecutionRecord> execute(@PathVariable String id) {
        ExecutionRecord executionRecord = orchestrator.execute(id);
        return ResponseEntity.ok(executionRecord);
    }

    @Operation(summary = "Roll back an executed or failed action",
               description = "Dispatches to RollbackExecutor to reverse the action. Returns RollbackReport.")
    @PostMapping("/{id}/rollback")
    public ResponseEntity<RollbackReport> rollback(
            @PathVariable String id,
            @RequestBody(required = false) RollbackRequest request) {
        String reason = (request != null && request.reason() != null)
                ? request.reason() : "manual rollback";
        String initiatedBy = (request != null && request.initiatedBy() != null)
                ? request.initiatedBy() : "anonymous";
        RollbackReport report = rollbackExecutor.rollback(id, initiatedBy, reason);
        return ResponseEntity.ok(report);
    }

    record RollbackRequest(String reason, String initiatedBy) {}
}
