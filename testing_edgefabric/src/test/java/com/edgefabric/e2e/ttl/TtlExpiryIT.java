package com.edgefabric.e2e.ttl;

import com.edgefabric.e2e.base.BaseE2ETest;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * ════════════════════════════════════════════════════════════════════
 *  TtlExpiryIT — End-to-End Tests for TTL Expiry Behaviour
 * ════════════════════════════════════════════════════════════════════
 *
 * WHAT THIS TESTS:
 *   The full expiry lifecycle of a cache entry as seen from a client:
 *     - Entry becomes inaccessible (404) after its TTL elapses
 *     - Entry remains accessible (200) before its TTL elapses
 *     - The X-Expires-At response header on GET reflects the correct epoch ms
 *     - The expiresAt field in the PUT response body is correct for both
 *       custom and default (60 s) TTL values
 *
 * TTL IMPLEMENTATION NOTES (from CacheController.java):
 *   - X-TTL-MS default = 60 000 ms (60 seconds)
 *   - expiresAt is computed at the LB: System.currentTimeMillis() + ttl
 *   - As of EPMICMPHE-240, there is no maximum TTL limit
 *
 * TIMING STRATEGY:
 *   - Short TTL tests use X-TTL-MS = 2 000 ms (2 seconds) so they
 *     complete quickly in CI without being flaky
 *   - Awaitility polls with a 2 s initial delay then every 300 ms
 *   - All epoch-ms range assertions use a ±3 s tolerance band
 * ════════════════════════════════════════════════════════════════════
 */
@Epic("EdgeFabric Cache")
@Feature("TTL Expiry")
class TtlExpiryIT extends BaseE2ETest {

    // ─────────────────────────────────────────────────────────────────────
    // TEST 1 — Entry expires and returns 404 after TTL elapses
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Story("TTL expiry")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Cache entry should become 404 NOT_FOUND after its TTL elapses")
    void shouldExpireAfterTtl() {
        String key = "ttl-expiry-test";

        putCache(DEFAULT_TENANT, key, "expiring-value".getBytes(), "text/plain", "2000")
                .statusCode(201);

        // Poll until the entry expires — initial delay of 2 s avoids a
        // burst of requests before the TTL can possibly have elapsed.
        Awaitility.await("Entry is gone after 2 s TTL")
                .atMost(Duration.ofSeconds(10))
                .pollDelay(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() ->
                        getCache(DEFAULT_TENANT, key)
                                .statusCode(404)
                                .body("errorCode", equalTo("NOT_FOUND")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // TEST 2 — Entry is readable before TTL expires
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Story("TTL expiry")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("Cache entry should be accessible via GET before its TTL expires")
    void shouldBeAccessibleBeforeExpiry() {
        String key = "ttl-alive-test";

        putCache(DEFAULT_TENANT, key, "alive-value".getBytes(), "text/plain", "30000")
                .statusCode(201);

        getCache(DEFAULT_TENANT, key)
                .statusCode(200)
                .body(equalTo("alive-value"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // TEST 3 — X-Expires-At response header is present and correct on GET
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Story("X-Expires-At header")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("GET 200 response should carry X-Expires-At header within expected epoch-ms range")
    void shouldSetExpiresAtHeaderOnGet() {
        String key = "ttl-header-test";
        long ttlMs = 10_000L;
        long before = System.currentTimeMillis();

        putCache(DEFAULT_TENANT, key, "header-value".getBytes(), "text/plain", String.valueOf(ttlMs))
                .statusCode(201);

        String rawHeader = getCache(DEFAULT_TENANT, key)
                .statusCode(200)
                .extract().header("X-Expires-At");

        long expiresAt = Long.parseLong(rawHeader);

        assertThat(expiresAt)
                .as("X-Expires-At must be within ±3 s of (requestTime + ttl)")
                .isBetween(before + ttlMs - 3_000L, before + ttlMs + 3_000L);
    }

    // ─────────────────────────────────────────────────────────────────────
    // TEST 4 — PUT response expiresAt reflects the requested custom TTL
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Story("PUT response expiresAt")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("PUT response expiresAt field should match the requested custom TTL value")
    void shouldReflectCustomTtlInPutResponse() {
        String key = "ttl-put-custom-test";
        long ttlMs = 5_000L;
        long before = System.currentTimeMillis();

        long expiresAt = putCache(DEFAULT_TENANT, key, "custom-ttl-value".getBytes(), "text/plain", String.valueOf(ttlMs))
                .statusCode(201)
                .extract().jsonPath().getLong("expiresAt");

        assertThat(expiresAt)
                .as("expiresAt in PUT response must be within ±3 s of (requestTime + customTtl)")
                .isBetween(before + ttlMs - 3_000L, before + ttlMs + 3_000L);
    }

    // ─────────────────────────────────────────────────────────────────────
    // TEST 5 — PUT response expiresAt reflects the default 60 s TTL
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Story("Default TTL")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("PUT response expiresAt should reflect the default 60 s TTL when X-TTL-MS is omitted")
    void shouldUseDefaultTtlInPutResponse() {
        String key = "ttl-default-test";
        long before = System.currentTimeMillis();

        // 4-arg overload omits X-TTL-MS — controller default is 60 000 ms
        long expiresAt = putCache(DEFAULT_TENANT, key, "default-ttl-value".getBytes(), "text/plain")
                .statusCode(201)
                .extract().jsonPath().getLong("expiresAt");

        assertThat(expiresAt)
                .as("expiresAt must be within ±3 s of (requestTime + DEFAULT_TTL_MS=60 000)")
                .isBetween(before + DEFAULT_TTL_MS - 3_000L, before + DEFAULT_TTL_MS + 3_000L);
    }
}
