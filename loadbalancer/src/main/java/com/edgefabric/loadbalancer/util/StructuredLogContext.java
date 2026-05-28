package com.edgefabric.loadbalancer.util;

import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for adding structured context to logs via SLF4J MDC.
 * All fields added here will appear in JSON log output.
 */
public class StructuredLogContext implements AutoCloseable {

    private final Map<String, String> keysToRemove = new HashMap<>();

    private StructuredLogContext() {
    }

    /**
     * Start a new structured log context.
     */
    public static StructuredLogContext create() {
        return new StructuredLogContext();
    }

    /**
     * Add nodeId to log context for distributed tracing.
     */
    public StructuredLogContext nodeId(String nodeId) {
        return put("nodeId", nodeId);
    }

    /**
     * Add clusterId to log context for distributed tracing.
     */
    public StructuredLogContext clusterId(String clusterId) {
        return put("clusterId", clusterId);
    }

    /**
     * Add operation type (GET, PUT, DELETE, GOSSIP_SYNC, etc.).
     */
    public StructuredLogContext operation(String operation) {
        return put("operation", operation);
    }

    /**
     * Add operation duration in milliseconds.
     */
    public StructuredLogContext duration(long durationMs) {
        return put("duration", String.valueOf(durationMs));
    }

    /**
     * Add HTTP status code or result code.
     */
    public StructuredLogContext statusCode(int statusCode) {
        return put("statusCode", String.valueOf(statusCode));
    }

    /**
     * Add operation result (SUCCESS, FAILURE, TIMEOUT, etc.).
     */
    public StructuredLogContext result(String result) {
        return put("result", result);
    }

    /**
     * Add tenant identifier.
     */
    public StructuredLogContext tenant(String tenant) {
        return put("tenant", tenant);
    }

    /**
     * Add cache key.
     */
    public StructuredLogContext key(String key) {
        return put("key", key);
    }

    /**
     * Add error type for exception logging.
     */
    public StructuredLogContext errorType(String errorType) {
        return put("errorType", errorType);
    }

    /**
     * Add error message for exception logging.
     */
    public StructuredLogContext errorMessage(String errorMessage) {
        return put("errorMessage", errorMessage);
    }

    /**
     * Add arbitrary key-value pair to log context.
     */
    public StructuredLogContext put(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
            keysToRemove.put(key, value);
        }
        return this;
    }

    /**
     * Clear all MDC keys added by this context.
     * Called automatically when using try-with-resources.
     */
    @Override
    public void close() {
        keysToRemove.keySet().forEach(MDC::remove);
    }
}
