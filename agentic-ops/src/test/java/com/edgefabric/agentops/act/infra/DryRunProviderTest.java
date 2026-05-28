package com.edgefabric.agentops.act.infra;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DryRunProviderTest {

    private DryRunProvider provider;

    @BeforeEach
    void setUp() {
        provider = new DryRunProvider();
    }

    @Test
    void provisionNode_returnsNonNullInstanceId() {
        NodeSpec spec = new NodeSpec("cache-node", "ap-south-1", Map.of("Role", "hermes-cache-node"));
        String instanceId = provider.provisionNode(spec);
        assertThat(instanceId).isNotNull().startsWith("dry-run-cache-node");
    }

    @Test
    void terminateNode_doesNotThrow() {
        assertThatCode(() -> provider.terminateNode("dry-run-test-123"))
                .doesNotThrowAnyException();
    }

    @Test
    void getProvisionStatus_returnsRunning() {
        NodeProvisionStatus status = provider.getProvisionStatus("dry-run-test-123");
        assertThat(status).isEqualTo(NodeProvisionStatus.RUNNING);
    }
}
