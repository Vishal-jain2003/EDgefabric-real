package com.edgefabric.loadbalancer.exception;

/**
 * Thrown when a quorum-based read or write operation cannot achieve
 * the required number of successful replica responses.
 */
public class QuorumNotMetException extends RuntimeException {

    private final int required;
    private final int achieved;
    private final String operation;

    public QuorumNotMetException(String operation, int required, int achieved) {
        super(String.format("Quorum not met for %s: required %d, achieved %d",
                operation, required, achieved));
        this.operation = operation;
        this.required = required;
        this.achieved = achieved;
    }

    public int getRequired() {
        return required;
    }

    public int getAchieved() {
        return achieved;
    }

    public String getOperation() {
        return operation;
    }
}

