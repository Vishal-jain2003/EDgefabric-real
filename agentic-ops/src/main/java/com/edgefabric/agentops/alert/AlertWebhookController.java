package com.edgefabric.agentops.alert;

import com.edgefabric.agentops.chat.ChatService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Receives Alertmanager webhook payloads and exposes a read endpoint for the UI.
 *
 * <h3>Alertmanager config (example)</h3>
 * <pre>
 * receivers:
 *   - name: 'edgefabric-agent'
 *     webhook_configs:
 *       - url: 'http://localhost:8090/api/v1/alerts/webhook'
 * </pre>
 *
 * <p>On receiving a {@code critical} alert the controller asynchronously asks Claude
 * to analyse it and stores the result back in {@link AlertStore}.</p>
 */
@Tag(name = "Alerts", description = "Alertmanager webhook receiver and alert feed")
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertWebhookController {

    private static final Logger log = LoggerFactory.getLogger(AlertWebhookController.class);

    private final AlertStore alertStore;
    private final ChatService chatService;

    public AlertWebhookController(AlertStore alertStore, ChatService chatService) {
        this.alertStore = alertStore;
        this.chatService = chatService;
    }

    /**
     * Alertmanager sends a POST with a JSON body containing an {@code alerts} array.
     * This endpoint parses each alert, stores it, and triggers async AI analysis
     * for critical-severity alerts.
     */
    @Operation(summary = "Alertmanager webhook receiver",
            description = "Receives Alertmanager POST payloads. Parses each alert and stores it. " +
                    "Critical alerts trigger an async AI analysis.")
    @PostMapping("/webhook")
    public ResponseEntity<Void> receiveWebhook(@RequestBody JsonNode payload) {
        JsonNode alertsArray = payload.path("alerts");
        if (!alertsArray.isArray()) {
            log.warn("Alertmanager webhook received payload with no 'alerts' array");
            return ResponseEntity.badRequest().build();
        }

        for (JsonNode raw : alertsArray) {
            AlertStore.AlertEntry entry = parseAlert(raw);
            alertStore.add(entry);
            log.info("Alert received [name={}, severity={}, status={}]",
                    entry.alertName(), entry.severity(), entry.status());

            if ("critical".equalsIgnoreCase(entry.severity()) && "firing".equalsIgnoreCase(entry.status())) {
                triggerAsyncAnalysis(entry);
            }
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Returns the most recent alerts (default: 50) for the UI feed.
     */
    @Operation(summary = "Recent alerts feed",
            description = "Returns the N most recent alerts received via webhook, newest first.")
    @GetMapping("/recent")
    public ResponseEntity<List<AlertStore.AlertEntry>> recentAlerts(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(alertStore.getRecent(limit));
    }

    // ── private helpers ────────────────────────────────────────────────────

    private AlertStore.AlertEntry parseAlert(JsonNode raw) {
        JsonNode labels      = raw.path("labels");
        JsonNode annotations = raw.path("annotations");

        String fingerprint = raw.path("fingerprint").asText("unknown-" + System.nanoTime());
        String alertName   = labels.path("alertname").asText("UnknownAlert");
        String severity    = labels.path("severity").asText("info");
        String status      = raw.path("status").asText("unknown");
        String instance    = labels.path("instance").asText(null);
        String job         = labels.path("job").asText(null);
        String summary     = annotations.path("summary").asText(null);
        String description = annotations.path("description").asText(null);

        Instant startsAt = parseInstant(raw.path("startsAt").asText(null));

        return new AlertStore.AlertEntry(
                fingerprint, alertName, severity, status,
                instance, job, summary, description,
                null, startsAt, Instant.now());
    }

    private void triggerAsyncAnalysis(AlertStore.AlertEntry alert) {
        CompletableFuture.runAsync(() -> {
            try {
                String context = String.format(
                        "ALERT FIRED: %s (severity=%s)\nInstance: %s\nSummary: %s\nDescription: %s\n" +
                                "Please diagnose this alert against the current cluster state and recommend immediate action.",
                        alert.alertName(), alert.severity(), alert.instance(),
                        alert.summary(), alert.description());

                String analysis = chatService.chat(context).response();
                alertStore.setAnalysis(alert.fingerprint(), analysis);
                log.info("AI analysis complete for alert [name={}, fingerprint={}]",
                        alert.alertName(), alert.fingerprint());
            } catch (Exception e) {
                log.warn("AI analysis failed for alert [name={}]: {}", alert.alertName(), e.getMessage());
            }
        });
    }

    private Instant parseInstant(String text) {
        if (text == null || text.isBlank()) return Instant.now();
        try {
            return Instant.parse(text);
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
