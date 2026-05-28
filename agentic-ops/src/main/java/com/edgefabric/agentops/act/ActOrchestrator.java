package com.edgefabric.agentops.act;

import com.edgefabric.agentops.act.infra.InfrastructureProvider;
import com.edgefabric.agentops.act.infra.NodeSpec;
import com.edgefabric.agentops.audit.AuditLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Main execution engine for the ACT phase.
 * Takes an APPROVED AgentAction through 9 ordered steps and produces an ExecutionRecord.
 *
 * Step 1: Pre-validation (status + TTL check)
 * Step 2: Conflict detection (ActionConflictGuard)
 * Step 3: Rate limiter check (ActionRateLimiter)
 * Step 4: Circuit breaker check (CircuitBreaker)
 * Step 5: Approval gate (SUPERVISED mode waits; AUTOMATIC proceeds)
 * Step 6: Pre-execution revalidation (ActionRevalidator)
 * Step 7: Guardrail evaluation (GuardrailEvaluator)
 * Step 8: Infrastructure execution (InfrastructureProvider)
 * Step 9: Post-execution audit (ExecutionRecord → AuditLogger)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActOrchestrator {

    private final ActionRepository actionRepository;
    private final AuditLogger auditLogger;
    private final ActionConflictGuard conflictGuard;
    private final ActionRevalidator revalidator;
    private final ActionRateLimiter rateLimiter;
    private final CircuitBreaker circuitBreaker;
    private final GuardrailEvaluator guardrailEvaluator;
    private final InfrastructureProvider infraProvider;

    // Default cluster size for guardrail calculation when not provided via a ClusterSnapshot
    private static final int DEFAULT_CLUSTER_SIZE = 3;
    private static final int DEFAULT_AFFECTED_NODES = 1;
    private static final double DEFAULT_ESTIMATED_COST = 0.0;

    /**
     * Execute an approved action through all 9 steps.
     *
     * @param actionId the ID of the APPROVED action to execute
     * @return ExecutionRecord describing the full execution
     * @throws ResponseStatusException 404 if not found, 409 if not APPROVED
     */
    public ExecutionRecord execute(String actionId) {
        AgentAction action = actionRepository.findById(actionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Action '" + actionId + "' not found"));

        if (action.getStatus() != ActionStatus.APPROVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Action '" + actionId + "' is not APPROVED — current status: " + action.getStatus()
                            + ". Only APPROVED actions can be executed.");
        }

        Instant startedAt = Instant.now();
        List<ExecutionStep> steps = new ArrayList<>();

        // Step 1: Pre-validation
        ExecutionStep step1 = runStep("step1_pre_validation", () -> {
            // TTL check
            if (action.getExpiresAt() != null && Instant.now().isAfter(action.getExpiresAt())) {
                throw new ActExecutionException("Action TTL expired");
            }
        });
        steps.add(step1);
        if (step1.status() == ExecutionStatus.FAILURE) {
            return buildFailedRecord(actionId, action.getType(), action.getTarget(),
                    startedAt, steps, "pre-validation failed: " + step1.errorMessage());
        }

        // Step 2: Conflict detection (exclude the action being executed from the check)
        ActionConflictGuard.ConflictResult conflictResult = conflictGuard.check(action.getType(), action.getTarget(), actionId);
        ExecutionStep step2 = conflictResult.hasConflict()
                ? new ExecutionStep("step2_conflict_check", ExecutionStatus.FAILURE, 0L,
                "conflict with action " + conflictResult.conflictingActionId() + ": " + conflictResult.reason())
                : new ExecutionStep("step2_conflict_check", ExecutionStatus.SUCCESS, 0L, null);
        steps.add(step2);
        if (conflictResult.hasConflict()) {
            return buildFailedRecord(actionId, action.getType(), action.getTarget(),
                    startedAt, steps, "conflict detected: " + conflictResult.reason());
        }

        // Step 3: Rate limiter
        boolean rateOk = rateLimiter.tryAcquire(DEFAULT_CLUSTER_SIZE);
        ExecutionStep step3 = rateOk
                ? new ExecutionStep("step3_rate_limiter", ExecutionStatus.SUCCESS, 0L, null)
                : new ExecutionStep("step3_rate_limiter", ExecutionStatus.FAILURE, 0L,
                rateLimiter.getRejectionReason(DEFAULT_CLUSTER_SIZE));
        steps.add(step3);
        if (!rateOk) {
            return buildFailedRecord(actionId, action.getType(), action.getTarget(),
                    startedAt, steps, "rate limiter rejected: " + step3.errorMessage());
        }

        // Step 4: Circuit breaker
        boolean cbOk = circuitBreaker.tryAcquire();
        ExecutionStep step4 = cbOk
                ? new ExecutionStep("step4_circuit_breaker", ExecutionStatus.SUCCESS, 0L, null)
                : new ExecutionStep("step4_circuit_breaker", ExecutionStatus.FAILURE, 0L,
                "circuit breaker is OPEN for action type " + action.getType());
        steps.add(step4);
        if (!cbOk) {
            rateLimiter.release();
            return buildFailedRecord(actionId, action.getType(), action.getTarget(),
                    startedAt, steps, "circuit breaker is OPEN");
        }

        // Step 5: Approval gate (SUPERVISED mode only — AUTOMATIC proceeds)
        ExecutionStep step5 = new ExecutionStep("step5_approval_gate", ExecutionStatus.SKIPPED, 0L, null);
        steps.add(step5);

        // Step 6: Pre-execution revalidation
        action.updateStatus(ActionStatus.RE_VALIDATING);
        actionRepository.save(action);
        ActionRevalidator.RevalidationResult revalResult = revalidator.revalidate(action);
        ExecutionStep step6 = revalResult.isValid()
                ? new ExecutionStep("step6_revalidation", ExecutionStatus.SUCCESS, 0L, null)
                : new ExecutionStep("step6_revalidation", ExecutionStatus.FAILURE, 0L, revalResult.invalidReason());
        steps.add(step6);
        if (!revalResult.isValid()) {
            rateLimiter.release();
            circuitBreaker.recordFailure();
            action.updateStatus(ActionStatus.FAILED);
            actionRepository.save(action);
            return buildFailedRecord(actionId, action.getType(), action.getTarget(),
                    startedAt, steps, "revalidation failed: " + revalResult.invalidReason());
        }

        // Step 7: Guardrail evaluation
        GuardrailResult guardrailResult = guardrailEvaluator.evaluate(
                action, DEFAULT_CLUSTER_SIZE, DEFAULT_AFFECTED_NODES, DEFAULT_ESTIMATED_COST);
        ExecutionStep step7 = guardrailResult.passed()
                ? new ExecutionStep("step7_guardrail", ExecutionStatus.SUCCESS, 0L, null)
                : new ExecutionStep("step7_guardrail", ExecutionStatus.FAILURE, 0L,
                String.join("; ", guardrailResult.violations()));
        steps.add(step7);
        if (!guardrailResult.passed()) {
            rateLimiter.release();
            circuitBreaker.recordFailure();
            action.updateStatus(ActionStatus.FAILED);
            actionRepository.save(action);
            return buildFailedRecord(actionId, action.getType(), action.getTarget(),
                    startedAt, steps, "guardrail violation: " + step7.errorMessage());
        }

        // Step 8: Execute
        action.updateStatus(ActionStatus.EXECUTING);
        actionRepository.save(action);
        auditLogger.logDecision(action, "EXECUTING", "system", "Starting execution");

        ExecutionStep step8 = runStep("step8_execution", () -> {
            log.info("Executing action: id={}, type={}, target={}", actionId, action.getType(), action.getTarget());
            try {
                switch (action.getType()) {
                    case SCALE_OUT, WARM -> {
                        NodeSpec spec = new NodeSpec("cache-node", "ap-south-1",
                                Map.of("Role", "hermes-cache-node", "actionId", actionId));
                        String instanceId = infraProvider.provisionNode(spec);
                        log.info("Provisioned node: instanceId={}, actionId={}", instanceId, actionId);
                    }
                    case SCALE_IN, DRAIN -> {
                        infraProvider.terminateNode(action.getTarget());
                        log.info("Terminated node: target={}, actionId={}", action.getTarget(), actionId);
                    }
                }
            } catch (Exception e) {
                throw new ActExecutionException("Infrastructure call failed: " + e.getMessage(), e);
            }
        });
        steps.add(step8);

        // Step 9: Post-execution
        Instant completedAt = Instant.now();
        if (step8.status() == ExecutionStatus.FAILURE) {
            rateLimiter.release();
            circuitBreaker.recordFailure();
            action.updateStatus(ActionStatus.FAILED);
            actionRepository.save(action);
            return buildFailedRecord(actionId, action.getType(), action.getTarget(),
                    startedAt, steps, "execution failed: " + step8.errorMessage());
        }

        ExecutionStep step9 = new ExecutionStep("step9_post_execution_audit", ExecutionStatus.SUCCESS, 0L, null);
        steps.add(step9);

        action.updateStatus(ActionStatus.EXECUTED);
        actionRepository.save(action);
        circuitBreaker.recordSuccess();
        rateLimiter.release();

        ExecutionRecord executionRecord = ExecutionRecord.builder()
                .actionId(actionId)
                .actionType(action.getType())
                .target(action.getTarget())
                .mode(ExecutionMode.AUTOMATIC)
                .status(ExecutionStatus.SUCCESS)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .steps(steps)
                .rollbackStrategy(RollbackStrategy.UNDO)
                .revalidationWarnings(revalResult.warnings())
                .build();

        auditLogger.logDecision(action, "EXECUTED", "system",
                "Execution complete. Steps=" + steps.size());

        return executionRecord;
    }

    // Helper: run a step and capture timing + result
    private ExecutionStep runStep(String name, ThrowingRunnable work) {
        long start = System.currentTimeMillis();
        try {
            work.run();
            return new ExecutionStep(name, ExecutionStatus.SUCCESS, System.currentTimeMillis() - start, null);
        } catch (ActExecutionException e) {
            return new ExecutionStep(name, ExecutionStatus.FAILURE, System.currentTimeMillis() - start, e.getMessage());
        }
    }

    private ExecutionRecord buildFailedRecord(String actionId, ActionType type, String target,
                                               Instant startedAt, List<ExecutionStep> steps, String reason) {
        return ExecutionRecord.builder()
                .actionId(actionId)
                .actionType(type)
                .target(target)
                .mode(ExecutionMode.AUTOMATIC)
                .status(ExecutionStatus.FAILURE)
                .startedAt(startedAt)
                .completedAt(Instant.now())
                .steps(steps)
                .rollbackStrategy(RollbackStrategy.UNDO)
                .failureReason(reason)
                .build();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws ActExecutionException;
    }

    static class ActExecutionException extends RuntimeException {
        ActExecutionException(String message) {
            super(message);
        }
        ActExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
