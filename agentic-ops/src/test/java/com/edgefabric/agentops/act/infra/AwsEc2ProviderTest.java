package com.edgefabric.agentops.act.infra;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class AwsEc2ProviderTest {

    private AwsEc2Provider provider;

    @BeforeEach
    void setUp() {
        provider = new AwsEc2Provider();
    }

    @Test
    void provisionNode_returnsStubInstanceId() {
        NodeSpec spec = new NodeSpec("cache-node", "ap-south-1", Map.of("Role", "hermes-cache-node"));
        String instanceId = provider.provisionNode(spec);
        assertThat(instanceId).isNotNull().startsWith("aws-stub-cache-node");
    }

    @Test
    void terminateNode_doesNotThrow() {
        assertThatCode(() -> provider.terminateNode("i-stub-12345"))
                .doesNotThrowAnyException();
    }

    @Test
    void getProvisionStatus_returnsRunning() {
        NodeProvisionStatus status = provider.getProvisionStatus("i-stub-12345");
        assertThat(status).isEqualTo(NodeProvisionStatus.RUNNING);
    }

    @Test
    void nodeSpec_storesFields() {
        NodeSpec spec = new NodeSpec("loadbalancer", "us-east-1", Map.of("key", "value"));
        assertThat(spec.nodeType()).isEqualTo("loadbalancer");
        assertThat(spec.region()).isEqualTo("us-east-1");
        assertThat(spec.tags()).containsEntry("key", "value");
    }

    @Test
    void nodeProvisionStatus_hasExpectedValues() {
        assertThat(NodeProvisionStatus.values()).containsExactlyInAnyOrder(
                NodeProvisionStatus.PENDING, NodeProvisionStatus.RUNNING, NodeProvisionStatus.FAILED);
    }
}
