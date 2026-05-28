package com.edgefabric.e2e.quorum;

import com.edgefabric.e2e.base.BaseE2ETest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ════════════════════════════════════════════════════════════════════
 *  QuorumFailureIT — End-to-End Tests for Quorum Failure Behaviour
 * ════════════════════════════════════════════════════════════════════
 *
 * WHAT THIS TESTS:
 *   The system's response when quorum cannot be met because too few
 *   cache nodes are reachable:
 *     - PUT with only 1 of 3 nodes alive  → 503 QUORUM_NOT_MET (WRITE)
 *     - GET with only 1 of 3 nodes alive  → 503 QUORUM_NOT_MET (READ)
 *     - Normal PUT/GET resumes after nodes are restarted
 *
 * HOW QUORUM WORKS (from QuorumService.java):
 *   N=3 (replication factor), W=2 (write quorum), R=2 (read quorum)
 *   All N nodes are contacted concurrently. quorum.timeout-ms = 5 000 ms.
 *   If fewer than W/R successes before the timeout → QuorumNotMetException
 *   → GlobalExceptionHandler → 503 SERVICE_UNAVAILABLE, errorCode=QUORUM_NOT_MET
 *
 * TIMING NOTE:
 *   Tests that pause nodes will take ~5 s to return 503 because the LB
 *   waits for the full quorum.timeout-ms = 5 000 ms before failing.
 *   Docker pause freezes the container process; the kernel's TCP stack
 *   still accepts connections but no HTTP response is returned.
 *   After 5 s the quorum latch times out → QuorumNotMetException.
 *
 * SETUP REQUIRED:
 *   docker-compose up (all 3 cache nodes must be running before these tests)
 *   Tests are skipped automatically if Docker is not available.
 *
 * CLEANUP:
 *   Each test that pauses nodes restarts them in a finally block and
 *   waits for gossip to converge back to 3 ALIVE members before returning.
 * ════════════════════════════════════════════════════════════════════
 */
@Epic("EdgeFabric Distributed Guarantees")
@Feature("Quorum Failure")
@DisplayName("Quorum Failure E2E Tests")
@Tag("docker-destructive")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QuorumFailureIT extends BaseE2ETest {

    // ── Direct node URL (for gossip convergence checks only) ──────────────
    private static final String NODE_1_URL =
            System.getProperty("node1.url", "http://localhost:8081");

    // ── Container names — Compose V2 default naming convention ───────────
    private static final String NODE_2_CONTAINER =
            System.getProperty("node2.container", "edgefabric-cache-node-2-1");
    private static final String NODE_3_CONTAINER =
            System.getProperty("node3.container", "edgefabric-cache-node-3-1");

    private static final String GOSSIP_PATH        = "/internal/cluster/gossip";
    private static final Duration CONVERGENCE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL       = Duration.ofMillis(500);
    private static final ObjectMapper MAPPER          = new ObjectMapper();


    // ══════════════════════════════════════════════════════════════════════
    // TEST 1 — Write Quorum Failure
    // ══════════════════════════════════════════════════════════════════════

    /**
     * With nodes 2 and 3 paused, only node 1 is reachable.
     * The LB fires writes to all 3 nodes concurrently; only node 1 responds.
     * After quorum.timeout-ms (5 s), W=2 is not met → 503 QUORUM_NOT_MET.
     *
     * Expected response body:
     *   { "errorCode": "QUORUM_NOT_MET",
     *     "details": { "operation": "WRITE", "required": 2, "achieved": 1 } }
     */
    @Test
    @Order(1)
    @Story("Write quorum failure")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("PUT should return 503 QUORUM_NOT_MET when fewer than W=2 nodes are reachable")
    void shouldReturn503WhenWriteQuorumNotMet() throws Exception {
        assumeDockerAvailable();

        pauseContainer(NODE_2_CONTAINER);
        pauseContainer(NODE_3_CONTAINER);

        try {
            // This call blocks for ~5 s while the LB waits for the quorum timeout.
            putCache(DEFAULT_TENANT, "quorum-write-fail-key", "data".getBytes(), "text/plain", null)
                    .statusCode(503)
                    .body("errorCode", equalTo("QUORUM_NOT_MET"))
                    .body("details.operation", equalTo("WRITE"))
                    .body("details.required", is(2));
        } finally {
            restartAndConverge(NODE_2_CONTAINER, NODE_3_CONTAINER);
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // TEST 2 — Read Quorum Failure
    // ══════════════════════════════════════════════════════════════════════

    /**
     * A key is written with all 3 nodes alive, then nodes 2 and 3 are paused.
     * The LB fires reads to all 3 nodes; only node 1 responds.
     * After quorum.timeout-ms (5 s), R=2 is not met → 503 QUORUM_NOT_MET.
     *
     * Pausing happens immediately after the write so gossip has not yet
     * detected the failure — the LB still routes to 3 replicas and must
     * wait for the timeout rather than fast-failing on available-count.
     */
    @Test
    @Order(2)
    @Story("Read quorum failure")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("GET should return 503 QUORUM_NOT_MET when fewer than R=2 nodes are reachable")
    void shouldReturn503WhenReadQuorumNotMet() throws Exception {
        assumeDockerAvailable();

        String key = "quorum-read-fail-key";

        // Seed the key while all 3 nodes are alive
        putCache(DEFAULT_TENANT, key, "quorum-read-data".getBytes(), "text/plain", null)
                .statusCode(201);

        pauseContainer(NODE_2_CONTAINER);
        pauseContainer(NODE_3_CONTAINER);

        try {
            // This call blocks for ~5 s while the LB waits for the quorum timeout.
            getCache(DEFAULT_TENANT, key)
                    .statusCode(503)
                    .body("errorCode", equalTo("QUORUM_NOT_MET"))
                    .body("details.operation", equalTo("READ"))
                    .body("details.required", is(2));
        } finally {
            restartAndConverge(NODE_2_CONTAINER, NODE_3_CONTAINER);
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // TEST 3 — Recovery After Quorum Restored
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Both nodes were restarted in Test 2's finally block, so the cluster
     * is already back to 3 ALIVE members. This test confirms that once
     * quorum is restored, normal PUT and GET operations succeed.
     */
    @Test
    @Order(3)
    @Story("Recovery after quorum failure")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("PUT and GET should succeed normally after quorum is restored")
    void shouldSucceedAfterNodesRestart() {
        assumeDockerAvailable();

        String key = "quorum-recovery-key";

        putCache(DEFAULT_TENANT, key, "recovered-data".getBytes(), "text/plain", null)
                .statusCode(201);

        getCache(DEFAULT_TENANT, key)
                .statusCode(200)
                .body(equalTo("recovered-data"));
    }


    // ══════════════════════════════════════════════════════════════════════
    // Docker helpers
    // ══════════════════════════════════════════════════════════════════════

    private static Boolean dockerAvailable;

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

    private static void assumeDockerAvailable() {
        assumeTrue(isDockerAvailable(),
                "Docker not available on this host — skipping quorum failure tests");
    }

    @Step("Pause Docker container: {container}")
    private void pauseContainer(String container) throws Exception {
        exec("docker", "pause", container);
    }

    /**
     * Restarts each container and waits in two phases:
     *
     * Phase 1 — Gossip convergence: polls node 1's gossip table until
     *   aliveCount == 3 (all nodes are ALIVE in the gossip protocol).
     *
     * Phase 2 — LB ring sync: the LB refreshes its consistent-hash ring
     *   every cluster.sync-interval-ms = 5 000 ms. Even after gossip
     *   converges, the LB ring may still show fewer nodes, causing quorum
     *   writes to return 503. We probe the LB directly with a PUT until
     *   it returns 200, confirming the ring includes enough nodes for W=2.
     */
    @Step("Restart containers and wait for gossip + LB ring to fully converge")
    private void restartAndConverge(String... containers) {
        for (String container : containers) {
            try {
                exec("docker", "restart", container);
            } catch (Exception e) {
                System.err.println("WARNING: failed to restart " + container + ": " + e.getMessage());
            }
        }

        // Phase 1 — wait for gossip to show all 3 nodes as ALIVE
        Awaitility.await("Cluster recovers to 3 ALIVE members after restart")
                .atMost(CONVERGENCE_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> {
                    try {
                        return aliveCount() == 3;
                    } catch (Exception e) {
                        return false;
                    }
                });

        // Phase 2 — wait for the LB's ring to include the restarted nodes.
        // The LB syncs membership every 5 s; a probe PUT returning 200
        // proves quorum (W=2) is achievable again.
        Awaitility.await("LB ring synced — quorum write succeeds after restart")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> {
                    try {
                        int status = HttpClient.newHttpClient().send(
                                HttpRequest.newBuilder()
                                        .uri(URI.create(LB_URL + CACHE_ENDPOINT + "/ring-probe-key"))
                                        .method("PUT", HttpRequest.BodyPublishers.ofByteArray("probe".getBytes()))
                                        .header("X-Tenant", DEFAULT_TENANT)
                                        .header("Content-Type", "text/plain")
                                        .timeout(Duration.ofSeconds(8))
                                        .build(),
                                HttpResponse.BodyHandlers.discarding())
                                .statusCode();
                        return status == 201;
                    } catch (Exception e) {
                        return false;
                    }
                });
    }

    private long aliveCount() throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(NODE_1_URL + GOSSIP_PATH))
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        return MAPPER.readTree(resp.body()).get("aliveCount").asLong();
    }

    private void exec(String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        int exit = p.waitFor();
        if (exit != 0) {
            String out = new String(p.getInputStream().readAllBytes());
            throw new RuntimeException(
                    "Command " + Arrays.toString(command) + " failed (exit " + exit + "): " + out);
        }
    }
}
