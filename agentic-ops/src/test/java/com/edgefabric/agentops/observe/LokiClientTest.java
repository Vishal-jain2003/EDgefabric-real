package com.edgefabric.agentops.observe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class LokiClientTest {

    private MockRestServiceServer server;
    private LokiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://loki:3100");
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        client = new LokiClient(builder.build());
    }

    private static final String LOKI_SUCCESS = """
            {
              "status": "success",
              "data": {
                "resultType": "streams",
                "result": [
                  {
                    "stream": {"service": "edgefabric-caching"},
                    "values": [
                      ["1716288000000000000", "ERROR quorum write timeout"],
                      ["1716288010000000000", "ERROR another error"]
                    ]
                  }
                ]
              }
            }
            """;

    @Test
    void queryRange_returnsLogLines_onSuccess() {
        server.expect(requestTo(containsString("/loki/api/v1/query_range")))
                .andRespond(withSuccess(LOKI_SUCCESS, MediaType.APPLICATION_JSON));

        List<String> lines = client.queryRange("{job=\"test\"}", "0", "60", 100);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("ERROR quorum write timeout");
    }

    @Test
    void queryRange_returnsEmptyList_onServerError() {
        server.expect(requestTo(containsString("/loki/api/v1/query_range")))
                .andRespond(withServerError());

        List<String> lines = client.queryRange("{job=\"test\"}", "0", "60", 100);

        assertThat(lines).isEmpty();
    }

    @Test
    void queryRange_returnsEmptyList_whenStatusIsError() {
        String errorBody = "{\"status\":\"error\",\"error\":\"bad query\"}";
        server.expect(requestTo(containsString("/loki/api/v1/query_range")))
                .andRespond(withSuccess(errorBody, MediaType.APPLICATION_JSON));

        List<String> lines = client.queryRange("bad", "0", "60", 10);

        assertThat(lines).isEmpty();
    }

    @Test
    void countErrors_returnsLineCount() {
        server.expect(requestTo(containsString("/loki/api/v1/query_range")))
                .andRespond(withSuccess(LOKI_SUCCESS, MediaType.APPLICATION_JSON));

        int count = client.countErrors("edgefabric-caching", "quorum write timeout", 5);

        assertThat(count).isEqualTo(2);
    }

    @Test
    void countErrors_returnsZero_onFailure() {
        server.expect(requestTo(containsString("/loki/api/v1/query_range")))
                .andRespond(withServerError());

        int count = client.countErrors("edgefabric-caching", "ERROR", 5);

        assertThat(count).isZero();
    }
}
