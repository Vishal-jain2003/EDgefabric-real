package com.edgefabric.agentops.act;

/**
 * Represents one step's execution result within a full execution record.
 */
public record ExecutionStep(
        String name,
        ExecutionStatus status,
        long durationMs,
        String errorMessage
) {}
