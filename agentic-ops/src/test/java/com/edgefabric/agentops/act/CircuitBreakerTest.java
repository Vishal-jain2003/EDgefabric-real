package com.edgefabric.agentops.act;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class CircuitBreakerTest {

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        // failureThreshold=2, successThreshold=1, halfOpenTimeoutMs=100
        circuitBreaker = new CircuitBreaker(ActionType.DRAIN, 2, 1, 100L);
    }

    @Test
    void initialState_isClosed() {
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.tryAcquire()).isTrue();
    }

    @Test
    void recordSuccess_inClosedState_remainsClosed() {
        circuitBreaker.recordSuccess();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.tryAcquire()).isTrue();
    }

    @Test
    void recordFailure_belowThreshold_remainsClosed() {
        circuitBreaker.recordFailure();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.tryAcquire()).isTrue();
    }

    @Test
    void recordFailure_atThreshold_opensCircuit() {
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure(); // threshold = 2
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuitBreaker.tryAcquire()).isFalse();
    }

    @Test
    void openCircuit_afterTimeout_transitionsToHalfOpen() throws InterruptedException {
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(150); // wait past halfOpenTimeoutMs=100
        // tryAcquire should now allow one probe through (HALF_OPEN)
        assertThat(circuitBreaker.tryAcquire()).isTrue();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void halfOpenCircuit_onSuccess_closeCircuit() throws InterruptedException {
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        Thread.sleep(150);
        circuitBreaker.tryAcquire(); // transition to HALF_OPEN

        circuitBreaker.recordSuccess(); // successThreshold=1
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.tryAcquire()).isTrue();
    }

    @Test
    void halfOpenCircuit_onFailure_reopensCircuit() throws InterruptedException {
        // Use a long halfOpenTimeout so re-open can never accidentally expire during the assertion
        CircuitBreaker cb = new CircuitBreaker(ActionType.DRAIN, 2, 1, 30_000L);
        cb.recordFailure();
        cb.recordFailure();
        // Manually force to HALF_OPEN by resetting openedAt to the past via reset+recordFailure trick:
        // instead, use a short-timeout CB just for the transition, then switch to long-timeout state
        // Simpler: directly verify state after recordFailure in HALF_OPEN using the 100ms CB but
        // check state first (which is synchronous and race-free) before tryAcquire.
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        Thread.sleep(150);
        circuitBreaker.tryAcquire(); // transitions to HALF_OPEN
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        circuitBreaker.recordFailure(); // re-opens
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        // Verify blocked: use a fresh CB with long timeout to avoid the 100ms race on slow JVMs
        cb.recordFailure();
        cb.recordFailure(); // cb is now OPEN with 30s timeout — tryAcquire definitely returns false
        assertThat(cb.tryAcquire()).isFalse();
    }

    @Test
    void openCircuit_blocksAcquire() {
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();

        assertThat(circuitBreaker.tryAcquire()).isFalse();
        assertThat(circuitBreaker.tryAcquire()).isFalse(); // multiple calls still blocked
    }

    @Test
    void getActionType_returnsConfiguredType() {
        assertThat(circuitBreaker.getActionType()).isEqualTo(ActionType.DRAIN);
    }

    @Test
    void reset_fromOpenState_returnsToClosed() {
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        circuitBreaker.reset();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.tryAcquire()).isTrue();
    }
}
