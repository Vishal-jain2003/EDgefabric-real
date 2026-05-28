package com.edgefabric.agentops.act;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ActionRateLimiterTest {

    private ActionRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        // maxConcurrent=1, minGapSeconds=2, minAliveNodes=2
        rateLimiter = new ActionRateLimiter(1, 2L, 2);
    }

    @Test
    void tryAcquire_firstAction_allowed() {
        assertThat(rateLimiter.tryAcquire(3)).isTrue();
    }

    @Test
    void tryAcquire_secondConcurrentAction_rejected() {
        rateLimiter.tryAcquire(3);
        // first action is running — second should be rejected
        assertThat(rateLimiter.tryAcquire(3)).isFalse();
    }

    @Test
    void release_afterAcquire_allowsNextAction() throws InterruptedException {
        rateLimiter.tryAcquire(3);
        rateLimiter.release();

        Thread.sleep(2100); // wait for minGapSeconds
        assertThat(rateLimiter.tryAcquire(3)).isTrue();
    }

    @Test
    void tryAcquire_beforeMinGapExpired_rejected() throws InterruptedException {
        rateLimiter.tryAcquire(3);
        rateLimiter.release();

        // Immediately try again — minGap not elapsed
        assertThat(rateLimiter.tryAcquire(3)).isFalse();
    }

    @Test
    void tryAcquire_belowMinAliveNodes_rejected() {
        // Only 1 alive node, minimum is 2 — must reject
        assertThat(rateLimiter.tryAcquire(1)).isFalse();
    }

    @Test
    void tryAcquire_exactlyMinAliveNodes_allowed() {
        // Exactly 2 alive nodes, minAliveNodes=2 — should allow
        assertThat(rateLimiter.tryAcquire(2)).isTrue();
    }

    @Test
    void tryAcquire_moreThanMinAliveNodes_allowed() {
        assertThat(rateLimiter.tryAcquire(5)).isTrue();
    }

    @Test
    void getRejectionReason_concurrentLimit_returnsMessage() {
        rateLimiter.tryAcquire(3);
        assertThat(rateLimiter.getRejectionReason(3)).contains("concurrent");
    }

    @Test
    void getRejectionReason_minAliveViolation_returnsMessage() {
        assertThat(rateLimiter.getRejectionReason(1)).contains("alive");
    }
}
