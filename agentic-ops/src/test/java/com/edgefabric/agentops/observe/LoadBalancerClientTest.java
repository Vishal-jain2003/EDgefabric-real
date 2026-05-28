package com.edgefabric.agentops.observe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class LoadBalancerClientTest {

    private MockRestServiceServer server;
    private LoadBalancerClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://lb:8080");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new LoadBalancerClient(builder.build());
    }

    @Test
    void getDashboardExport_returnsJsonNode_onSuccess() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("loadBalancerStatus", "UP");
        body.set("nodes", mapper.createArrayNode());

        server.expect(requestToUriTemplate("http://lb:8080/api/v1/dashboard/export"))
                .andRespond(withSuccess(mapper.writeValueAsString(body), MediaType.APPLICATION_JSON));

        JsonNode result = client.getDashboardExport();

        assertThat(result).isNotNull();
        assertThat(result.path("loadBalancerStatus").asText()).isEqualTo("UP");
        server.verify();
    }

    @Test
    void getDashboardExport_returnsNull_onServerError() {
        server.expect(requestToUriTemplate("http://lb:8080/api/v1/dashboard/export"))
                .andRespond(withServerError());

        JsonNode result = client.getDashboardExport();

        assertThat(result).isNull();
        server.verify();
    }

    @Test
    void getRingInfo_returnsJsonNode_onSuccess() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("nodeCount", 3);
        body.put("ringSize", 450);
        body.put("virtualNodesPerNode", 150);
        body.put("hashAlgorithm", "xxhash");

        server.expect(requestToUriTemplate("http://lb:8080/api/v1/internal/ring/info"))
                .andRespond(withSuccess(mapper.writeValueAsString(body), MediaType.APPLICATION_JSON));

        JsonNode result = client.getRingInfo();

        assertThat(result).isNotNull();
        assertThat(result.path("nodeCount").asInt()).isEqualTo(3);
        server.verify();
    }

    @Test
    void getRingInfo_returnsNull_onServerError() {
        server.expect(requestToUriTemplate("http://lb:8080/api/v1/internal/ring/info"))
                .andRespond(withServerError());

        JsonNode result = client.getRingInfo();

        assertThat(result).isNull();
        server.verify();
    }
}
