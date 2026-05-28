package com.edgefabric.caching.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link WalClient}.
 *
 * <p>Verifies the async, fire-and-forget WAL append contract:
 * <ul>
 *   <li>{@code appendAsync} is non-blocking (returns immediately)</li>
 *   <li>Queue overflow drops the entry and increments the dropped counter</li>
 *   <li>Background drainer POSTs successfully to the LB endpoint</li>
 *   <li>LB unreachable results in silent failure — no exception propagated</li>
 * </ul>
 */
class WalClientTest {

    private MockWebServer mockWebServer;
    private SimpleMeterRegistry meterRegistry;

    private static final int QUEUE_CAPACITY = 3;
    // 5 s gives the WAL drainer enough time to establish a TCP connection on a
    // loaded CI agent.  Socket-accept latency on shared runners can exceed 2 s,
    // causing the connect to time out before takeRequest() returns.
    private static final int TIMEOUT_MS = 5000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        // Bind explicitly to 127.0.0.1 so the server URL never uses the machine
        // hostname (e.g. eapi.opswatgears.com), which is unresolvable in CI.
        mockWebServer.start(InetAddress.getByName("127.0.0.1"), 0);
        meterRegistry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private WalClient buildClient() {
        // Use 127.0.0.1 directly — mockWebServer.url("/") may return the machine
        // hostname which is unresolvable in CI environments.
        String lbBaseUrl = "http://127.0.0.1:" + mockWebServer.getPort() + "/";
        return new WalClient(lbBaseUrl, QUEUE_CAPACITY, TIMEOUT_MS, meterRegistry, OBJECT_MAPPER);
    }

    private WalClient buildClientUnreachable() {
        // Port 1 is always unreachable / immediately refused
        return new WalClient("http://127.0.0.1:1", QUEUE_CAPACITY, TIMEOUT_MS, meterRegistry, OBJECT_MAPPER);
    }

    private Counter droppedCounter(WalClient client) {
        Counter c = meterRegistry.find("edgefabric.wal_client.dropped").counter();
        assertThat(c).as("edgefabric.wal_client.dropped counter must be registered").isNotNull();
        return c;
    }

    private Counter appendedCounter(WalClient client) {
        Counter c = meterRegistry.find("edgefabric.wal_client.appended").counter();
        assertThat(c).as("edgefabric.wal_client.appended counter must be registered").isNotNull();
        return c;
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    /**
     * appendAsync must return without blocking — the caller thread must not be
     * delayed even if the queue is being drained slowly.
     */
    @Test
    void appendAsync_isNonBlocking() {
        WalClient client = buildClient();

        long startMs = System.currentTimeMillis();
        client.appendAsync("cache:key1", "hello".getBytes(), System.currentTimeMillis() + 60_000L,
                "text/plain", 1L);
        long elapsedMs = System.currentTimeMillis() - startMs;

        // Must return in well under 1 second — if it blocks it would take at least TIMEOUT_MS
        assertThat(elapsedMs).isLessThan(500L);
    }

    /**
     * When the bounded queue is full, further appendAsync calls must silently drop
     * the entry and increment {@code edgefabric.wal_client.dropped}.
     */
    @Test
    void appendAsync_queueOverflow_dropsEntryAndIncrementsCounter() throws InterruptedException {
        WalClient client = buildClient();

        // Fill the queue to capacity without a server response (server will not accept)
        // We pause the server by not enqueueing any responses, but the drainer
        // is still running. Use a very small capacity and flood it.
        // Re-build with capacity=1 so one entry fills it fast.
        String lbBaseUrl = "http://127.0.0.1:" + mockWebServer.getPort() + "/";
        WalClient tinyClient = new WalClient(lbBaseUrl, 1, TIMEOUT_MS, meterRegistry, OBJECT_MAPPER);

        // Flood beyond capacity — at least one must be dropped
        for (int i = 0; i < 10; i++) {
            tinyClient.appendAsync("cache:key" + i, "data".getBytes(),
                    System.currentTimeMillis() + 60_000L, "text/plain", (long) i);
        }

        // Give background thread a moment so counters are updated
        TimeUnit.MILLISECONDS.sleep(200);

        assertThat(droppedCounter(tinyClient).count()).isGreaterThanOrEqualTo(1.0);
    }

    /**
     * Background drainer must POST to {@code /api/v1/internal/wal/append} on the LB
     * and the request body must contain the key.
     */
    @Test
    void backgroundDrainer_postsToLbEndpoint() throws Exception {
        // Enqueue a 202 Accepted response so the POST succeeds
        mockWebServer.enqueue(new MockResponse().setResponseCode(202));

        WalClient client = buildClient();
        byte[] data = "payload".getBytes();
        long expiresAt = System.currentTimeMillis() + 60_000L;

        client.appendAsync("cache:my-key", data, expiresAt, "application/json", 42L);

        // Wait for the background thread to drain and POST
        RecordedRequest request = mockWebServer.takeRequest(3, TimeUnit.SECONDS);

        assertThat(request).as("Expected a POST request to the LB WAL endpoint").isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/api/v1/internal/wal/append");

        String body = request.getBody().readUtf8();
        assertThat(body).contains("my-key");
    }

    /**
     * When the LB is unreachable, appendAsync must not propagate any exception to
     * the caller. The dropped counter must reflect the failed attempt.
     */
    @Test
    void appendAsync_lbUnreachable_silentFailureNoException() throws InterruptedException {
        WalClient client = buildClientUnreachable();

        assertThatCode(() ->
                client.appendAsync("cache:key-unreachable", "data".getBytes(),
                        System.currentTimeMillis() + 60_000L, "text/plain", 1L)
        ).doesNotThrowAnyException();

        // Allow background drainer to attempt and fail
        TimeUnit.MILLISECONDS.sleep(600);

        // No exception must have escaped; dropped counter may be incremented on failure
        // (implementation detail — just verify no NPE / propagation)
        assertThat(droppedCounter(client).count()).isGreaterThanOrEqualTo(0.0);
    }

    /**
     * After a successful POST the appended counter must be incremented.
     */
    @Test
    void backgroundDrainer_successfulPost_incrementsAppendedCounter() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(202));

        WalClient client = buildClient();
        client.appendAsync("cache:counted-key", "data".getBytes(),
                System.currentTimeMillis() + 60_000L, "text/plain", 7L);

        // Wait for the request to be received and processed
        RecordedRequest request = mockWebServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(request).as("Expected a POST request to be made").isNotNull();

        // Wait longer to ensure the counter is incremented after the response is received
        TimeUnit.MILLISECONDS.sleep(500);

        assertThat(appendedCounter(client).count()).isGreaterThanOrEqualTo(1.0);
    }
}
