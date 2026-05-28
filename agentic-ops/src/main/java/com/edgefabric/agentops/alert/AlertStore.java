package com.edgefabric.agentops.alert;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * In-memory circular buffer that holds the most recent {@value #MAX_ALERTS} alerts
 * received via the Alertmanager webhook.
 *
 * <p>Thread-safe: all mutations are synchronised on {@code this}.</p>
 */
@Component
public class AlertStore {

    private static final int MAX_ALERTS = 100;

    private final Deque<AlertEntry> alerts = new ArrayDeque<>(MAX_ALERTS);

    /** Add an alert, evicting the oldest if the buffer is full. */
    public synchronized void add(AlertEntry alert) {
        if (alerts.size() >= MAX_ALERTS) {
            alerts.pollFirst();
        }
        alerts.addLast(alert);
    }

    /** Update the AI analysis text for a specific alert by fingerprint. */
    public synchronized void setAnalysis(String fingerprint, String analysis) {
        alerts.stream()
                .filter(a -> fingerprint.equals(a.fingerprint()))
                .findFirst()
                .ifPresent(old -> {
                    alerts.remove(old);
                    alerts.addLast(old.withAnalysis(analysis));
                });
    }

    /** Returns the most recent {@code limit} alerts, newest first. */
    public synchronized List<AlertEntry> getRecent(int limit) {
        return alerts.stream()
                .sorted((a, b) -> b.receivedAt().compareTo(a.receivedAt()))
                .limit(limit)
                .toList();
    }

    @Tool(name = "observe_recent_alerts",
            description = "Returns the 20 most recent Alertmanager alerts received by the webhook. " +
                    "Each alert includes: alertName, severity (critical/warning/info), status (firing/resolved), " +
                    "instance, summary, description, startsAt, and optional AI analysis. " +
                    "Use this to check if any SLO breaches, node failures or high burn-rate alerts are active.")
    public List<AlertEntry> getRecentAlerts() {
        return getRecent(20);
    }

    // ── Alert record ──────────────────────────────────────────────────────

    /**
     * A single alert entry from Alertmanager.
     */
    public record AlertEntry(
            String fingerprint,
            String alertName,
            String severity,
            String status,
            String instance,
            String job,
            String summary,
            String description,
            String aiAnalysis,
            Instant startsAt,
            Instant receivedAt
    ) {
        public AlertEntry withAnalysis(String analysis) {
            return new AlertEntry(fingerprint, alertName, severity, status,
                    instance, job, summary, description, analysis, startsAt, receivedAt);
        }
    }
}
