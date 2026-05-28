package com.edgefabric.caching.service;

import com.edgefabric.caching.dto.NodeInfoDTO;
import com.edgefabric.caching.exception.RegistryNotFoundException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;


import static org.junit.jupiter.api.Assertions.*;

class RegistryClientTest {
    private MockWebServer mockWebServer;
    private RegistryClient registryClient;

    @BeforeEach
    void setUp() throws Exception{
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        String baseUrl = mockWebServer.url("/").toString();
        WebClient webClient = WebClient
                .builder()
                .baseUrl(baseUrl)
                .build();

        registryClient = new RegistryClient(webClient);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockWebServer.shutdown();
    }

    @Test
    void shouldSendRegisterRequest() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        NodeInfoDTO nodeInfo = new NodeInfoDTO("node1","127.0.0.1",8080);

        registryClient.register(nodeInfo).block();

        RecordedRequest recordedRequest = mockWebServer.takeRequest();

        assertEquals("POST",recordedRequest.getMethod());
        assertEquals("/registry/node",recordedRequest.getPath());

        String body = recordedRequest.getBody().readUtf8();
        assertTrue(body.contains("node1"));
    }

    @Test
    void shouldSendHeartbeatRequest() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));
        registryClient.sendHeartbeat("node1");

        RecordedRequest recordedRequest = mockWebServer.takeRequest();

        assertEquals("POST", recordedRequest.getMethod());
        assertEquals("/registry/node/heartbeat", recordedRequest.getPath());

        String body = recordedRequest.getBody().readUtf8();
        assertTrue(body.contains("node1"));
    }

    @Test
    void shouldNotThrowExceptionWhenRegistryDown() throws Exception {
        mockWebServer.shutdown();

        assertDoesNotThrow(
                () -> registryClient.sendHeartbeat("node1")
        );
    }

    @Test
    void shouldHandleRegistrationFailure() throws Exception {
        mockWebServer.shutdown();

        NodeInfoDTO nodeInfo = new NodeInfoDTO("node1", "127.0.0.1", 8080);

        assertDoesNotThrow(
                () -> registryClient.register(nodeInfo)
        );
    }

    @Test
    void shouldSendDeregisterRequest() throws Exception {

        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        registryClient.deregister("node1");

        RecordedRequest recordedRequest = mockWebServer.takeRequest();

        assertEquals("DELETE", recordedRequest.getMethod());
        assertEquals("/registry/node/node1", recordedRequest.getPath());
    }

    @Test
    void shouldThrowExceptionWhenDeregisterFails() {

        mockWebServer.enqueue(
                new MockResponse().setResponseCode(500)
        );

        assertThrows(RegistryNotFoundException.class,
                () -> registryClient.deregister("node1"));
    }
}
