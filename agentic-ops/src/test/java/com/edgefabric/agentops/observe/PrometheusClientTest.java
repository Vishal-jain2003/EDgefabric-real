package com.edgefabric.agentops.observe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PrometheusClientTest {

    private MockRestServiceServer server;
    private PrometheusClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://prometheus:9090");
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        client = new PrometheusClient(builder.build());
    }

    private static final String INSTANT_SUCCESS = """
            {
              "status": "success",
              "data": {
                "resultType": "vector",
                "result": [
                  {
                    "metric": {"__name__": "up", "instance": "node1"},
                    "value": [1716288000, "1"]
                  }
                ]
              }
            }
            """;

    private static final String RANGE_SUCCESS = """
            {
              "status": "success",
              "data": {
                "resultType": "matrix",
                "result": [
                  {
                    "metric": {"__name__": "http_requests_total"},
                    "values": [[1716288000, "10"], [1716288030, "20"], [1716288060, "30"]]
                  }
                ]
              }
            }
            """;

    @Test
    void query_returnsMap_onSuccess() {
        server.expect(requestTo(containsString("/api/v1/query")))
                .andRespond(withSuccess(INSTANT_SUCCESS, MediaType.APPLICATION_JSON));

        Map<String, String> result = client.query("up");

        assertThat(result).isNotEmpty();
        assertThat(result.values()).contains("1");
        server.verify();
    }

    @Test
    void query_returnsEmptyMap_onServerError() {
        server.expect(requestTo(containsString("/api/v1/query")))
                .andRespond(withServerError());

        Map<String, String> result = client.query("up");

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    void query_returnsEmptyMap_onFailureStatus() {
        String failBody = "{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"bad query\"}";
        server.expect(requestTo(containsString("/api/v1/query")))
                .andRespond(withSuccess(failBody, MediaType.APPLICATION_JSON));

        Map<String, String> result = client.query("badq");

        assertThat(result).isEmpty();
    }

    @Test
    void queryRange_returnsLastValueForEachSeries() {
        server.expect(requestTo(containsString("/api/v1/query_range")))
                .andRespond(withSuccess(RANGE_SUCCESS, MediaType.APPLICATION_JSON));

        Map<String, String> result = client.queryRange("rate(x[1m])", "0", "60", "30s");

        assertThat(result).isNotEmpty();
        assertThat(result.values()).contains("30");
        server.verify();
    }

    @Test
    void queryRange_returnsEmptyMap_onServerError() {
        server.expect(requestTo(containsString("/api/v1/query_range")))
                .andRespond(withServerError());

        Map<String, String> result = client.queryRange("x", "0", "60", "30s");

        assertThat(result).isEmpty();
    }

    @Test
    void queryMemoryUsedRatio_returnsDouble_onSuccess() {
        server.expect(requestTo(containsString("/api/v1/query")))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[
                          {"metric":{},"value":[1716288000,"0.65"]}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        Double ratio = client.queryMemoryUsedRatio("node1:8082");

        assertThat(ratio).isEqualTo(0.65);
    }

    @Test
    void queryMemoryUsedRatio_returnsNull_whenNoResults() {
        server.expect(requestTo(containsString("/api/v1/query")))
                .andRespond(withSuccess(
                        "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}",
                        MediaType.APPLICATION_JSON));

        Double ratio = client.queryMemoryUsedRatio("node1:8082");

        assertThat(ratio).isNull();
    }

    @Test
    void queryEvictionsPerMin_returnsDouble_onSuccess() {
        server.expect(requestTo(containsString("/api/v1/query")))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[
                          {"metric":{},"value":[1716288000,"3.5"]}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        Double evictions = client.queryEvictionsPerMin("node1:8082");

        assertThat(evictions).isEqualTo(3.5);
    }

    @Test
    void queryEvictionsPerMin_returnsNull_whenServerDown() {
        server.expect(requestTo(containsString("/api/v1/query")))
                .andRespond(withServerError());

        Double evictions = client.queryEvictionsPerMin("node1:8082");

        assertThat(evictions).isNull();
    }
}
