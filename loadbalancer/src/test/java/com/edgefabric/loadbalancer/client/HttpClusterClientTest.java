package com.edgefabric.loadbalancer.client;

import com.edgefabric.loadbalancer.dto.response.CacheResponse;
import com.edgefabric.loadbalancer.exception.CacheKeyExpiredException;
import com.edgefabric.loadbalancer.exception.CacheKeyNotFoundException;
import com.edgefabric.loadbalancer.model.CacheNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HttpClusterClientTest {

    private MockWebServer mockWebServer;
    private HttpClusterClient client;
    private CacheNode node;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        WebClient webClient = WebClient.builder().build();
        client = new HttpClusterClient(webClient, 5000L);
        node = new CacheNode("node-1", "localhost", mockWebServer.getPort());
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void forwardPutRequest_ShouldSendCorrectHttpRequest() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        long expiresAt = System.currentTimeMillis() + 60000L;
        byte[] data = "Hello".getBytes();

        client.forwardPutRequest(node, "my-key", data, expiresAt, "text/plain", 12345L).block();

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("PUT", request.getMethod());
        assertEquals("/api/v1/internal/cache/my-key", request.getPath());
        assertEquals(String.valueOf(expiresAt), request.getHeader("X-Expires-At"));
        assertEquals("12345", request.getHeader("X-Quorum-Version"));
        assertEquals("text/plain", request.getHeader("Content-Type"));
        assertArrayEquals(data, request.getBody().readByteArray());
    }

    @Test
    void forwardPutRequest_ShouldThrowException_WhenServerErrors() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));
        long expiresAt = System.currentTimeMillis() + 1000L;

        assertThrows(RuntimeException.class, () ->
                client.forwardPutRequest(node, "fail-key", new byte[1], expiresAt, "text/plain", 1L).block()
        );
    }

    @Test
    void forwardGetRequest_shouldReturnCacheResponse_whenKeyExists() {
        long expiresAt = System.currentTimeMillis() + 5000L;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/plain")
                .addHeader("X-Quorum-Version", "777")
                .addHeader("X-Expires-At", String.valueOf(expiresAt))
                .setBody("Hello Rayyan"));

        CacheResponse result = client.forwardGetRequest(node, "user_123").block();

        assertNotNull(result.getData());
        String dataStr = new String(result.getData(), StandardCharsets.UTF_8);

        assertEquals("Hello Rayyan", dataStr);
        assertEquals("text/plain", result.getContentType());
        assertEquals(777L, result.getVersion());
        assertEquals(expiresAt, result.getExpiresAt());
    }

    @Test
    void forwardGetRequest_shouldThrowCacheKeyNotFoundException_whenKeyNotFound() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(404));

        assertThrows(CacheKeyNotFoundException.class, () ->
                client.forwardGetRequest(node, "missing_key").block()
        );
    }

    @Test
    void forwardGetRequest_shouldThrowCacheKeyExpiredException_whenKeyGone() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(410));

        assertThrows(CacheKeyExpiredException.class, () ->
                client.forwardGetRequest(node, "expired_key").block()
        );
    }

    @Test
    void forwardPutRequest_shouldNotSetVersionHeader_whenVersionIsZero() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        client.forwardPutRequest(node, "vkey", "data".getBytes(), 99999L, "text/plain", 0L).block();

        RecordedRequest request = mockWebServer.takeRequest();
        assertNull(request.getHeader("X-Quorum-Version"));
        assertEquals("99999", request.getHeader("X-Expires-At"));
    }

    @Test
    void forwardGetRequest_shouldReturnDefaults_whenHeadersMissing() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/octet-stream")
                .setBody("payload"));

        CacheResponse result = client.forwardGetRequest(node, "noheaders").block();

        assertEquals(-1L, result.getVersion());
        assertEquals(-1L, result.getExpiresAt());
        assertNotNull(result.getData());
        assertEquals("payload", new String(result.getData(), StandardCharsets.UTF_8));
    }

    @Test
    void forwardGetRequest_shouldReturnMinusOne_whenHeadersAreInvalid() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/plain")
                .addHeader("X-Quorum-Version", "not-a-number")
                .addHeader("X-Expires-At", "also-bad")
                .setBody("data"));

        CacheResponse result = client.forwardGetRequest(node, "badheaders").block();

        assertEquals(-1L, result.getVersion());
        assertEquals(-1L, result.getExpiresAt());
        assertEquals("text/plain", result.getContentType());
    }
}
