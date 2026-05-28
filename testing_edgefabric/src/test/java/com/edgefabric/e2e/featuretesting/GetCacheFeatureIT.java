package com.edgefabric.e2e.featuretesting;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

class GetCacheFeatureIT {

    private static final String BASE_URL =
            System.getProperty("edgefabric.url", "http://localhost:8080");
    private static final String CACHE_ENDPOINT = "/api/v1/cache";
    private static final String DEFAULT_TENANT = "default";

    @BeforeAll
    static void setup() {
        RestAssured.baseURI = BASE_URL;
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    @Test
    @DisplayName("Should return 404 on first GET when cache is empty")
    void shouldReturn404WhenCacheIsEmptyOnFirstGet() {
        given()
                .header("X-Tenant", DEFAULT_TENANT)
                .pathParam("key", "first_time_missing_key")
                .when()
                .get(CACHE_ENDPOINT + "/{key}")
                .then()
                .statusCode(404)
                .body("errorCode", equalTo("NOT_FOUND"));
    }

    @Test
    @DisplayName("Should return 404 for unknown key even when cache contains other entries")
    void shouldReturn404ForUnknownKey() {
        insertCacheData(DEFAULT_TENANT, "existing_key_1", "some_data", ContentType.JSON.toString());

        given()
                .header("X-Tenant", DEFAULT_TENANT)
                .pathParam("key", "ghost_key_000")
                .when()
                .get(CACHE_ENDPOINT + "/{key}")
                .then()
                .statusCode(404)
                .body("errorCode", equalTo("NOT_FOUND"));
    }

    @Test
    @DisplayName("Should successfully retrieve an existing cache entry")
    void shouldRetrieveExistingCacheEntry() {
        String testKey = "user_profile_123";
        String testValue = "cached_user_data";

        insertCacheData(DEFAULT_TENANT, testKey, testValue, ContentType.JSON.toString());

        Response response = given()
                .header("X-Tenant", DEFAULT_TENANT)
                .pathParam("key", testKey)
                .when()
                .get(CACHE_ENDPOINT + "/{key}")
                .then()
                .statusCode(200)
                .header("Content-Type", startsWith(ContentType.JSON.toString()))
                .extract()
                .response();

        assertThat(response.jsonPath().getString("value")).isEqualTo(testValue);
        assertThat(response.jsonPath().getString("key")).isEqualTo(testKey);
    }

    @Test
    @DisplayName("Should maintain Unicode data integrity")
    void shouldMaintainUnicodeDataIntegrity() {
        String testKey = "unicode_key_1";
        String testValue = "ã“ã‚“ã«ã¡ã¯ ä¸–ç•Œ ðŸŒðŸš€";

        insertCacheData(DEFAULT_TENANT, testKey, testValue, ContentType.JSON.toString());

        given()
                .header("X-Tenant", DEFAULT_TENANT)
                .pathParam("key", testKey)
                .when()
                .get(CACHE_ENDPOINT + "/{key}")
                .then()
                .statusCode(200)
                .body("value", equalTo(testValue));
    }

    @Test
    @DisplayName("Should enforce multi-tenant isolation")
    void shouldEnforceTenantIsolation() {
        String testKey = "financial_report_q1";
        String testValue = "confidential_data";
        String tenantA = "tenant-alpha";
        String tenantB = "tenant-beta";

        insertCacheData(tenantA, testKey, testValue, ContentType.JSON.toString());

        given()
                .header("X-Tenant", tenantB)
                .pathParam("key", testKey)
                .when()
                .get(CACHE_ENDPOINT + "/{key}")
                .then()
                .statusCode(404)
                .body("errorCode", equalTo("NOT_FOUND"))
                .body("message", notNullValue());
    }

    @Test
    @DisplayName("Should preserve and return content type")
    void shouldPreserveContentType() {
        String testKey = "html_fragment_99";
        String htmlData = "<h1>Hello EdgeFabric</h1>";
        String contentType = "text/html";

        given()
                .header("X-Tenant", DEFAULT_TENANT)
                .header("Content-Type", contentType)
                .pathParam("key", testKey)
                .body(htmlData.getBytes())
                .when()
                .put(CACHE_ENDPOINT + "/{key}")
                .then()
                .statusCode(201);

        given()
                .header("X-Tenant", DEFAULT_TENANT)
                .pathParam("key", testKey)
                .when()
                .get(CACHE_ENDPOINT + "/{key}")
                .then()
                .statusCode(200)
                .header("Content-Type", startsWith(contentType))
                .body(equalTo(htmlData));
    }

    @Test
    @DisplayName("Should return 404 for empty key route")
    void shouldHandleEmptyKeyGracefully() {
        given()
                .header("X-Tenant", DEFAULT_TENANT)
                .when()
                .get(CACHE_ENDPOINT + "/")
                .then()
                .statusCode(404)
                .body("errorCode", equalTo("NOT_FOUND"));
    }

    private void insertCacheData(String tenant, String key, String value, String contentType) {
        String payload = String.format(
                "{ \"key\": \"%s\", \"value\": \"%s\" }",
                key,
                value
        );

        given()
                .header("X-Tenant", tenant)
                .header("Content-Type", contentType)
                .pathParam("key", key)
                .body(payload)
                .when()
                .put(CACHE_ENDPOINT + "/{key}")
                .then()
                .statusCode(201);
    }
}