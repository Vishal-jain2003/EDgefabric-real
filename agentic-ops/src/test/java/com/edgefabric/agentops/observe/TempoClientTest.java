package com.edgefabric.agentops.observe;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TempoClientTest {

    private MockRestServiceServer server;
    private TempoClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://tempo:3200");
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        client = new TempoClient(builder.build());
    }

    private static final String SEARCH_SUCCESS = """
            {
              "traces": [
                {"traceID": "abc123", "rootServiceName": "edgefabric-loadbalancer", "durationMs": 800},
                {"traceID": "def456", "rootServiceName": "edgefabric-loadbalancer", "durationMs": 1200}
              ]
            }
            """;

    @Test
    void searchSlowTraces_returnsTraceIds_onSuccess() {
        server.expect(requestToUriTemplate(
                        "http://tempo:3200/api/search?service.name=edgefabric-loadbalancer&minDuration=500ms&limit=5"))
                .andRespond(withSuccess(SEARCH_SUCCESS, MediaType.APPLICATION_JSON));

        List<String> traceIds = client.searchSlowTraces("edgefabric-loadbalancer", 500, 5);

        assertThat(traceIds).hasSize(2).containsExactly("abc123", "def456");
        server.verify();
    }

    @Test
    void searchSlowTraces_returnsEmptyList_onServerError() {
        server.expect(requestToUriTemplate(
                        "http://tempo:3200/api/search?service.name=edgefabric-loadbalancer&minDuration=500ms&limit=5"))
                .andRespond(withServerError());

        List<String> result = client.searchSlowTraces("edgefabric-loadbalancer", 500, 5);

        assertThat(result).isEmpty();
    }

    @Test
    void searchSlowTraces_returnsEmptyList_whenBodyIsNull() {
        server.expect(requestToUriTemplate(
                        "http://tempo:3200/api/search?service.name=svc&minDuration=100ms&limit=3"))
                .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        List<String> result = client.searchSlowTraces("svc", 100, 3);

        assertThat(result).isEmpty();
    }

    @Test
    void getTrace_returnsJsonNode_onSuccess() {
        String traceBody = "{\"traceID\":\"abc123\",\"spans\":[]}";
        server.expect(requestToUriTemplate("http://tempo:3200/api/traces/abc123"))
                .andRespond(withSuccess(traceBody, MediaType.APPLICATION_JSON));

        JsonNode trace = client.getTrace("abc123");

        assertThat(trace).isNotNull();
        assertThat(trace.path("traceID").asText()).isEqualTo("abc123");
        server.verify();
    }

    @Test
    void getTrace_returnsNull_onServerError() {
        server.expect(requestToUriTemplate("http://tempo:3200/api/traces/bad-id"))
                .andRespond(withServerError());

        JsonNode result = client.getTrace("bad-id");

        assertThat(result).isNull();
    }
}
