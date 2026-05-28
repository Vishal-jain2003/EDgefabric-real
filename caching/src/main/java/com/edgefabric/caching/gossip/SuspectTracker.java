package com.edgefabric.caching.gossip;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SuspectTracker {

    private final Map<String, Long> suspectStartTimes = new ConcurrentHashMap<>();

    public void markSuspect(String nodeId, long timestampMs) {
        suspectStartTimes.putIfAbsent(nodeId, timestampMs);
    }

    public void clear(String nodeId) {
        suspectStartTimes.remove(nodeId);
    }

    public List<String> getExpiredSuspects(long nowMs, long timeoutMs) {
        return suspectStartTimes.entrySet().stream()
                .filter(entry -> nowMs - entry.getValue() > timeoutMs)
                .map(Map.Entry::getKey)
                .toList();
    }
}

