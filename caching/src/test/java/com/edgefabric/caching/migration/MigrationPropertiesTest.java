package com.edgefabric.caching.migration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationPropertiesTest {

    @Test
    void shouldHaveDefaultEnabledTrue() {
        MigrationProperties props = new MigrationProperties();

        assertThat(props.isEnabled()).isTrue();
    }

    @Test
    void shouldHaveDefaultRateLimit500() {
        MigrationProperties props = new MigrationProperties();

        assertThat(props.getRateLimit()).isEqualTo(500);
    }

    @Test
    void shouldHaveDefaultDebounceMs2000() {
        MigrationProperties props = new MigrationProperties();

        assertThat(props.getDebounceMs()).isEqualTo(2000);
    }

    @Test
    void shouldHaveDefaultMaxRetries3() {
        MigrationProperties props = new MigrationProperties();

        assertThat(props.getMaxRetries()).isEqualTo(3);
    }

    @Test
    void shouldHaveDefaultBackoffBaseMs1000() {
        MigrationProperties props = new MigrationProperties();

        assertThat(props.getBackoffBaseMs()).isEqualTo(1000);
    }

    @Test
    void shouldHaveDefaultBackoffMaxMs30000() {
        MigrationProperties props = new MigrationProperties();

        assertThat(props.getBackoffMaxMs()).isEqualTo(30000);
    }

    @Test
    void shouldAllowSettingEnabled() {
        MigrationProperties props = new MigrationProperties();

        props.setEnabled(false);

        assertThat(props.isEnabled()).isFalse();
    }

    @Test
    void shouldAllowSettingValidRateLimit() {
        MigrationProperties props = new MigrationProperties();

        props.setRateLimit(1000);

        assertThat(props.getRateLimit()).isEqualTo(1000);
    }

    @Test
    void shouldThrowWhenRateLimitIsZero() {
        MigrationProperties props = new MigrationProperties();

        assertThatThrownBy(() -> props.setRateLimit(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rateLimit must be > 0");
    }

    @Test
    void shouldThrowWhenRateLimitIsNegative() {
        MigrationProperties props = new MigrationProperties();

        assertThatThrownBy(() -> props.setRateLimit(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rateLimit must be > 0");
    }

    @Test
    void shouldIncludeInvalidValueInExceptionMessage() {
        MigrationProperties props = new MigrationProperties();

        assertThatThrownBy(() -> props.setRateLimit(-42))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-42");
    }

    @Test
    void shouldAllowSettingRateLimitToOne() {
        MigrationProperties props = new MigrationProperties();

        props.setRateLimit(1);

        assertThat(props.getRateLimit()).isEqualTo(1);
    }

    @Test
    void shouldAllowSettingDebounceMs() {
        MigrationProperties props = new MigrationProperties();

        props.setDebounceMs(5000);

        assertThat(props.getDebounceMs()).isEqualTo(5000);
    }

    @Test
    void shouldAllowSettingMaxRetries() {
        MigrationProperties props = new MigrationProperties();

        props.setMaxRetries(5);

        assertThat(props.getMaxRetries()).isEqualTo(5);
    }

    @Test
    void shouldAllowSettingBackoffBaseMs() {
        MigrationProperties props = new MigrationProperties();

        props.setBackoffBaseMs(2000);

        assertThat(props.getBackoffBaseMs()).isEqualTo(2000);
    }

    @Test
    void shouldAllowSettingBackoffMaxMs() {
        MigrationProperties props = new MigrationProperties();

        props.setBackoffMaxMs(60000);

        assertThat(props.getBackoffMaxMs()).isEqualTo(60000);
    }

    @Test
    void shouldHaveDefaultReplicationFactor3() {
        MigrationProperties props = new MigrationProperties();

        assertThat(props.getReplicationFactor()).isEqualTo(3);
    }

    @Test
    void shouldAllowSettingReplicationFactor() {
        MigrationProperties props = new MigrationProperties();

        props.setReplicationFactor(5);

        assertThat(props.getReplicationFactor()).isEqualTo(5);
    }

    @Test
    void shouldThrowWhenReplicationFactorIsZero() {
        MigrationProperties props = new MigrationProperties();

        assertThatThrownBy(() -> props.setReplicationFactor(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replicationFactor must be > 0");
    }

    @Test
    void shouldHaveDefaultDeleteDelayMs15000() {
        MigrationProperties props = new MigrationProperties();

        assertThat(props.getDeleteDelayMs()).isEqualTo(15000);
    }

    @Test
    void shouldAllowSettingDeleteDelayMs() {
        MigrationProperties props = new MigrationProperties();

        props.setDeleteDelayMs(30000);

        assertThat(props.getDeleteDelayMs()).isEqualTo(30000);
    }

    @Test
    void shouldAllowSettingDeleteDelayMsToZero() {
        MigrationProperties props = new MigrationProperties();

        props.setDeleteDelayMs(0);

        assertThat(props.getDeleteDelayMs()).isEqualTo(0);
    }

    @Test
    void shouldThrowWhenDeleteDelayMsIsNegative() {
        MigrationProperties props = new MigrationProperties();

        assertThatThrownBy(() -> props.setDeleteDelayMs(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deleteDelayMs must be >= 0");
    }

    // ── NEW: Additional validation and boundary cases ──────────────────────────

    @Test
    void shouldThrowWhenReplicationFactorIsNegative() {
        MigrationProperties props = new MigrationProperties();

        assertThatThrownBy(() -> props.setReplicationFactor(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-1");
    }

    @Test
    void shouldIncludeNegativeReplicationFactorValueInExceptionMessage() {
        MigrationProperties props = new MigrationProperties();

        assertThatThrownBy(() -> props.setReplicationFactor(-99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-99");
    }

    @Test
    void shouldAllowSettingMaxRetriesToOne() {
        MigrationProperties props = new MigrationProperties();

        props.setMaxRetries(1);

        assertThat(props.getMaxRetries()).isEqualTo(1);
    }

    @Test
    void shouldAllowSettingBackoffBaseMsToZero() {
        MigrationProperties props = new MigrationProperties();

        props.setBackoffBaseMs(0);

        assertThat(props.getBackoffBaseMs()).isEqualTo(0);
    }

    @Test
    void shouldAllowSettingDebounceToZero() {
        MigrationProperties props = new MigrationProperties();

        props.setDebounceMs(0);

        assertThat(props.getDebounceMs()).isEqualTo(0);
    }
}
