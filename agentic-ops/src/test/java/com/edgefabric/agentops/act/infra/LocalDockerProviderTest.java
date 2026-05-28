package com.edgefabric.agentops.act.infra;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class LocalDockerProviderTest {

    private LocalDockerProvider provider;

    @BeforeEach
    void setUp() {
        provider = new LocalDockerProvider();
    }

    @Test
    void provisionNode_returnsNonNullInstanceId() {
        NodeSpec spec = new NodeSpec("cache-node", "local", Map.of("env", "dev"));
        String instanceId = provider.provisionNode(spec);
        assertThat(instanceId).isNotNull().startsWith("docker-cache-node");
    }

    @Test
    void terminateNode_doesNotThrow() {
        assertThatCode(() -> provider.terminateNode("docker-test-456"))
                .doesNotThrowAnyException();
    }

    @Test
    void getProvisionStatus_returnsRunning() {
        NodeProvisionStatus status = provider.getProvisionStatus("docker-test-456");
        assertThat(status).isEqualTo(NodeProvisionStatus.RUNNING);
    }
}
