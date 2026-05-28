package com.edgefabric.e2e.hashing;

import com.edgefabric.e2e.base.BaseE2ETest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ════════════════════════════════════════════════════════════════════
 *  ConsistentHashingRoutingIT — End-to-End tests for ring behaviour
 * ════════════════════════════════════════════════════════════════════
 *
 * WHAT THIS TESTS:
 *   Everything about the consistent hash ring that is observable
 *   from outside the process:
 *
 *   1. Ring info endpoint shows 3 nodes with correct size after startup
 *   2. Same key is ALWAYS routed to the same primary node (determinism)
 *   3. Different keys distribute across at least 2 distinct nodes
 *   4. /actuator/prometheus exposes hashing_ring_* metrics
 *   5. Routing counters increment as cache requests flow through the LB
 *   6. Node count drops when a cache node leaves the cluster (Docker test)
 *
 * SETUP REQUIRED:
 *   docker-compose up (all 3 cache nodes + load balancer must be running)
 *   Tests 1–5 work without Docker CLI access.
 *   Test 6 requires Docker and is skipped automatically if unavailable.
 *
 * NEW ENDPOINTS USED:
 *   GET /api/v1/internal/ring/info        — ring snapshot
 *   GET /api/v1/internal/ring/route?key=X — routing decision for a key
 *   GET /actuator/prometheus              — Micrometer metrics in text format
 * ════════════════════════════════════════════════════════════════════
 */
@Epic("Consistent Hashing")
@Feature("Ring Routing Behaviour")
@DisplayName("Consistent Hashing — Routing E2E Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsistentHashingRoutingIT extends BaseE2ETest {

    private static final String RING_INFO_PATH  = "/api/v1/internal/ring/info";
    private static final String RING_ROUTE_PATH = "/api/v1/internal/ring/route";
    private static final String PROMETHEUS_PATH = "/actuator/prometheus";

    private static final String NODE_3_CONTAINER =
            System.getProperty("node3.container", "edgefabric-cache-node-3-1");

    private static final int    VIRTUAL_NODES_PER_NODE = 150;
    private static final int    EXPECTED_NODE_COUNT    = 4;
    private static final int    EXPECTED_RING_SIZE     = EXPECTED_NODE_COUNT * VIRTUAL_NODES_PER_NODE;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ════════════════════════════════════════════════════════════════════
    // TEST 1 — Ring info shows 3 nodes after cluster startup
    // ════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @Story("Ring state visibility")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Ring info endpoint shows 4 nodes and correct virtual ring size on startup")
    void ringInfoShowsAllThreeNodesOnStartup() throws Exception {
        // The LB bootstraps via DNS and syncs membership on a schedule (cluster.sync-interval-ms=5000).
        // On CI agents under load, the ring may lag by up to one sync cycle after Docker Compose
        // startup. Poll up to 60s (12 × 5s cycles) to give the ring time to converge to all
        // EXPECTED_NODE_COUNT nodes before asserting. GossipCycleIT is excluded from this run
        // so no node restarts interfere with convergence.
        Awaitility.await("Ring converges to " + EXPECTED_NODE_COUNT + " nodes")
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> getRingNodeCount() == EXPECTED_NODE_COUNT);

        Response resp = given()
                .baseUri(LB_URL)
                .when()
                .get(RING_INFO_PATH)
                .then()
                .statusCode(200)
                .extract().response();

        JsonNode body = MAPPER.readTree(resp.asString());

        assertThat(body.get("nodeCount").asInt())
                .as("Ring should have %d physical nodes", EXPECTED_NODE_COUNT)
                .isEqualTo(EXPECTED_NODE_COUNT);

        assertThat(body.get("ringSize").asInt())
                .as("Ring size should be nodeCount × virtualNodesPerNode = %d", EXPECTED_RING_SIZE)
                .isEqualTo(EXPECTED_RING_SIZE);

        assertThat(body.get("virtualNodesPerNode").asInt())
                .isEqualTo(VIRTUAL_NODES_PER_NODE);

        assertThat(body.get("activeNodes").size())
                .as("activeNodes list should have %d entries", EXPECTED_NODE_COUNT)
                .isEqualTo(EXPECTED_NODE_COUNT);
    }

    // ════════════════════════════════════════════════════════════════════
    // TEST 2 — Routing is deterministic: same key → same primary node
    // ════════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @Story("Routing determinism")
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("Same key always routes to the same primary node (determinism)")
    void sameKeyAlwaysRoutesToSamePrimaryNode() throws Exception {
        String testKey = "e2e-determinism-test-key";

        String firstNodeId = getPrimaryNodeId(testKey);
        assertThat(firstNodeId).as("Primary node must not be null").isNotBlank();

        // Call 20 more times — every result must match the first
        for (int i = 1; i <= 20; i++) {
            String nodeId = getPrimaryNodeId(testKey);
            assertThat(nodeId)
                    .as("Call #%d: key '%s' should always route to '%s' but got '%s'",
                            i, testKey, firstNodeId, nodeId)
                    .isEqualTo(firstNodeId);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // TEST 3 — Different keys distribute across multiple nodes
    // ════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @Story("Key distribution")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("200 different keys route to at least 2 distinct nodes (ring is distributing)")
    void differentKeysDistributeAcrossAtLeastTwoNodes() throws Exception {
        Set<String> distinctNodes = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            String nodeId = getPrimaryNodeId("dist-key-" + i);
            if (nodeId != null) {
                distinctNodes.add(nodeId);
            }
        }

        assertThat(distinctNodes)
                .as("200 keys must distribute to at least 2 distinct nodes, got: %s", distinctNodes)
                .hasSizeGreaterThanOrEqualTo(2);
    }

    // ════════════════════════════════════════════════════════════════════
    // TEST 4 — /actuator/prometheus exposes ring metrics
    // ════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @Story("Metrics observability")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Prometheus endpoint exposes hashing_ring_node_count with value 4")
    void prometheusEndpointExposesRingMetrics() {
        // Allow one extra sync cycle for the ring to be fully converged before scraping metrics.
        // The ring syncs every 5s — Awaitility ensures the metric already reflects EXPECTED_NODE_COUNT
        // before we make the hard assertion below.
        Awaitility.await("Ring metric reflects " + EXPECTED_NODE_COUNT + " nodes before Prometheus scrape")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> extractPrometheusGaugeValue(
                        given().baseUri(LB_URL).get(PROMETHEUS_PATH).asString(),
                        "hashing_ring_node_count") == EXPECTED_NODE_COUNT);

        String metrics = given()
                .baseUri(LB_URL)
                .when()
                .get(PROMETHEUS_PATH)
                .then()
                .statusCode(200)
                .extract().asString();

        assertThat(metrics)
                .as("Prometheus should expose hashing_ring_node_count")
                .contains("hashing_ring_node_count")
                .as("Prometheus should expose hashing_ring_size")
                .contains("hashing_ring_size")
                .as("Prometheus should expose hashing_ring_route_total")
                .contains("hashing_ring_route_total")
                .as("Prometheus should expose hashing_ring_node_added_total")
                .contains("hashing_ring_node_added_total");

        // Extract actual value of hashing_ring_node_count and verify it equals EXPECTED_NODE_COUNT (4)
        double nodeCountValue = extractPrometheusGaugeValue(metrics, "hashing_ring_node_count");
        assertThat(nodeCountValue)
                .as("hashing_ring_node_count should be %d", EXPECTED_NODE_COUNT)
                .isEqualTo(EXPECTED_NODE_COUNT);

        // Verify ring size metric value
        double ringSizeValue = extractPrometheusGaugeValue(metrics, "hashing_ring_size");
        assertThat(ringSizeValue)
                .as("hashing_ring_size should be %d", EXPECTED_RING_SIZE)
                .isEqualTo(EXPECTED_RING_SIZE);
    }

    // ════════════════════════════════════════════════════════════════════
    // TEST 5 — Routing counters increment with cache requests
    // ════════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @Story("Metrics increment correctness")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("Routing counters increment as cache PUT requests flow through the LB")
    void routingCountersIncrementWithCacheRequests() {
        // Capture baseline counter value BEFORE making requests
        double before = scrapeRouteTotal();

        // Send extra requests as buffer — in a live cluster some may transiently fail
        // before reaching the routing code. We assert at least 10 were routed.
        int totalRequests = 20;
        int minExpected   = 10;
        for (int i = 0; i < totalRequests; i++) {
            putCache(DEFAULT_TENANT, "counter-test-key-" + i,
                    ("value-" + i).getBytes(), "text/plain");
        }

        // Capture counter AFTER
        double after = scrapeRouteTotal();

        assertThat(after - before)
                .as("hashing_ring_route_total should increase by at least %d after %d PUT requests",
                        minExpected, totalRequests)
                .isGreaterThanOrEqualTo(minExpected);
    }

    // ════════════════════════════════════════════════════════════════════
    // TEST 6 — Node count drops when a node leaves (Docker-dependent)
    // ════════════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @Tag("docker-destructive")
    @Story("Dynamic ring membership")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Ring node count drops to 3 when cache-node-3 is paused, recovers to 4 after unpause")
    void nodeCountDropsWhenNodePausedAndRecoversAfterUnpause() throws Exception {
        assumeTrue(isDockerAvailable(),
                "Docker CLI not available — skipping dynamic membership test");

        try {
            // Pause node 3
            exec("docker", "pause", NODE_3_CONTAINER);

            // The LB syncs cluster membership every sync-interval-ms = 5000 ms.
            // Wait up to 20s for the ring to reflect the missing node.
            Awaitility.await("Ring node count drops to 3 after node 3 is paused")
                    .atMost(Duration.ofSeconds(20))
                    .pollInterval(Duration.ofSeconds(1))
                    .until(() -> getRingNodeCount() == 3);

            // Verify ring size also shrank
            int ringInfo = getRingNodeCount();
            assertThat(ringInfo).isEqualTo(3);

            // Also verify via metrics
            double metricNodeCount = extractPrometheusGaugeValue(
                    given().baseUri(LB_URL).get(PROMETHEUS_PATH).asString(),
                    "hashing_ring_node_count");
            assertThat(metricNodeCount).isEqualTo(3.0);

        } finally {
            // Always restore — unpause node 3
            exec("docker", "unpause", NODE_3_CONTAINER);

            // Wait for ring to recover to 4 nodes
            Awaitility.await("Ring recovers to 4 nodes after node 3 is unpaused")
                    .atMost(Duration.ofSeconds(20))
                    .pollInterval(Duration.ofSeconds(1))
                    .until(() -> getRingNodeCount() == 4);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ════════════════════════════════════════════════════════════════════

    private String getPrimaryNodeId(String key) throws Exception {
        Response resp = given()
                .baseUri(LB_URL)
                .queryParam("key", key)
                .when()
                .get(RING_ROUTE_PATH)
                .then()
                .statusCode(200)
                .extract().response();

        JsonNode body = MAPPER.readTree(resp.asString());
        JsonNode primaryNode = body.get("primaryNode");
        if (primaryNode == null || primaryNode.isNull()) return null;
        return primaryNode.get("nodeId").asText();
    }

    private int getRingNodeCount() throws Exception {
        Response resp = given()
                .baseUri(LB_URL)
                .when()
                .get(RING_INFO_PATH)
                .then()
                .statusCode(200)
                .extract().response();
        return MAPPER.readTree(resp.asString()).get("nodeCount").asInt();
    }

    /**
     * Scrapes /actuator/prometheus and returns the current value of
     * hashing_ring_route_total{operation="replicas"}.
     *
     * PUT/GET cache requests go through QuorumService → cacheRouter.routeToReplicas()
     * → ring.getNodes(), which increments the "replicas" counter.
     * The "single" counter only fires when /ring/route is called directly.
     */
    private double scrapeRouteTotal() {
        String metrics = given()
                .baseUri(LB_URL)
                .when()
                .get(PROMETHEUS_PATH)
                .then()
                .statusCode(200)
                .extract().asString();

        // Match: hashing_ring_route_total{...operation="replicas"...} 42.0
        Pattern p = Pattern.compile(
                "hashing_ring_route_total\\{[^}]*operation=\"replicas\"[^}]*\\}\\s+([\\d.E+]+)");
        Matcher m = p.matcher(metrics);
        if (m.find()) {
            return Double.parseDouble(m.group(1));
        }
        return 0.0;
    }

    /**
     * Extracts the value of a Prometheus gauge metric by name.
     * Matches lines like: metric_name{...application="loadbalancer"...} 3.0
     */
    private double extractPrometheusGaugeValue(String metrics, String metricName) {
        Pattern p = Pattern.compile(
                metricName + "\\{[^}]*\\}\\s+([\\d.E+]+)");
        Matcher m = p.matcher(metrics);
        if (m.find()) {
            return Double.parseDouble(m.group(1));
        }
        // Also try without labels (e.g. metric_name 3.0)
        Pattern plain = Pattern.compile("^" + metricName + "\\s+([\\d.E+]+)", Pattern.MULTILINE);
        Matcher mp = plain.matcher(metrics);
        if (mp.find()) {
            return Double.parseDouble(mp.group(1));
        }
        return -1.0;
    }

    private static Boolean dockerAvailable = null;

    private static boolean isDockerAvailable() {
        if (dockerAvailable == null) {
            try {
                dockerAvailable = new ProcessBuilder("docker", "info")
                        .redirectErrorStream(true)
                        .start()
                        .waitFor() == 0;
            } catch (Exception e) {
                dockerAvailable = false;
            }
        }
        return dockerAvailable;
    }

    private static void exec(String... cmd) throws Exception {
        int exitCode = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
                .waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed (exit " + exitCode + "): " + String.join(" ", cmd));
        }
    }
}
