package com.edgefabric.agentops.util;

import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility for adding structured context to logs via SLF4J MDC.
 * All fields added here appear in JSON log output via {@code LogstashEncoder}.
 * Use with try-with-resources to ensure MDC is cleaned up automatically.
 */
public class StructuredLogContext implements AutoCloseable {

    private final Map<String, String> keysAdded = new HashMap<>();

    private StructuredLogContext() {}

    public static StructuredLogContext create() {
        return new StructuredLogContext();
    }

    public StructuredLogContext nodeId(String nodeId) {
        return put("nodeId", nodeId);
    }

    public StructuredLogContext clusterId(String clusterId) {
        return put("clusterId", clusterId);
    }

    public StructuredLogContext operation(String operation) {
        return put("operation", operation);
    }

    public StructuredLogContext duration(long durationMs) {
        return put("duration", String.valueOf(durationMs));
    }

    public StructuredLogContext statusCode(int statusCode) {
        return put("statusCode", String.valueOf(statusCode));
    }

    public StructuredLogContext result(String result) {
        return put("result", result);
    }

    public StructuredLogContext errorType(String errorType) {
        return put("errorType", errorType);
    }

    public StructuredLogContext errorMessage(String errorMessage) {
        return put("errorMessage", errorMessage);
    }

    public StructuredLogContext put(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
            keysAdded.put(key, value);
        }
        return this;
    }

    @Override
    public void close() {
        keysAdded.keySet().forEach(MDC::remove);
    }
}
