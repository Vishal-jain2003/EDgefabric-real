package com.edgefabric.loadbalancer.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class RestClientConfigTest {

    @Test
    void webClient_createsInstance() {
        RestClientConfig config = new RestClientConfig();
        WebClient client = config.webClient(WebClient.builder(), 5000, 10000L);
        assertNotNull(client);
    }
}
