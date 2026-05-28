package com.edgefabric.agentops.act;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-ActionType circuit breaker with CLOSED → OPEN → HALF_OPEN state machine.
 * Stored in a ConcurrentHashMap keyed by ActionType in ActOrchestrator.
 */
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final ActionType actionType;
    private final int failureThreshold;
    private final int successThreshold;
    private final long halfOpenTimeoutMs;

    private volatile State state = State.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private volatile Instant openedAt = null;

    public CircuitBreaker(ActionType actionType, int failureThreshold,
                          int successThreshold, long halfOpenTimeoutMs) {
        this.actionType = actionType;
        this.failureThreshold = failureThreshold;
        this.successThreshold = successThreshold;
        this.halfOpenTimeoutMs = halfOpenTimeoutMs;
    }

    /**
     * Try to acquire permission to execute. Returns false when circuit is OPEN.
     * Transitions OPEN → HALF_OPEN after halfOpenTimeoutMs.
     */
    public synchronized boolean tryAcquire() {
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.OPEN) {
            if (openedAt != null
                    && Instant.now().toEpochMilli() - openedAt.toEpochMilli() >= halfOpenTimeoutMs) {
                state = State.HALF_OPEN;
                return true;
            }
            return false;
        }
        // HALF_OPEN: allow one probe through
        return true;
    }

    /**
     * Record a successful execution. In HALF_OPEN, enough successes close the circuit.
     */
    public synchronized void recordSuccess() {
        if (state == State.HALF_OPEN) {
            int successes = successCount.incrementAndGet();
            if (successes >= successThreshold) {
                state = State.CLOSED;
                failureCount.set(0);
                successCount.set(0);
                openedAt = null;
            }
        } else if (state == State.CLOSED) {
            failureCount.set(0); // reset failure count on success in CLOSED
        }
    }

    /**
     * Record a failed execution. In HALF_OPEN, any failure re-opens the circuit.
     */
    public synchronized void recordFailure() {
        if (state == State.HALF_OPEN) {
            state = State.OPEN;
            openedAt = Instant.now();
            successCount.set(0);
        } else if (state == State.CLOSED) {
            int failures = failureCount.incrementAndGet();
            if (failures >= failureThreshold) {
                state = State.OPEN;
                openedAt = Instant.now();
            }
        }
    }

    /**
     * Manually reset the circuit breaker to CLOSED state.
     */
    public synchronized void reset() {
        state = State.CLOSED;
        failureCount.set(0);
        successCount.set(0);
        openedAt = null;
    }

    public State getState() {
        return state;
    }

    public ActionType getActionType() {
        return actionType;
    }
}
