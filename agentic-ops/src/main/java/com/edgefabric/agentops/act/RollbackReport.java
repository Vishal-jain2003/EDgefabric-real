package com.edgefabric.agentops.act;

import java.time.Instant;

/**
 * Result of a rollback operation: summary of steps attempted, succeeded, and failed.
 */
public record RollbackReport(
        String actionId,
        String initiatedBy,
        String reason,
        int stepsAttempted,
        int stepsSucceeded,
        int stepsFailed,
        Instant startedAt,
        Instant completedAt,
        boolean succeeded
) {}
