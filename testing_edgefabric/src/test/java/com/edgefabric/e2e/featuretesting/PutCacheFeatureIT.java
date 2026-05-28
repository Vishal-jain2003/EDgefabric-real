package com.edgefabric.e2e.featuretesting;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;


import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;


class PutCacheFeatureIT {

    private static final String BASE_URL =
            System.getProperty("edgefabric.url", "http://localhost:8080");

    private static final String CACHE_ENDPOINT = "/api/v1/cache";
    private static final String DEFAULT_TENANT = "default";

    private static final String HEADER_TENANT       = "X-Tenant";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String HEADER_TTL          = "X-TTL-MS";

    private static final String ERR_VALIDATION_FAILED = "Validation failed for request parameters.";

    private static final String ERR_EMPTY_VALUE = "Cache value must not be null or empty.";

    @BeforeAll
    static void setup() {
        RestAssured.baseURI = BASE_URL;
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    // ─────────────────────────────────────────────
    // HELPER METHODS
    // ─────────────────────────────────────────────

    private ValidatableResponse putCache(String tenant, String key, byte[] value,
                                         String contentType, String ttl) {
        RequestSpecification req = given()
                .header(HEADER_TENANT, tenant)
                .header(HEADER_CONTENT_TYPE, contentType)
                .pathParam("key", key)
                .body(value);
        if (ttl != null) {
            req = req.header(HEADER_TTL, ttl);
        }
        return req.when().put(CACHE_ENDPOINT + "/{key}").then();
    }

    private ValidatableResponse getCache(String tenant, String key) {
        return given()
                .header(HEADER_TENANT, tenant)
                .pathParam("key", key)
                .when()
                .get(CACHE_ENDPOINT + "/{key}")
                .then();
    }

    private void assertPutSuccess(ValidatableResponse response, String expectedKey) {
        response
                .statusCode(201)
                .body("key", equalTo(expectedKey))
                .body("message", equalTo("Cache entry stored successfully"))
                .body("timestamp", notNullValue());
    }

    private void assertInvalidInput(ValidatableResponse response) {
        response
                .statusCode(400)
                .body("errorCode", equalTo("INVALID_INPUT"))
                .body("message", equalTo(ERR_VALIDATION_FAILED));
    }

    private void assertInvalidInputWithMessage(ValidatableResponse response, String expectedMessage) {
        response
                .statusCode(400)
                .body("errorCode", equalTo("INVALID_INPUT"))
                .body("message", equalTo(expectedMessage));
    }

    // ─────────────────────────────────────────────
    // HAPPY PATH
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("Should successfully store a JSON cache entry (Happy Path)")
    void shouldStoreJsonCacheEntry() {

        String key = "user_session_101";
        byte[] value = "{\"userId\":\"101\",\"role\":\"admin\"}".getBytes();

        assertPutSuccess(
                putCache(DEFAULT_TENANT, key, value, ContentType.JSON.toString(), null),
                key);
    }

    @Test
    @DisplayName("Should successfully store a plain-text cache entry")
    void shouldStorePlainTextCacheEntry() {

        String key = "greeting_msg";
        byte[] value = "Hello, EdgeFabric!".getBytes();

        assertPutSuccess(putCache(DEFAULT_TENANT, key, value, "text/plain", null), key);
    }

    @Test
    @DisplayName("Should successfully store a binary/octet-stream cache entry")
    void shouldStoreBinaryCacheEntry() {

        String key = "binary_blob_42";
        byte[] value = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};

        assertPutSuccess(putCache(DEFAULT_TENANT, key, value, "application/octet-stream", null), key);
    }

    @Test
    @DisplayName("Should overwrite an existing cache entry and serve the new value on GET")
    void shouldOverwriteExistingCacheEntry() {

        String key = "shared_key";

        // Act 1: seed the initial value
        putCache(DEFAULT_TENANT, key, "original_data".getBytes(), "text/plain", null)
                .statusCode(201);

        // Act 2: overwrite — assert the PUT response itself is correct
        assertPutSuccess(
                putCache(DEFAULT_TENANT, key, "updated_data".getBytes(), "text/plain", null),
                key);

        // Assert: GET must return the new value, providing overwrite succeeded
        getCache(DEFAULT_TENANT, key)
                .statusCode(200)
                .body(equalTo("updated_data"));
    }

    @Test
    @DisplayName("Should store entry with a custom TTL within allowed limit")
    void shouldStoreEntryWithCustomTtl() {

        String key = "ttl_key_2000";
        byte[] value = "short_lived_data".getBytes();

        assertPutSuccess(putCache(DEFAULT_TENANT, key, value, "text/plain", "2000"), key);
    }

    @Test
    @DisplayName("Should store entries in isolation per tenant — same key must return different values per tenant")
    void shouldIsolateCacheEntriesPerTenant() {

        // Arrange: unique key scoped to this test
        String key        = "tenant_isolation_key";
        String tenantA    = "org-alpha";
        String tenantB    = "org-beta";
        String valueA     = "tenant_a_data";
        String valueB     = "tenant_b_data";

        // Act: store the same key under two separate tenants
        putCache(tenantA, key, valueA.getBytes(), "text/plain", null).statusCode(201);
        putCache(tenantB, key, valueB.getBytes(), "text/plain", null).statusCode(201);

        // Assert: each tenant retrieves only its own value
        getCache(tenantA, key)
                .statusCode(200)
                .body(equalTo(valueA));

        getCache(tenantB, key)
                .statusCode(200)
                .body(equalTo(valueB));
    }

    @Test
    @DisplayName("Should store an HTML cache entry and preserve Content-Type on GET retrieval")
    void shouldPreserveHtmlContentTypeOnRetrieval() {

        // Arrange: unique key scoped to this test
        String key         = "html_page_cache";
        String htmlData    = "<html><body><h1>EdgeFabric</h1></body></html>";
        String contentType = "text/html";

        // Act: store the HTML entry
        assertPutSuccess(
                putCache(DEFAULT_TENANT, key, htmlData.getBytes(), contentType, null),
                key);

        // Assert: GET must echo the correct Content-Type header and the exact body
        getCache(DEFAULT_TENANT, key)
                .statusCode(200)
                .header(HEADER_CONTENT_TYPE, startsWith(contentType))
                .body(equalTo(htmlData));
    }

    @Test
    @DisplayName("Should accept key with all allowed characters (alphanumeric, colon, hyphen, underscore)")
    void shouldAcceptKeyWithAllAllowedCharacters() {

        String key = "valid-key_name:v1";

        assertPutSuccess(
                putCache(DEFAULT_TENANT, key, "edge_data".getBytes(), "text/plain", null),
                key);
    }


    // ─────────────────────────────────────────────
    // VALIDATION ERRORS (4xx)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("Should return 400 Bad Request when key contains invalid characters")
    void shouldReturn400ForInvalidKeyCharacters() {

        assertInvalidInput(
                putCache(DEFAULT_TENANT, "invalid@key!", "some_data".getBytes(),
                        ContentType.JSON.toString(), null));
    }

    @Test
    @DisplayName("Should return 400 Bad Request when key exceeds 250 characters")
    void shouldReturn400ForOversizedKey() {

        assertInvalidInput(
                putCache(DEFAULT_TENANT, StringUtils.repeat("k", 251), "some_data".getBytes(),
                        ContentType.JSON.toString(), null));
    }

    @Test
    @DisplayName("Should return 400 Bad Request when cache value is null/empty")
    void shouldReturn400ForEmptyValue() {

        assertInvalidInputWithMessage(
                putCache(DEFAULT_TENANT, "empty_value_key", new byte[0],
                        ContentType.JSON.toString(), null),
                ERR_EMPTY_VALUE);
    }


    @ParameterizedTest(name = "Should return 400 for out-of-range X-TTL-MS value: [{0}]")
    @ValueSource(strings = {"0", "-1000"})
    @DisplayName("Should return 400 Bad Request for out-of-range X-TTL-MS values (zero or negative)")
    void shouldReturn400ForOutOfRangeTtlValues(String invalidTtl) {

        // Key is made unique per TTL value so each parameterized run is fully isolated
        String key = "invalid_ttl_key_" + invalidTtl.replaceAll("[^a-zA-Z0-9]", "_");

        assertInvalidInput(
                putCache(DEFAULT_TENANT, key, "some_data".getBytes(), "text/plain", invalidTtl));
    }

    // ─────────────────────────────────────────────
    // BOUNDARY / EDGE CASES
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("Should accept key of exactly 250 characters (upper boundary)")
    void shouldAcceptKeyOfExactly250Characters() {

        String key = StringUtils.repeat("a", 250);

        assertPutSuccess(
                putCache(DEFAULT_TENANT, key, "boundary_value".getBytes(), "text/plain", null),
                key);
    }


    // ─────────────────────────────────────────────
    // PAYLOAD SIZE LIMIT (413)
    // ─────────────────────────────────────────────

    /**
     * WHAT THIS TESTS:
     *   A body larger than 2MB (2 097 152 bytes) must be rejected with 413.
     *
     * HOW IT WORKS INTERNALLY:
     *   CacheEntryValidator.validateData() checks data.length against
     *   cacheProperties.getMaxCacheEntrySizeBytes() (= 2 097 152).
     *   When exceeded → CacheValidationException is thrown
     *   → GlobalExceptionHandler returns 413 PAYLOAD_TOO_LARGE.
     *
     * WHY THIS MATTERS FOR MVP:
     *   Each cache node has 850MB of memory budget. Without this limit,
     *   one oversized PUT could consume most of one node's budget and
     *   trigger LRU eviction of valid data for other tenants.
     *
     * IMPORTANT — error message from CacheEntryValidator.java (source verified):
     *   "Cache value exceeds the allowed maximum cache value."
     *   NOT "File too large" — that message only appears for multipart uploads.
     */
    @Test
    @DisplayName("Should return 413 when body exceeds the 2MB maximum entry size")
    void shouldReturn413WhenBodyExceedsMaxEntrySize() {

        // 2 097 153 bytes — exactly one byte over the 2MB limit
        byte[] oversizedPayload = new byte[2 * 1024 * 1024 + 1];

        putCache(DEFAULT_TENANT, "oversized-payload-key", oversizedPayload, "application/octet-stream", null)
                .statusCode(413)
                .body("errorCode", equalTo("PAYLOAD_TOO_LARGE"))
                .body("message",   equalTo("Cache value exceeds the allowed maximum cache value."));
    }


    // ─────────────────────────────────────────────
    // OVERWRITE — CONTENT-TYPE CHANGE
    // ─────────────────────────────────────────────

    /**
     * WHAT THIS TESTS:
     *   When a key is overwritten with a different Content-Type, the GET response
     *   must reflect the NEW Content-Type and the NEW body — not the old ones.
     *
     * HOW IT WORKS INTERNALLY:
     *   CacheItem stores both byte[] data and String contentType together.
     *   A second PUT creates a new CacheItem with a higher version (nanotime).
     *   Quorum read picks the response with the highest version number.
     *   So both data and contentType from the latest PUT win.
     *
     * WHY THIS MATTERS:
     *   If overwrite stores new bytes but keeps the old Content-Type,
     *   the client receives a JSON body with Content-Type: text/plain.
     *   That is a real data corruption bug — the consumer cannot deserialize.
     *
     * FAILURE INDICATORS:
     *   - GET Content-Type is still "text/plain" → metadata was not updated
     *   - GET body is still "plain_text_value"   → quorum selected old version
     */
    @Test
    @DisplayName("Should update Content-Type when same key is overwritten with a different type")
    void shouldUpdateContentTypeWhenOverwrittenWithDifferentType() {

        String key = "overwrite-content-type-key";

        // Step 1 — initial write as plain text
        putCache(DEFAULT_TENANT, key, "plain_text_value".getBytes(), "text/plain", null)
                .statusCode(201);

        // Step 2 — overwrite the same key with JSON (different content type)
        String jsonValue = "{\"version\":2,\"updated\":true}";
        putCache(DEFAULT_TENANT, key, jsonValue.getBytes(), "application/json", null)
                .statusCode(201)
                .body("key", equalTo(key));

        // Allow write propagation to settle before quorum read (CI can be slow)
        try { Thread.sleep(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        // Step 3 — GET must return the NEW content-type header AND the NEW body
        getCache(DEFAULT_TENANT, key)
                .statusCode(200)
                .header("Content-Type", startsWith("application/json"))
                .body(equalTo(jsonValue));
    }

}

