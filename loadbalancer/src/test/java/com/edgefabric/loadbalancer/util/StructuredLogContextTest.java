package com.edgefabric.loadbalancer.util;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredLogContextTest {

    private static final Logger logger = (Logger) LoggerFactory.getLogger(StructuredLogContextTest.class);
    private ListAppender<ILoggingEvent> listAppender;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(listAppender);
        MDC.clear();
    }

    @Test
    void shouldAddOperationContextToMDC() {
        try (var logCtx = StructuredLogContext.create()
                .operation("PUT")
                .tenant("test-tenant")
                .key("test-key")) {

            assertThat(MDC.get("operation")).isEqualTo("PUT");
            assertThat(MDC.get("tenant")).isEqualTo("test-tenant");
            assertThat(MDC.get("key")).isEqualTo("test-key");
        }
    }

    @Test
    void shouldClearMDCAfterClose() {
        try (var logCtx = StructuredLogContext.create()
                .operation("GET")
                .nodeId("node-1")) {
            assertThat(MDC.get("operation")).isNotNull();
            assertThat(MDC.get("nodeId")).isNotNull();
        }

        assertThat(MDC.get("operation")).isNull();
        assertThat(MDC.get("nodeId")).isNull();
    }

    @Test
    void shouldAddErrorContextForExceptions() {
        Exception testException = new IllegalArgumentException("Test error");

        try (var logCtx = StructuredLogContext.create()
                .errorType(testException.getClass().getSimpleName())
                .errorMessage(testException.getMessage())
                .statusCode(400)) {

            assertThat(MDC.get("errorType")).isEqualTo("IllegalArgumentException");
            assertThat(MDC.get("errorMessage")).isEqualTo("Test error");
            assertThat(MDC.get("statusCode")).isEqualTo("400");
        }
    }

    @Test
    void shouldAddDurationAndResultContext() {
        try (var logCtx = StructuredLogContext.create()
                .duration(123L)
                .result("SUCCESS")
                .statusCode(201)) {

            assertThat(MDC.get("duration")).isEqualTo("123");
            assertThat(MDC.get("result")).isEqualTo("SUCCESS");
            assertThat(MDC.get("statusCode")).isEqualTo("201");
        }
    }

    @Test
    void shouldAddDistributedTracingContext() {
        try (var logCtx = StructuredLogContext.create()
                .nodeId("node-1")
                .clusterId("prod-cluster")) {

            assertThat(MDC.get("nodeId")).isEqualTo("node-1");
            assertThat(MDC.get("clusterId")).isEqualTo("prod-cluster");
        }
    }

    @Test
    void shouldHandleNullValuesGracefully() {
        try (var logCtx = StructuredLogContext.create()
                .operation(null)
                .key("valid-key")
                .tenant(null)) {

            assertThat(MDC.get("operation")).isNull();
            assertThat(MDC.get("key")).isEqualTo("valid-key");
            assertThat(MDC.get("tenant")).isNull();
        }
    }

    @Test
    void shouldSupportArbitraryKeyValuePairs() {
        try (var logCtx = StructuredLogContext.create()
                .put("customField1", "value1")
                .put("customField2", "value2")) {

            assertThat(MDC.get("customField1")).isEqualTo("value1");
            assertThat(MDC.get("customField2")).isEqualTo("value2");
        }

        assertThat(MDC.get("customField1")).isNull();
        assertThat(MDC.get("customField2")).isNull();
    }

    @Test
    void shouldSupportMethodChaining() {
        try (var logCtx = StructuredLogContext.create()
                .operation("PUT")
                .tenant("test")
                .key("key1")
                .duration(50)
                .statusCode(201)
                .result("SUCCESS")) {

            assertThat(MDC.get("operation")).isEqualTo("PUT");
            assertThat(MDC.get("tenant")).isEqualTo("test");
            assertThat(MDC.get("key")).isEqualTo("key1");
            assertThat(MDC.get("duration")).isEqualTo("50");
            assertThat(MDC.get("statusCode")).isEqualTo("201");
            assertThat(MDC.get("result")).isEqualTo("SUCCESS");
        }
    }
}
