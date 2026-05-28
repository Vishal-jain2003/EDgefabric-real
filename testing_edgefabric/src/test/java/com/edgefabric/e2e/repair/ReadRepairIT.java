package com.edgefabric.e2e.repair;

import com.edgefabric.e2e.base.BaseE2ETest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ════════════════════════════════════════════════════════════════════
 *  ReadRepairIT — End-to-End Test for Read Repair
 * ════════════════════════════════════════════════════════════════════
 *
 * WHAT THIS TESTS:
 *   ReadRepairService (loadbalancer) detects stale replicas during a
 *   quorum read and asynchronously re-writes the winning version to
 *   any node that is behind. This test proves that mechanism works
 *   end-to-end by:
 *     1. Creating a stale replica (node 3 misses a write)
 *     2. Triggering a quorum read via the LB
 *     3. Polling node 3's internal cache endpoint directly until
 *        it holds the up-to-date value — proving repair fired
 *
 * HOW STALE STATE IS CREATED (docker stop / docker start):
 *   Node 3 is stopped (SIGKILL via docker stop -t 0). Its in-memory
 *   cache is cleared. A new value (V2) is written via the LB quorum
 *   (W=2 → nodes 1 and 2). Node 3 is then started again fresh — it
 *   has no data for the test key.
 *
 *   Using stop/start (rather than pause) avoids the race where an
 *   outstanding CompletableFuture write reaches the container after
 *   it is unpaused. With stop, the WebClient call to node 3 fails
 *   fast (connection refused) — no pending write lingers.
 *
 * HOW READ REPAIR IS TRIGGERED:
 *   QuorumService fires reads to ALL N=3 replicas concurrently (not
 *   just R=2). Node 3 responds with 404 (key absent → version=-1).
 *   The winner is V2 from nodes 1 and 2 (higher version). Node 3's
 *   version(-1) < winner version → ReadRepairService fires an async
 *   forwardPutRequest to node 3 with the winner's data.
 *
 * HOW VERIFICATION WORKS:
 *   Node 3's internal endpoint is polled directly (bypassing the LB):
 *     GET http://localhost:8083/api/v1/internal/cache/{tenant}:{key}
 *   The internal key format is "{tenant}:{key}" — confirmed from
 *   CacheGatewayService: tenantKey = tenant + ":" + key.
 *   Each Awaitility iteration also triggers a fresh LB GET to ensure
 *   read repair keeps firing until node 3 is fully updated.
 *
 * PROTOCOL TIMINGS:
 *   - docker stop -t 0  : immediate (SIGKILL)
 *   - Spring Boot start : ~5–10 s
 *   - ClusterJoin       : ~1 s
 *   - Gossip convergence: ~5–10 s (one gossip round = 5 s)
 *   - LB ring sync      : ~5 s (cluster.sync-interval-ms = 5 000)
 *   - Read repair async : < 1 s (local Docker network)
 *   Total convergence budget: 60 s (generous for CI)
 *
 * SETUP REQUIRED:
 *   docker-compose up (all 3 cache nodes running)
 *   Test is skipped automatically if Docker is not available.
 * ════════════════════════════════════════════════════════════════════
 */
@Epic("EdgeFabric Distributed Guarantees")
@Feature("Read Repair")
@DisplayName("Read Repair E2E Tests")
@Tag("docker-destructive")
class ReadRepairIT extends BaseE2ETest {

    // ── Direct node endpoints (bypass LB) ────────────────────────────────
    private static final String NODE_1_URL =
            System.getProperty("node1.url", "http://localhost:8081");
    private static final String NODE_3_URL =
            System.getProperty("node3.url", "http://localhost:8083");
    private static final String NODE_3_CONTAINER =
            System.getProperty("node3.container", "edgefabric-cache-node-3-1");

    private static final String GOSSIP_PATH    = "/internal/cluster/gossip";
    private static final String INTERNAL_CACHE = "/api/v1/internal/cache/";

    private static final Duration CONVERGENCE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration REPAIR_TIMEOUT       = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL        = Duration.ofSeconds(2);

    private static final ObjectMapper MAPPER = new ObjectMapper();


    // ══════════════════════════════════════════════════════════════════════
    // THE READ REPAIR TEST
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @Story("Read repair after stale replica")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Stale replica should be repaired by ReadRepairService on the next quorum read")
    @Description("""
            Step-by-step proof of read repair:
            1. Write V1 to all 3 nodes via quorum PUT.
            2. Stop node 3 — its in-memory cache is cleared.
            3. Overwrite the key with V2 via quorum PUT (nodes 1 and 2 only).
            4. Start node 3 fresh — it has no data for this key.
            5. Wait for cluster to converge (all 3 ALIVE).
            6. Assert node 3 does NOT have V2 yet (stale state confirmed).
            7. Trigger GET via LB — QuorumService contacts all 3 nodes,
               detects node 3 is stale (version=-1 < winner V2 version),
               ReadRepairService fires async PUT to node 3.
            8. Poll node 3's internal endpoint until it returns V2.
            """)
    void shouldRepairStaleReplicaOnQuorumRead() throws Exception {
        assumeDockerAvailable();

        String key         = "read-repair-test-key";
        // Internal key format used by CacheGatewayService: tenant + ":" + key
        String internalKey = DEFAULT_TENANT + ":" + key;
        byte[] valueV1     = "version-1-data".getBytes();
        byte[] valueV2     = "version-2-data".getBytes();
        String longTtl     = "300000"; // 5 minutes - enough time for test to complete

        // ── Step 1: Seed all 3 nodes with V1 ─────────────────────────────
        putCache(DEFAULT_TENANT, key, valueV1, "text/plain", longTtl)
                .statusCode(201);

        // ── Step 2: Stop node 3 (in-memory data is wiped on restart) ─────
        stopContainer(NODE_3_CONTAINER);

        try {
            // ── Step 3: Overwrite with V2 — only nodes 1 and 2 receive it ─
            // Quorum W=2 is satisfied without node 3.
            // The WebClient call to node 3 fails fast (connection refused),
            // so no pending write lingers after we start node 3 again.
            putCache(DEFAULT_TENANT, key, valueV2, "text/plain", longTtl)
                    .statusCode(201);

            // ── Step 4: Start node 3 fresh ───────────────────────────────
            startContainer(NODE_3_CONTAINER);

            // ── Step 5: Wait for full gossip convergence (all 3 ALIVE) ───
            // Also allows time for the LB to sync its consistent-hash ring
            // to include node 3 (cluster.sync-interval-ms = 5 000 ms).
            waitForConvergence();

            // ── Step 6: Confirm node 3 does not yet have V2 ──────────────
            // Node 3 started fresh — it has no data for this key.
            assertThat(fetchStatusFromNode3(internalKey))
                    .as("Node 3 should be in a stale state (key absent) before read repair fires")
                    .isEqualTo(404);

            // ── Step 7 + 8: Trigger GETs and poll until node 3 is repaired
            // Each Awaitility iteration:
            //   a) Sends a GET via the LB  → QuorumService contacts all 3 nodes,
            //      detects node 3 as stale → ReadRepairService fires async repair
            //   b) Reads node 3 directly  → passes once V2 has been written
            Awaitility.await("Node 3 is repaired with V2 by ReadRepairService")
                    .atMost(REPAIR_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(() -> {
                        // Fire a quorum read — this triggers read repair if node 3
                        // is now in the LB's ring and still holds a stale version.
                        getCache(DEFAULT_TENANT, key).statusCode(200);

                        // Verify node 3 now holds V2 via its internal endpoint.
                        // Throws AssertionError (retried by Awaitility) if still 404.
                        byte[] node3Data = fetchFromNode3(internalKey);
                        assertThat(node3Data)
                                .as("Node 3 should hold V2 after read repair")
                                .isEqualTo(valueV2);
                    });

        } finally {
            // Ensure cluster is left in a clean state regardless of test outcome
            try {
                startContainer(NODE_3_CONTAINER);
            } catch (Exception ignored) {
                // already running — docker start is a no-op on a running container
            }
            waitForConvergence();
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // Node-direct HTTP helpers (bypass Load Balancer)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns the HTTP status code from a direct GET to node 3's internal
     * cache endpoint without throwing. The caller interprets the status.
     *
     * URL: http://localhost:8083/api/v1/internal/cache/{internalKey}
     * The colon in the internalKey ("default:my-key") is valid in a URL
     * path segment and is accepted by Spring's @PathVariable.
     */
    @Step("Check HTTP status of {internalKey} on node 3 (direct)")
    private int fetchStatusFromNode3(String internalKey) throws Exception {
        HttpResponse<Void> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(NODE_3_URL + INTERNAL_CACHE + internalKey))
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        return resp.statusCode();
    }

    /**
     * Returns the raw body bytes from node 3's internal cache endpoint.
     * Throws AssertionError on any non-200 response so Awaitility retries
     * rather than failing the test immediately.
     */
    @Step("Read {internalKey} directly from node 3 internal endpoint")
    private byte[] fetchFromNode3(String internalKey) throws Exception {
        HttpResponse<byte[]> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(NODE_3_URL + INTERNAL_CACHE + internalKey))
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());

        if (resp.statusCode() != 200) {
            throw new AssertionError(
                    "Node 3 returned HTTP " + resp.statusCode() + " for key [" + internalKey
                    + "] — read repair has not yet propagated");
        }
        return resp.body();
    }


    // ══════════════════════════════════════════════════════════════════════
    // Cluster convergence helper
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Polls node 1's gossip table until aliveCount == 3, meaning all three
     * nodes are fully converged. Transient IOExceptions (e.g., node 1 still
     * starting) are treated as "not yet" rather than hard failures.
     */
    @Step("Wait for all 3 nodes to converge to ALIVE status via gossip")
    private void waitForConvergence() {
        Awaitility.await("Cluster converges to 3 ALIVE members")
                .atMost(CONVERGENCE_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> {
                    try {
                        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                                HttpRequest.newBuilder()
                                        .uri(URI.create(NODE_1_URL + GOSSIP_PATH))
                                        .GET()
                                        .timeout(Duration.ofSeconds(5))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                        return resp.statusCode() == 200
                                && MAPPER.readTree(resp.body()).get("aliveCount").asLong() == 3;
                    } catch (Exception e) {
                        return false;
                    }
                });
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
                "Docker not available on this host — skipping read repair test");
    }

    /**
     * Stops a container immediately (SIGKILL via -t 0).
     * Using stop rather than pause ensures the in-memory cache is cleared
     * and that no pending WebClient writes can reach the container later.
     */
    @Step("Stop Docker container: {container} (SIGKILL)")
    private void stopContainer(String container) throws Exception {
        exec("docker", "stop", "-t", "0", container);
    }

    @Step("Start Docker container: {container}")
    private void startContainer(String container) throws Exception {
        exec("docker", "start", container);
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
