package com.edgefabric.e2e.base;

import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;

import static io.restassured.RestAssured.given;

/**
 * ════════════════════════════════════════════════════════════════════
 *  BaseE2ETest — Shared Foundation for All E2E Tests
 * ════════════════════════════════════════════════════════════════════
 *
 * WHAT THIS IS:
 *   A shared abstract base class that every test class in this suite
 *   extends. It holds all shared constants, RestAssured setup, and
 *   reusable helper methods.
 *
 * WHY IT EXISTS:
 *   Without this, every test file copy-pastes:
 *     - BASE_URL, CACHE_ENDPOINT, DEFAULT_TENANT constants
 *     - @BeforeAll RestAssured setup
 *     - putCache() and getCache() helpers
 *   That means N files to update when anything changes.
 *   With this: one change here, all tests benefit.
 *
 * WHO EXTENDS THIS:
 *   - HealthCheckIT     (health/ folder)
 *   - TtlExpiryIT       (ttl/ folder)
 *   - PutCacheFeatureIT and GetCacheFeatureIT can also extend this
 *     in a future refactor (currently they have their own duplicate setup)
 *
 * WHO DOES NOT EXTEND THIS:
 *   - ClusterMembershipIT — it talks to cache node ports (8081/8082/8083),
 *     not the Load Balancer (8080). It manages its own RestAssured setup.
 * ════════════════════════════════════════════════════════════════════
 */
public abstract class BaseE2ETest {

    // ─────────────────────────────────────────────────────────────────────
    // ENDPOINT CONSTANTS
    // Single source of truth for all URL paths used in tests.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Load Balancer base URL.
     * Default: http://localhost:8080
     * Override at runtime: -Dedgefabric.url=http://your-host:8080
     */
    protected static final String LB_URL =
            System.getProperty("edgefabric.url", "http://localhost:8080");

    /**
     * The only public-facing cache endpoint.
     * Every PUT and GET goes through here.
     * Full paths:
     *   PUT /api/v1/cache/{key}  → store data
     *   GET /api/v1/cache/{key}  → retrieve data
     */
    protected static final String CACHE_ENDPOINT = "/api/v1/cache";

    /**
     * Health check endpoint.
     * GET /api/v1/system/health → {"status":"UP"}
     * If this returns anything other than 200, the cluster is not ready.
     */
    protected static final String HEALTH_ENDPOINT = "/api/v1/system/health";

    /**
     * Default tenant used in tests that are not testing tenant isolation.
     *
     * HOW X-TENANT WORKS (from source code):
     *   The controller has: @RequestHeader(value = "X-Tenant", defaultValue = "default")
     *   This means: if you omit the X-Tenant header, the system silently uses "default".
     *   It does NOT return 400 Bad Request for a missing tenant header.
     *   This is a deliberate design choice — "default" is a valid tenant.
     */
    protected static final String DEFAULT_TENANT = "default";

    // ─────────────────────────────────────────────────────────────────────
    // IMPORTANT TTL VALUES
    //
    // SOURCE CODE FINDING (CacheController.java line 41):
    //   @RequestHeader(value = "X-TTL-MS", defaultValue = "60000")
    //
    // DEFAULT TTL = 60 SECONDS.
    // NOTE: As of EPMICMPHE-240, the 24-hour TTL limit has been removed.
    //
    // Impact on tests:
    //   - Data stored WITHOUT X-TTL-MS header expires after 60 seconds.
    //   - Tests that store without TTL must complete within 60 seconds.
    //   - Tests that verify persistence at >60s will fail (data has expired).
    // ─────────────────────────────────────────────────────────────────────
    protected static final long DEFAULT_TTL_MS = 60_000L;      // 60 seconds

    /**
     * Maximum allowed size for a single cache entry.
     * Source: CacheProperties.java → cache.maxCacheEntrySizeBytes = 2097152
     * Exceeding this → 413 PAYLOAD_TOO_LARGE with errorCode "PAYLOAD_TOO_LARGE"
     */
    protected static final int MAX_ENTRY_SIZE_BYTES = 2 * 1024 * 1024; // 2 097 152 bytes

    // ─────────────────────────────────────────────────────────────────────
    // RESTASSURED SETUP
    // JUnit 5 inherits @BeforeAll from parent classes automatically.
    // Subclasses do NOT need to declare their own @BeforeAll.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Runs ONCE before all tests in any class that extends BaseE2ETest.
     *
     * enableLoggingOfRequestAndResponseIfValidationFails() is essential:
     * When a test fails, RestAssured prints the full HTTP request + response.
     * Without it, you see "Expected 200, got 404" with zero context.
     * With it, you see exactly what URL was called, what headers were sent,
     * and what the server returned — makes debugging fast.
     */
    @BeforeAll
    static void setupRestAssured() {
        RestAssured.baseURI = LB_URL;
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    // ─────────────────────────────────────────────────────────────────────
    // REUSABLE REQUEST HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * PUT /api/v1/cache/{key} — stores data WITHOUT an explicit TTL.
     * The cache uses the controller default (60 seconds).
     *
     * @param tenant      X-Tenant header — which namespace this data belongs to
     * @param key         cache key — must match ^[a-zA-Z0-9:_-]+$ and be ≤250 chars
     * @param value       raw bytes to store — must not be empty, must be ≤2MB
     * @param contentType Content-Type header — preserved and returned exactly on GET
     * @return ValidatableResponse — chain .statusCode(201).body(...) on the result
     *
     * SUCCESS RESPONSE (201 Created):
     *   { "key": "...", "message": "Cache entry stored successfully",
     *     "timestamp": "...", "expiresAt": ... }
     */
    protected ValidatableResponse putCache(String tenant, String key,
                                           byte[] value, String contentType) {
        return putCache(tenant, key, value, contentType, null);
    }

    /**
     * PUT /api/v1/cache/{key} — stores data WITH an explicit TTL.
     *
     * @param ttlMs X-TTL-MS value in milliseconds as a String (e.g., "5000" = 5 seconds).
     *              Pass null to omit the header (uses the 60-second default).
     *              Value must be > 0 — zero or negative returns 400 INVALID_INPUT.
     */
    protected ValidatableResponse putCache(String tenant, String key,
                                           byte[] value, String contentType,
                                           String ttlMs) {
        RequestSpecification req = given()
                .header("X-Tenant", tenant)
                .header("Content-Type", contentType)
                .pathParam("key", key)
                .body(value);

        if (ttlMs != null) {
            req = req.header("X-TTL-MS", ttlMs);
        }

        return req.when()
                  .put(CACHE_ENDPOINT + "/{key}")
                  .then();
    }

    /**
     * GET /api/v1/cache/{key} — retrieves stored data.
     *
     * WHAT THE RESPONSE LOOKS LIKE:
     *   - 200: body = raw bytes you stored, Content-Type = what you stored with,
     *          X-Expires-At header = epoch ms when entry expires
     *   - 404: body = {"errorCode":"NOT_FOUND","message":"...","timestamp":"..."}
     *          (same shape for both "key not found" and "key expired" cases)
     *
     * IMPORTANT:
     *   The 200 response body is NOT a JSON envelope — it is the raw bytes you
     *   PUT. If you stored a JSON string, the body IS that JSON string.
     *   If you stored plain text, the body IS that plain text.
     */
    protected ValidatableResponse getCache(String tenant, String key) {
        return given()
                .header("X-Tenant", tenant)
                .pathParam("key", key)
                .when()
                .get(CACHE_ENDPOINT + "/{key}")
                .then();
    }
}
