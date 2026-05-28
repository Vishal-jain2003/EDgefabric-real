package com.edgefabric.agentops.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlertStoreTest {

    private AlertStore store;

    @BeforeEach
    void setUp() {
        store = new AlertStore();
    }

    @Test
    void add_and_getRecent_returnsMostRecent() {
        store.add(entryAt("fp1", "AlertA", "warning", Instant.now().minusSeconds(5)));
        store.add(entryAt("fp2", "AlertB", "critical", Instant.now()));

        List<AlertStore.AlertEntry> recent = store.getRecent(10);
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).fingerprint()).isEqualTo("fp2"); // newest first
    }

    @Test
    void getRecent_limitsResults() {
        for (int i = 0; i < 5; i++) {
            store.add(entry("fp" + i, "Alert" + i, "info"));
        }
        assertThat(store.getRecent(3)).hasSize(3);
    }

    @Test
    void add_overMaxCapacity_evictsOldest() {
        // Fill to capacity (100) + 1 more
        for (int i = 0; i < 101; i++) {
            store.add(entry("fp" + i, "Alert", "info"));
        }
        List<AlertStore.AlertEntry> all = store.getRecent(200);
        assertThat(all).hasSize(100);
        // fp0 should have been evicted, fp100 should be present
        assertThat(all).noneMatch(a -> "fp0".equals(a.fingerprint()));
        assertThat(all).anyMatch(a -> "fp100".equals(a.fingerprint()));
    }

    @Test
    void setAnalysis_updatesMatchingAlert() {
        store.add(entry("fp1", "AlertA", "critical"));
        store.setAnalysis("fp1", "Root cause: memory pressure on node-3");

        List<AlertStore.AlertEntry> recent = store.getRecent(10);
        AlertStore.AlertEntry updated = recent.stream()
                .filter(a -> "fp1".equals(a.fingerprint()))
                .findFirst().orElseThrow();
        assertThat(updated.aiAnalysis()).isEqualTo("Root cause: memory pressure on node-3");
    }

    @Test
    void setAnalysis_nonExistentFingerprint_noOp() {
        store.add(entry("fp1", "AlertA", "info"));
        store.setAnalysis("fp-unknown", "Some analysis");
        assertThat(store.getRecent(10)).hasSize(1);
        assertThat(store.getRecent(10).get(0).aiAnalysis()).isNull();
    }

    @Test
    void getRecentAlerts_returnsTwenty() {
        for (int i = 0; i < 25; i++) {
            store.add(entry("fp" + i, "Alert", "info"));
        }
        assertThat(store.getRecentAlerts()).hasSize(20);
    }

    @Test
    void getRecent_emptyStore_returnsEmptyList() {
        assertThat(store.getRecent(10)).isEmpty();
    }

    private AlertStore.AlertEntry entry(String fingerprint, String name, String severity) {
        return entryAt(fingerprint, name, severity, Instant.now());
    }

    private AlertStore.AlertEntry entryAt(String fingerprint, String name, String severity, Instant receivedAt) {
        return new AlertStore.AlertEntry(fingerprint, name, severity, "firing",
                "localhost:8082", "edgefabric", "summary", "description",
                null, receivedAt.minusSeconds(1), receivedAt);
    }
}
