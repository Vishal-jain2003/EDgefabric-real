package com.edgefabric.agentops.explain;

import java.time.Instant;
import java.util.List;

/**
 * Structured diagnosis output from the Explain phase.
 */
public record Diagnosis(
        String tool,
        String severity,
        String failureMode,
        String rootCause,
        List<String> evidence,
        List<String> recommendations,
        String diagnosisString,
        Instant snapshotTime,
        String affectedComponent
) {
    public Diagnosis(String tool, String severity, String failureMode, String rootCause,
                     List<String> evidence, List<String> recommendations, String diagnosisString) {
        this(tool, severity, failureMode, rootCause, evidence, recommendations, diagnosisString, Instant.now(), null);
    }
}
