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

class CacheNodeClientTest {

    private MockRestServiceServer server;
    private CacheNodeClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        client = new CacheNodeClient(builder.build());
    }

    // ── HTTP tests ─────────────────────────────────────────────────────────

    @Test
    void getStats_returnsJsonNode_onSuccess() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("hitRate", 85.0);
        body.put("memoryUsed", 1024L);

        server.expect(requestToUriTemplate("http://10.0.0.1:8082/api/v1/cache/stats"))
                .andRespond(withSuccess(mapper.writeValueAsString(body), MediaType.APPLICATION_JSON));

        JsonNode result = client.getStats("10.0.0.1", 8082);

        assertThat(result).isNotNull();
        assertThat(result.path("hitRate").asDouble()).isEqualTo(85.0);
        server.verify();
    }

    @Test
    void getStats_returnsNull_onServerError() {
        server.expect(requestToUriTemplate("http://10.0.0.1:8082/api/v1/cache/stats"))
                .andRespond(withServerError());

        JsonNode result = client.getStats("10.0.0.1", 8082);

        assertThat(result).isNull();
        server.verify();
    }

    @Test
    void getGossipTable_returnsJsonNode_onSuccess() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("totalNodes", 3);
        body.set("members", mapper.createArrayNode());

        server.expect(requestToUriTemplate("http://10.0.0.1:8082/internal/cluster/gossip"))
                .andRespond(withSuccess(mapper.writeValueAsString(body), MediaType.APPLICATION_JSON));

        JsonNode result = client.getGossipTable("10.0.0.1", 8082);

        assertThat(result).isNotNull();
        assertThat(result.path("totalNodes").asInt()).isEqualTo(3);
        server.verify();
    }

    @Test
    void getGossipTable_returnsNull_onServerError() {
        server.expect(requestToUriTemplate("http://10.0.0.1:8082/internal/cluster/gossip"))
                .andRespond(withServerError());

        assertThat(client.getGossipTable("10.0.0.1", 8082)).isNull();
    }

    @Test
    void getMembers_returnsJsonNode_onSuccess() throws Exception {
        server.expect(requestToUriTemplate("http://10.0.0.1:8082/internal/cluster/members"))
                .andRespond(withSuccess("[{\"nodeId\":\"n1\"}]", MediaType.APPLICATION_JSON));

        JsonNode result = client.getMembers("10.0.0.1", 8082);

        assertThat(result).isNotNull();
        server.verify();
    }

    @Test
    void getMembers_returnsNull_onServerError() {
        server.expect(requestToUriTemplate("http://10.0.0.1:8082/internal/cluster/members"))
                .andRespond(withServerError());

        assertThat(client.getMembers("10.0.0.1", 8082)).isNull();
    }

    // ── Static helper tests ───────────────────────────────────────────────

    @Test
    void extractDouble_returnsValue_whenFieldPresent() throws Exception {
        ObjectNode node = mapper.createObjectNode();
        node.put("hitRate", 75.5);

        assertThat(CacheNodeClient.extractDouble(node, "hitRate")).isEqualTo(75.5);
    }

    @Test
    void extractDouble_returnsNull_whenFieldMissing() throws Exception {
        ObjectNode node = mapper.createObjectNode();

        assertThat(CacheNodeClient.extractDouble(node, "hitRate")).isNull();
    }

    @Test
    void extractDouble_returnsNull_whenNodeIsNull() {
        assertThat(CacheNodeClient.extractDouble(null, "hitRate")).isNull();
    }

    @Test
    void extractLong_returnsValue_whenFieldPresent() throws Exception {
        ObjectNode node = mapper.createObjectNode();
        node.put("memoryUsed", 2048L);

        assertThat(CacheNodeClient.extractLong(node, "memoryUsed")).isEqualTo(2048L);
    }

    @Test
    void extractLong_returnsNull_whenFieldMissing() {
        ObjectNode node = mapper.createObjectNode();

        assertThat(CacheNodeClient.extractLong(node, "memoryUsed")).isNull();
    }

    @Test
    void extractLong_returnsNull_whenNodeIsNull() {
        assertThat(CacheNodeClient.extractLong(null, "memoryUsed")).isNull();
    }

    @Test
    void extractSelfGossipStatus_returnsSelfStatus() throws Exception {
        ObjectNode member = mapper.createObjectNode();
        member.put("status", "ALIVE");
        member.put("self", true);

        ObjectNode table = mapper.createObjectNode();
        table.set("members", mapper.createArrayNode().add(member));

        assertThat(CacheNodeClient.extractSelfGossipStatus(table)).isEqualTo("ALIVE");
    }

    @Test
    void extractSelfGossipStatus_returnsNull_whenNoSelfEntry() throws Exception {
        ObjectNode member = mapper.createObjectNode();
        member.put("status", "ALIVE");
        member.put("self", false);

        ObjectNode table = mapper.createObjectNode();
        table.set("members", mapper.createArrayNode().add(member));

        assertThat(CacheNodeClient.extractSelfGossipStatus(table)).isNull();
    }

    @Test
    void extractSelfGossipStatus_returnsNull_whenTableIsNull() {
        assertThat(CacheNodeClient.extractSelfGossipStatus(null)).isNull();
    }

    @Test
    void extractSelfHeartbeat_returnsValue_whenSelfPresent() throws Exception {
        ObjectNode member = mapper.createObjectNode();
        member.put("heartbeat", 42L);
        member.put("self", true);

        ObjectNode table = mapper.createObjectNode();
        table.set("members", mapper.createArrayNode().add(member));

        assertThat(CacheNodeClient.extractSelfHeartbeat(table)).isEqualTo(42L);
    }

    @Test
    void extractSelfHeartbeat_returnsNull_whenTableIsNull() {
        assertThat(CacheNodeClient.extractSelfHeartbeat(null)).isNull();
    }

    @Test
    void extractSelfSecondsSinceUpdate_returnsValue_whenSelfPresent() throws Exception {
        ObjectNode member = mapper.createObjectNode();
        member.put("secondsSinceUpdate", 5L);
        member.put("self", true);

        ObjectNode table = mapper.createObjectNode();
        table.set("members", mapper.createArrayNode().add(member));

        assertThat(CacheNodeClient.extractSelfSecondsSinceUpdate(table)).isEqualTo(5L);
    }

    @Test
    void extractSelfSecondsSinceUpdate_returnsNull_whenTableIsNull() {
        assertThat(CacheNodeClient.extractSelfSecondsSinceUpdate(null)).isNull();
    }
}
