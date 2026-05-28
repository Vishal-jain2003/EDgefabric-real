package com.edgefabric.e2e.wal;

import com.edgefabric.e2e.base.BaseE2ETest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * E2E tests verifying that WAL does not alter observable API behaviour.
 *
 * <p>The WAL is a background write-ahead log — it is transparent to callers.
 * These tests confirm that every PUT/GET works correctly when WAL is active,
 * and that tenant-based routing (X-Tenant header → tenant:key composite)
 * behaves identically with WAL enabled.
 *
 * <p>Requires the Docker Compose stack started with WAL_ENABLED=true and
 * WAL_STORAGE=local (already set in docker-compose.yml).
 */
@DisplayName("WAL E2E — API behaviour unchanged when WAL is active")
class WalE2EIT extends BaseE2ETest {

    // ── 1. Basic PUT returns 201 Created ─────────────────────────────────────

    @Test
    @DisplayName("PUT succeeds and returns a message when WAL is active")
    void put_succeedsWithWalEnabled() {
        putCache(DEFAULT_TENANT, "wal-basic-key", "wal-value".getBytes(), "text/plain")
                .statusCode(201)
                .body("message", notNullValue());
    }

    // ── 2. GET after PUT still returns stored value ──────────────────────────

    @Test
    @DisplayName("GET returns the value that was PUT while WAL is active")
    void get_returnsStoredValue_withWalActive() {
        putCache(DEFAULT_TENANT, "wal-roundtrip-key", "wal-roundtrip-value".getBytes(), "text/plain")
                .statusCode(201);

        getCache(DEFAULT_TENANT, "wal-roundtrip-key")
                .statusCode(200)
                .body(equalTo("wal-roundtrip-value"));
    }

    // ── 3. Tenant header is forwarded — tenant:key composite is unique ───────

    @Test
    @DisplayName("Same key under different tenants returns tenant-specific values (WAL active)")
    void tenantIsolation_maintainedWithWalActive() {
        String key    = "wal-tenant-isolation-key";
        String valueA = "tenant-a-payload";
        String valueB = "tenant-b-payload";

        putCache("wal-tenant-a", key, valueA.getBytes(), "text/plain").statusCode(201);
        putCache("wal-tenant-b", key, valueB.getBytes(), "text/plain").statusCode(201);

        getCache("wal-tenant-a", key).statusCode(200).body(equalTo(valueA));
        getCache("wal-tenant-b", key).statusCode(200).body(equalTo(valueB));
    }

    // ── 4. Explicit tenant header is used — not the "default" fallback ───────

    @Test
    @DisplayName("Explicit X-Tenant header is respected — key stored under given tenant, not default")
    void explicitTenant_notDefault() {
        String tenant = "wal-explicit-tenant";
        String key    = "wal-explicit-key";

        putCache(tenant, key, "value".getBytes(), "text/plain").statusCode(201);

        // Retrieving under a different tenant must miss.
        getCache("other-tenant", key)
                .statusCode(404)
                .body("errorCode", equalTo("NOT_FOUND"));

        // Retrieving under the correct tenant must hit.
        getCache(tenant, key)
                .statusCode(200)
                .body(equalTo("value"));
    }

    // ── 5. Overwrite is idempotent with WAL active ───────────────────────────

    @Test
    @DisplayName("Second PUT on same tenant:key overwrites value when WAL is active")
    void overwrite_withWalActive() {
        String tenant = "wal-overwrite-tenant";
        String key    = "wal-overwrite-key";

        putCache(tenant, key, "original".getBytes(), "text/plain").statusCode(201);
        putCache(tenant, key, "updated".getBytes(),  "text/plain").statusCode(201);

        getCache(tenant, key)
                .statusCode(200)
                .body(equalTo("updated"));
    }

    // ── 7. Unknown key returns 404 as normal ─────────────────────────────────

    @Test
    @DisplayName("GET on unknown key returns 404 NOT_FOUND when WAL is active")
    void unknownKey_returns404_withWalActive() {
        getCache(DEFAULT_TENANT, "wal-nonexistent-key-xyz")
                .statusCode(404)
                .body("errorCode", equalTo("NOT_FOUND"));
    }
}
