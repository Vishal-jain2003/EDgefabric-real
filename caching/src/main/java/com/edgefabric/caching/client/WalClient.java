package com.edgefabric.caching.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Async WAL client that drains a bounded queue and POSTs entries to the
 * load balancer's {@code /api/v1/internal/wal/append} endpoint.
 *
 * <p>Gated by {@code cache.wal.enabled=true}.  If the queue overflows, the
 * entry is silently dropped and the {@code edgefabric.wal_client.dropped}
 * counter is incremented.  LB unreachability is logged at WARN; no exception
 * is propagated to the caller.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "cache.wal.enabled", havingValue = "true")
public class WalClient {

    private static final String APPEND_PATH = "/api/v1/internal/wal/append";

    private final String lbBaseUrl;
    private final int queueCapacity;
    private final int timeoutMs;
    private final MeterRegistry meterRegistry;

    private final LinkedBlockingQueue<WalAppendRequest> queue;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private Counter droppedCounter;
    private Counter appendedCounter;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread drainerThread;

    /**
     * Primary constructor used both by Spring (via {@link WalClientConfig}) and
     * directly in unit tests.
     *
     * @param objectMapper the shared Spring-managed ObjectMapper — must not be null
     */
    public WalClient(String lbBaseUrl, int queueCapacity, int timeoutMs,
                     MeterRegistry meterRegistry, ObjectMapper objectMapper) {
        this.lbBaseUrl = Objects.requireNonNull(lbBaseUrl, "lbBaseUrl");
        this.queueCapacity = queueCapacity;
        this.timeoutMs = timeoutMs;
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();

        initCounters();
        startDrainer();
    }

    private void initCounters() {
        this.droppedCounter = Counter.builder("edgefabric.wal_client.dropped")
                .description("WAL entries dropped due to full queue or send failure")
                .register(meterRegistry);
        this.appendedCounter = Counter.builder("edgefabric.wal_client.appended")
                .description("WAL entries successfully appended to the LB")
                .register(meterRegistry);
    }

    private void startDrainer() {
        drainerThread = new Thread(this::drainLoop, "wal-client-drainer");
        drainerThread.setDaemon(true);
        drainerThread.start();
    }

    /**
     * Non-blocking: enqueues the entry for async delivery.
     * If the queue is full, the entry is dropped and the dropped counter is incremented.
     */
    public void appendAsync(String key, byte[] data, long expiresAt, String contentType, long version) {
        String dataBase64 = Base64.getEncoder().encodeToString(data);
        WalAppendRequest req = new WalAppendRequest(key, dataBase64, expiresAt, contentType, version, "");
        boolean offered = queue.offer(req);
        if (!offered) {
            droppedCounter.increment();
            log.debug("WAL client: queue full, dropped entry for key={}", key);
        }
    }

    private void drainLoop() {
        while (running.get() || !queue.isEmpty()) {
            try {
                WalAppendRequest req = queue.poll(100, TimeUnit.MILLISECONDS);
                if (req == null) continue;
                sendToLb(req);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Never propagate — just log
                log.warn("WAL client: unexpected error in drainer loop: {}", e.getMessage());
            }
        }
    }

    private void sendToLb(WalAppendRequest req) {
        try {
            String body = objectMapper.writeValueAsString(req);
            // Build the full URL by trimming trailing slash from base URL
            String base = lbBaseUrl.endsWith("/")
                    ? lbBaseUrl.substring(0, lbBaseUrl.length() - 1)
                    : lbBaseUrl;
            String url = base + APPEND_PATH;

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeoutMs))
                    .build();

            HttpResponse<Void> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                appendedCounter.increment();
            } else {
                log.warn("WAL client: LB returned non-2xx status code={} for key={}", response.statusCode(), req.getKey());
                droppedCounter.increment();
            }
        } catch (Exception e) {
            log.warn("WAL client: failed to POST to LB baseUrl={} key={}: {}", lbBaseUrl, req.getKey(), e.getMessage());
            droppedCounter.increment();
            // Do not propagate — best-effort delivery
        }
    }

    @PreDestroy
    public void destroy() {
        running.set(false);
        if (drainerThread != null) {
            drainerThread.interrupt();
            try {
                // Wait up to 2 × timeoutMs for in-flight send to complete before the JVM tears down
                drainerThread.join(Math.max(timeoutMs * 2L, 2000L));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
