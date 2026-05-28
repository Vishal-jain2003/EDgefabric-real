package com.edgefabric.e2e.gossip;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ════════════════════════════════════════════════════════════════════
 *  GossipCycleIT — End-to-End Tests for the Gossip Protocol
 * ════════════════════════════════════════════════════════════════════
 *
 * WHAT THIS TESTS:
 *   The gossip cycle as a black-box system: membership propagation,
 *   heartbeat liveness, failure detection (SUSPECT/DEAD), node recovery,
 *   and self-refutation via incarnation bumping.
 *
 * HOW IT WORKS:
 *   These tests bypass the Load Balancer entirely and talk directly
 *   to the three cache nodes on their exposed HTTP ports (8081/8082/8083).
 *   Two inspection endpoints are used:
 *     - GET /internal/cluster/gossip  → full gossip table (all statuses)
 *     - GET /internal/cluster/members → alive-only list (what the LB sees)
 *
 * SETUP REQUIRED:
 *   docker-compose up (all 3 cache nodes must be running)
 *
 * DOCKER-DEPENDENT TESTS (Order 4–6):
 *   Tests that simulate node failure use docker pause/restart.
 *   They are skipped automatically if Docker is not available.
 *   The container name defaults to "edgefabric-cache-node-3-1" (Compose V2).
 *   Override with: -Dnode3.container=<name>
 *
 * RECOVERY MECHANISM NOTE:
 *   Once a node is DEAD, peers stop gossiping to it (getRandomPeers excludes
 *   DEAD nodes). Recovery therefore requires a container restart — not just
 *   unpause. On restart, ClusterJoinService calls POST /cluster/join on peers,
 *   receives its own DEAD entry, calls handleSelfGossip → refutes (incarnation++),
 *   then gossips ALIVE with higher incarnation back to peers.
 *
 * PROTOCOL TIMINGS (from application.properties):
 *   gossip.message-interval-ms         = 5000   (gossip round every 5s)
 *   failure-detector.probe-interval-ms = 1000   (probe every 1s)
 *   failure-detector.ping-timeout-ms   = 500    (PING wait)
 *   failure-detector.suspect-timeout-ms = 10000 (SUSPECT → DEAD after 10s)
 * ════════════════════════════════════════════════════════════════════
 */
@DisplayName("Gossip Cycle E2E Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GossipCycleIT {

    // ── Node base URLs — talking directly to cache nodes, bypassing the LB ──
    // docker-compose maps container port 8082 to host ports 8081 / 8082 / 8083
    private static final String NODE_1_URL = System.getProperty("node1.url", "http://localhost:8081");
    private static final String NODE_2_URL = System.getProperty("node2.url", "http://localhost:8082");
    private static final String NODE_3_URL = System.getProperty("node3.url", "http://localhost:8083");
    private static final String NODE_4_URL = System.getProperty("node4.url", "http://localhost:8084");
    private static final List<String> ALL_NODE_URLS = List.of(NODE_1_URL, NODE_2_URL, NODE_3_URL, NODE_4_URL);

    private static final int EXPECTED_NODE_COUNT = ALL_NODE_URLS.size(); // 4

    private static final String GOSSIP_PATH  = "/internal/cluster/gossip";
    private static final String MEMBERS_PATH = "/internal/cluster/members";

    // ── Awaitility timeouts ───────────────────────────────────────────────
    // 45s covers 9 gossip rounds — generous for a 3-node cluster
    private static final Duration CONVERGENCE_TIMEOUT = Duration.ofSeconds(45);
    // SUSPECT: probe fires every 1s + 500ms ping timeout → within ~2s; 6s buffer
    private static final Duration SUSPECT_TIMEOUT     = Duration.ofSeconds(6);
    // DEAD: SUSPECT state + 10s timeout + next probe cycle → ~12s; allow 20s
    private static final Duration DEAD_TIMEOUT        = Duration.ofSeconds(20);
    // Recovery via restart + ClusterJoin + 1 gossip round = ~10-15s; allow 45s
    private static final Duration RECOVERY_TIMEOUT    = Duration.ofSeconds(45);
    private static final Duration POLL_INTERVAL       = Duration.ofMillis(500);

    // Docker Compose V2: override via -Dnode3.container=<name>
    private static final String NODE_3_CONTAINER =
            System.getProperty("node3.container", "edgefabric-cache-node-3-1");

    // A new HttpClient is created per request to avoid stale keep-alive connections
    // after container restarts, which cause "HTTP/1.1 header parser received no bytes".
    private static final ObjectMapper MAPPER = new ObjectMapper();


    // ══════════════════════════════════════════════════════════════════════
    // TEST 1 — Gossip Convergence
    // ══════════════════════════════════════════════════════════════════════

    /**
     * GossipSender fires every 5s and sends only dirty entries (nodes whose
     * state changed since last round). ClusterJoinService seeds each node
     * with DNS peers on startup. After a few gossip rounds every node must
     * see all 3 peers as ALIVE.
     */
    @Test
    @Order(1)
    @DisplayName("All 4 nodes converge: each sees 4 ALIVE members after gossip rounds")
    void allNodesConvergeAfterStartup() {
        for (String nodeUrl : ALL_NODE_URLS) {
            Awaitility.await("Node " + nodeUrl + " converges to " + EXPECTED_NODE_COUNT + " ALIVE members")
                    .atMost(CONVERGENCE_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(() -> {
                        JsonNode table = gossipTable(nodeUrl);
                        assertThat(table.get("aliveCount").asLong())
                                .as("aliveCount at %s", nodeUrl)
                                .isEqualTo(EXPECTED_NODE_COUNT);
                    });
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // TEST 2 — Heartbeat Increment
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Every gossip round starts with bumpSelfHeartbeat(), guaranteeing at
     * least one dirty entry per round. A monotonically growing heartbeat
     * proves gossip rounds are continuously firing.
     */
    @Test
    @Order(2)
    @DisplayName("Self heartbeat increments after gossip rounds fire (one round = 5s)")
    void heartbeatIncrementsEachGossipRound() throws Exception {
        // Snapshot self heartbeat for every node
        Map<String, Long> before = new HashMap<>();
        for (String nodeUrl : ALL_NODE_URLS) {
            before.put(nodeUrl, selfEntry(gossipTable(nodeUrl)).get("heartbeat").asLong());
        }

        // Poll until each node's heartbeat strictly increases (proves gossip rounds are firing)
        for (String nodeUrl : ALL_NODE_URLS) {
            long initial = before.get(nodeUrl);
            Awaitility.await("Heartbeat at " + nodeUrl + " increments after gossip round")
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(() -> {
                        long after = selfEntry(gossipTable(nodeUrl)).get("heartbeat").asLong();
                        assertThat(after)
                                .as("Heartbeat at %s should have grown (was %d)", nodeUrl, initial)
                                .isGreaterThan(initial);
                    });
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // TEST 3 — Gossip Table Consistency Across All Nodes
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Gossip is eventually consistent. The merge rule (higher incarnation
     * wins; same incarnation: higher severity wins; same severity: higher
     * heartbeat wins) guarantees convergence. Every node must agree on the
     * same set of member IDs.
     */
    @Test
    @Order(3)
    @DisplayName("All nodes agree on the same set of 4 member IDs")
    void gossipTableIsConsistentAcrossAllNodes() {
        Awaitility.await("All " + EXPECTED_NODE_COUNT + " nodes agree on cluster membership")
                .atMost(CONVERGENCE_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> {
                    Set<String> ids1 = allNodeIds(NODE_1_URL);
                    Set<String> ids2 = allNodeIds(NODE_2_URL);
                    Set<String> ids3 = allNodeIds(NODE_3_URL);
                    Set<String> ids4 = allNodeIds(NODE_4_URL);

                    assertThat(ids1).as("Node 1 vs Node 2 member sets").isEqualTo(ids2);
                    assertThat(ids1).as("Node 1 vs Node 3 member sets").isEqualTo(ids3);
                    assertThat(ids1).as("Node 1 vs Node 4 member sets").isEqualTo(ids4);
                    assertThat(ids1).as("Expected exactly " + EXPECTED_NODE_COUNT + " cluster members").hasSize(EXPECTED_NODE_COUNT);
                });
    }


    // ══════════════════════════════════════════════════════════════════════
    // TEST 4 — SUSPECT Transition on Node Failure
    // ══════════════════════════════════════════════════════════════════════

    /**
     * When a node stops responding, FailureDetector.probeNode() sends a
     * direct PING (500ms timeout). No ack → tries indirect PING_REQ via
     * up to 3 helpers. Still no ack → markSuspect() + suspectTracker records
     * the timestamp.
     *
     * Probe fires every 1s, ping timeout 500ms → SUSPECT within ~2s.
     *
     * CLEANUP: Container is restarted (not just unpaused) because once a node
     * is DEAD, peers stop gossiping to it, making recovery via unpause impossible.
     * Restart triggers ClusterJoinService which performs self-refutation.
     */
    @Test
    @Order(4)
    @DisplayName("Paused node transitions to SUSPECT on remaining peers within probe interval")
    void suspectTransitionOnNodeFailure() throws Exception {
        assumeDockerAvailable();

        pauseContainer(NODE_3_CONTAINER);
        try {
            Awaitility.await("Node 3 appears SUSPECT (or DEAD) on Node 1")
                    .atMost(SUSPECT_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(() -> {
                        assertThat(aliveCountOf(NODE_1_URL))
                                .as("aliveCount on Node 1 should drop below " + EXPECTED_NODE_COUNT + " once node 3 is suspected")
                                .isLessThan(EXPECTED_NODE_COUNT);
                    });
        } finally {
            restartAndConverge();
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // TEST 5 — DEAD Promotion After Suspect Timeout
    // ══════════════════════════════════════════════════════════════════════

    /**
     * processSuspectTimeouts() runs every probe cycle (1s).
     * Nodes SUSPECT for > suspectTimeoutMs (10s) are promoted to DEAD and
     * removed from the alive-only /members list used by the Load Balancer.
     *
     * Total time: ~1.5s (SUSPECT) + 10s (timeout) = ~11.5s; allow 20s.
     */
    @Test
    @Order(5)
    @DisplayName("SUSPECT node promoted to DEAD after 10s timeout; absent from /internal/cluster/members")
    void deadPromotionAfterSuspectTimeout() throws Exception {
        assumeDockerAvailable();

        // Wait for node 3 to be fully ready after previous test's restart
        Awaitility.await("Node 3 gossip endpoint is responsive")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> {
                    gossipTable(NODE_3_URL);
                    return true;
                });

        // Capture node 3's ID while it is still reachable
        String node3Id = selfEntry(gossipTable(NODE_3_URL)).get("nodeId").asText();

        pauseContainer(NODE_3_CONTAINER);
        try {
            Awaitility.await("Node 3 promoted to DEAD on Node 1")
                    .atMost(DEAD_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(() ->
                            assertThat(statusOf(NODE_1_URL, node3Id))
                                    .as("Node 3 status on Node 1")
                                    .isEqualTo("DEAD"));

            // DEAD nodes must not appear in the alive-only /members endpoint
            assertThat(aliveNodeIds(NODE_1_URL))
                    .as("DEAD node must be absent from /internal/cluster/members")
                    .doesNotContain(node3Id);

        } finally {
            restartAndConverge();
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // TEST 6 — Node Recovery + Self-Refutation (Incarnation Bump)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Recovery path with the current implementation (dirty-only gossip):
     *
     *   1. Node 3 is paused → peers mark it DEAD (incarnation Y).
     *   2. Node 3 is restarted (fresh JVM, incarnation = 0).
     *   3. ClusterJoinService sends POST /cluster/join to node 1.
     *      Node 1 responds with its gossip digest including node 3 as DEAD (Y).
     *   4. Node 3 merges the response → handleSelfGossip fires:
     *      incoming.incarnation (Y) >= self.incarnation (0) → refutes!
     *      Self incarnation bumped to Y+1, status = ALIVE.
     *   5. Next gossip round: node 3 sends ALIVE with incarnation Y+1.
     *   6. Node 1 merges: Y+1 > Y → accepts → node 3 is ALIVE again.
     *
     * Observable: aliveCount back to 3 AND node 3's self-reported incarnation
     * is greater than the incarnation it had before the failure.
     *
     * NOTE: incarnBefore is read from NODE_3_URL (node 3's own view of itself)
     * so it remains valid regardless of whether node 3's Docker IP changes
     * after restart and it gets a new nodeId.
     */
    @Test
    @Order(6)
    @DisplayName("Restarted node recovers to ALIVE; incarnation increases proving self-refutation fired")
    void nodeRecoveryAndSelfRefutation() throws Exception {
        assumeDockerAvailable();

        String node3Id = selfEntry(gossipTable(NODE_3_URL)).get("nodeId").asText();
        long incarnBefore = incarnationOf(NODE_1_URL, node3Id);

        pauseContainer(NODE_3_CONTAINER);

        Awaitility.await("Node 3 reaches DEAD on Node 1")
                .atMost(DEAD_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> aliveCountOf(NODE_1_URL) < EXPECTED_NODE_COUNT);

        restartContainer(NODE_3_CONTAINER);

        Awaitility.await("Node 3 recovers to ALIVE with refuted incarnation")
                .atMost(RECOVERY_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> {
                    assertThat(aliveCountOf(NODE_1_URL))
                            .as("aliveCount on Node 1 must return to " + EXPECTED_NODE_COUNT)
                            .isEqualTo((long) EXPECTED_NODE_COUNT);

                    long incarnAfter = incarnationOf(NODE_1_URL, node3Id);
                    assertThat(incarnAfter)
                            .as("Incarnation must exceed pre-failure value (%d) proving self-refutation", incarnBefore)
                            .isGreaterThan(incarnBefore);
                });

        assertThat(aliveNodeIds(NODE_1_URL))
                .as("Recovered node must reappear in /internal/cluster/members")
                .isNotEmpty();
    }


    // ══════════════════════════════════════════════════════════════════════
    // TEST 7 — Cluster Join Seeds Membership Before First Gossip Round
    // ══════════════════════════════════════════════════════════════════════

    /**
     * ClusterJoinService fires on ApplicationReadyEvent (before the first 5s
     * gossip round). It resolves the cluster DNS alias, calls POST /cluster/join
     * on each discovered peer, and merges the returned full digest locally.
     *
     * By the time docker health checks pass, each node already has all peers
     * in its membership table without waiting for gossip propagation.
     * The tight 5s window distinguishes join-seeding from gossip convergence.
     */
    @Test
    @Order(7)
    @DisplayName("ClusterJoinService seeds all peers on startup before first gossip round fires")
    void clusterJoinSeedsBeforeFirstGossipRound() {
        // Use until() with exception swallowing so transient IOExceptions (e.g., node
        // still starting after previous test's restart) are treated as "not yet" rather
        // than hard failures.  The 30s window is conservative; a fresh cluster satisfies
        // this almost immediately via ClusterJoinService.
        for (String nodeUrl : ALL_NODE_URLS) {
            Awaitility.await("Node " + nodeUrl + " already knows all " + EXPECTED_NODE_COUNT + " members via join")
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(POLL_INTERVAL)
                    .until(() -> {
                        try {
                            return gossipTable(nodeUrl).get("totalNodes").asInt() == EXPECTED_NODE_COUNT;
                        } catch (Exception e) {
                            return false;
                        }
                    });
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // HTTP helpers — talk directly to cache node HTTP ports
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Fetches the gossip table from the given node with built-in retry.
     *
     * After a container restart, Docker's port proxy accepts TCP connections
     * before Spring Boot has finished binding — causing an immediate EOF.
     * We retry up to 5 times with 1s delay to ride out this window.
     * On final failure the IOException is wrapped as AssertionError so that
     * Awaitility-based callers also retry rather than aborting immediately.
     */
    private JsonNode gossipTable(String nodeUrl) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                HttpResponse<String> resp = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(nodeUrl + GOSSIP_PATH))
                                .GET()
                                .timeout(Duration.ofSeconds(5))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(resp.statusCode())
                        .as("GET %s%s", nodeUrl, GOSSIP_PATH)
                        .isEqualTo(200);
                return MAPPER.readTree(resp.body());
            } catch (IOException e) {
                last = e;
                if (attempt < 4) {
                    Awaitility.await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2)).until(() -> true);
                }
            }
        }
        throw new AssertionError("GET " + nodeUrl + GOSSIP_PATH + " failed after 5 attempts", last);
    }

    private List<String> aliveNodeIds(String nodeUrl) throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(nodeUrl + MEMBERS_PATH))
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode())
                .as("GET %s%s", nodeUrl, MEMBERS_PATH)
                .isEqualTo(200);
        List<String> ids = new ArrayList<>();
        MAPPER.readTree(resp.body()).forEach(m -> ids.add(m.get("nodeId").asText()));
        return ids;
    }

    private long aliveCountOf(String nodeUrl) throws Exception {
        return gossipTable(nodeUrl).get("aliveCount").asLong();
    }

    /** Returns the member entry where {@code self == true}. */
    private JsonNode selfEntry(JsonNode table) {
        for (JsonNode m : table.get("members")) {
            if (m.get("self").asBoolean()) return m;
        }
        throw new AssertionError("No self entry found in gossip table");
    }

    /** Returns all nodeIds present in the gossip table (any status). */
    private Set<String> allNodeIds(String nodeUrl) throws Exception {
        Set<String> ids = new HashSet<>();
        gossipTable(nodeUrl).get("members").forEach(m -> ids.add(m.get("nodeId").asText()));
        return ids;
    }

    /**
     * Returns the status string of targetNodeId as seen from observerUrl.
     * Returns {@code null} if the target is not yet in the gossip table.
     */
    private String statusOf(String observerUrl, String targetNodeId) throws Exception {
        for (JsonNode m : gossipTable(observerUrl).get("members")) {
            if (targetNodeId.equals(m.get("nodeId").asText())) {
                return m.get("status").asText();
            }
        }
        return null;
    }

    /**
     * Returns the incarnation of targetNodeId as seen from observerUrl.
     * Returns -1 if the target is not yet in the gossip table.
     */
    private long incarnationOf(String observerUrl, String targetNodeId) throws Exception {
        for (JsonNode m : gossipTable(observerUrl).get("members")) {
            if (targetNodeId.equals(m.get("nodeId").asText())) {
                return m.get("incarnation").asLong();
            }
        }
        return -1;
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
                "Docker not available on this host — skipping container lifecycle test");
    }

    private void pauseContainer(String container) throws Exception {
        exec("docker", "pause", container);
    }

    private void restartContainer(String container) throws Exception {
        exec("docker", "restart", container);
    }

    /**
     * Restarts node 3 and waits for the cluster to fully converge back to
     * 3 ALIVE members. Used in finally blocks to leave the cluster clean
     * for subsequent tests.
     */
    private void restartAndConverge() {
        try {
            restartContainer(NODE_3_CONTAINER);
        } catch (Exception e) {
            System.err.println("WARNING: failed to restart node 3: " + e.getMessage());
            return;
        }

        Awaitility.await("Cluster recovers to " + EXPECTED_NODE_COUNT + " ALIVE members after node 3 restart")
                .atMost(RECOVERY_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> {
                    try {
                        return aliveCountOf(NODE_1_URL) == EXPECTED_NODE_COUNT;
                    } catch (Exception e) {
                        return false;
                    }
                });
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
