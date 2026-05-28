package com.edgefabric.agentops.act;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory rate limiter for action execution.
 * Enforces: MAX_CONCURRENT=1, MIN_GAP=2min, minAliveNodes=2.
 */
@Component
public class ActionRateLimiter {

    private final int maxConcurrent;
    private final long minGapSeconds;
    private final int minAliveNodes;

    private final AtomicInteger currentConcurrent = new AtomicInteger(0);
    private volatile Instant lastReleaseTime = null;

    /**
     * Primary constructor — called by Spring or tests with custom params.
     */
    public ActionRateLimiter(int maxConcurrent, long minGapSeconds, int minAliveNodes) {
        this.maxConcurrent = maxConcurrent;
        this.minGapSeconds = minGapSeconds;
        this.minAliveNodes = minAliveNodes;
    }

    /**
     * Default constructor for Spring injection — uses production defaults.
     * MAX_CONCURRENT=1, MIN_GAP=120s, MIN_ALIVE_NODES=2
     */
    public ActionRateLimiter() {
        this(1, 120L, 2);
    }

    /**
     * Try to acquire a slot for execution.
     *
     * @param currentAliveNodes current number of alive nodes in the cluster
     * @return true if execution is permitted, false otherwise
     */
    public synchronized boolean tryAcquire(int currentAliveNodes) {
        if (currentAliveNodes < minAliveNodes) {
            return false;
        }
        if (currentConcurrent.get() >= maxConcurrent) {
            return false;
        }
        if (lastReleaseTime != null) {
            long secondsSinceLast = Instant.now().getEpochSecond() - lastReleaseTime.getEpochSecond();
            if (secondsSinceLast < minGapSeconds) {
                return false;
            }
        }
        currentConcurrent.incrementAndGet();
        return true;
    }

    /**
     * Release the execution slot after an action completes or fails.
     */
    public synchronized void release() {
        currentConcurrent.decrementAndGet();
        lastReleaseTime = Instant.now();
    }

    /**
     * Return a human-readable rejection reason for the given alive node count.
     */
    public String getRejectionReason(int currentAliveNodes) {
        if (currentAliveNodes < minAliveNodes) {
            return "Insufficient alive nodes: " + currentAliveNodes + " < minAliveNodes=" + minAliveNodes;
        }
        if (currentConcurrent.get() >= maxConcurrent) {
            return "Max concurrent actions exceeded: current=" + currentConcurrent.get()
                    + " maxConcurrent=" + maxConcurrent;
        }
        if (lastReleaseTime != null) {
            long secondsSinceLast = Instant.now().getEpochSecond() - lastReleaseTime.getEpochSecond();
            if (secondsSinceLast < minGapSeconds) {
                return "Min gap not elapsed: " + secondsSinceLast + "s elapsed < minGapSeconds=" + minGapSeconds;
            }
        }
        return "Rate limit active";
    }
}
