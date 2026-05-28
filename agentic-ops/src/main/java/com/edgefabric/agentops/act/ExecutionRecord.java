package com.edgefabric.agentops.act;

import lombok.Builder;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable record of a complete action execution, produced by ActOrchestrator.
 * The toAuditPayload() method is consumed by AuditLogger.
 */
@Builder
public record ExecutionRecord(
        String actionId,
        ActionType actionType,
        String target,
        ExecutionMode mode,
        ExecutionStatus status,
        Instant startedAt,
        Instant completedAt,
        List<ExecutionStep> steps,
        RollbackStrategy rollbackStrategy,
        String failureReason,
        List<String> revalidationWarnings
) {

    /**
     * Returns a map suitable for MDC-based structured audit logging.
     */
    public Map<String, Object> toAuditPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("actionId", actionId);
        payload.put("actionType", actionType != null ? actionType.name() : null);
        payload.put("target", target);
        payload.put("mode", mode != null ? mode.name() : null);
        payload.put("status", status != null ? status.name() : null);
        payload.put("startedAt", startedAt != null ? startedAt.toString() : null);
        payload.put("completedAt", completedAt != null ? completedAt.toString() : null);
        payload.put("stepCount", steps != null ? steps.size() : 0);
        payload.put("rollbackStrategy", rollbackStrategy != null ? rollbackStrategy.name() : null);
        if (failureReason != null) {
            payload.put("failureReason", failureReason);
        }
        if (revalidationWarnings != null && !revalidationWarnings.isEmpty()) {
            payload.put("revalidationWarnings", revalidationWarnings);
        }
        return payload;
    }

    /**
     * Sum of all step durations in milliseconds.
     */
    public long totalDurationMs() {
        if (steps == null) {
            return 0L;
        }
        return steps.stream().mapToLong(ExecutionStep::durationMs).sum();
    }
}
