package com.edgefabric.caching.migration;

import com.edgefabric.caching.model.NodeInfo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeInfoHashAdapterTest {

    private NodeInfo createNode(String id, String host, int servicePort, int gossipPort) {
        return new NodeInfo(id, host, servicePort, gossipPort);
    }

    @Test
    void shouldThrowWhenNodeInfoIsNull() {
        assertThatThrownBy(() -> new NodeInfoHashAdapter(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("nodeInfo must not be null");
    }

    @Test
    void shouldReturnCacheNodeIdAsNodeId() {
        NodeInfo node = createNode("cache-node-42", "10.0.0.1", 8080, 7946);
        NodeInfoHashAdapter adapter = new NodeInfoHashAdapter(node);

        assertThat(adapter.getNodeId()).isEqualTo("cache-node-42");
    }

    @Test
    void shouldDelegateGetHost() {
        NodeInfo node = createNode("n1", "192.168.1.100", 8080, 7946);
        NodeInfoHashAdapter adapter = new NodeInfoHashAdapter(node);

        assertThat(adapter.getHost()).isEqualTo("192.168.1.100");
    }

    @Test
    void shouldDelegateGetServicePort() {
        NodeInfo node = createNode("n1", "10.0.0.1", 9090, 7946);
        NodeInfoHashAdapter adapter = new NodeInfoHashAdapter(node);

        assertThat(adapter.getServicePort()).isEqualTo(9090);
    }

    @Test
    void shouldExposeUnderlyingNodeInfo() {
        NodeInfo node = createNode("n1", "10.0.0.1", 8080, 7946);
        NodeInfoHashAdapter adapter = new NodeInfoHashAdapter(node);

        assertThat(adapter.getNodeInfo()).isSameAs(node);
    }

    @Test
    void shouldBeEqualWhenSameNodeId() {
        NodeInfo node1 = createNode("same-id", "10.0.0.1", 8080, 7946);
        NodeInfo node2 = createNode("same-id", "10.0.0.2", 9090, 7947);

        NodeInfoHashAdapter adapter1 = new NodeInfoHashAdapter(node1);
        NodeInfoHashAdapter adapter2 = new NodeInfoHashAdapter(node2);

        assertThat(adapter1).isEqualTo(adapter2);
    }

    @Test
    void shouldNotBeEqualWhenDifferentNodeId() {
        NodeInfo node1 = createNode("id-1", "10.0.0.1", 8080, 7946);
        NodeInfo node2 = createNode("id-2", "10.0.0.1", 8080, 7946);

        NodeInfoHashAdapter adapter1 = new NodeInfoHashAdapter(node1);
        NodeInfoHashAdapter adapter2 = new NodeInfoHashAdapter(node2);

        assertThat(adapter1).isNotEqualTo(adapter2);
    }

    @Test
    void shouldHaveSameHashCodeWhenSameNodeId() {
        NodeInfo node1 = createNode("same-id", "10.0.0.1", 8080, 7946);
        NodeInfo node2 = createNode("same-id", "10.0.0.2", 9090, 7947);

        NodeInfoHashAdapter adapter1 = new NodeInfoHashAdapter(node1);
        NodeInfoHashAdapter adapter2 = new NodeInfoHashAdapter(node2);

        assertThat(adapter1).hasSameHashCodeAs(adapter2);
    }

    @Test
    void shouldHaveDifferentHashCodeWhenDifferentNodeId() {
        NodeInfo node1 = createNode("id-1", "10.0.0.1", 8080, 7946);
        NodeInfo node2 = createNode("id-2", "10.0.0.1", 8080, 7946);

        NodeInfoHashAdapter adapter1 = new NodeInfoHashAdapter(node1);
        NodeInfoHashAdapter adapter2 = new NodeInfoHashAdapter(node2);

        assertThat(adapter1.hashCode()).isNotEqualTo(adapter2.hashCode());
    }

    @Test
    void shouldNotBeEqualToNull() {
        NodeInfo node = createNode("n1", "10.0.0.1", 8080, 7946);
        NodeInfoHashAdapter adapter = new NodeInfoHashAdapter(node);

        assertThat(adapter).isNotEqualTo(null);
    }

    @Test
    void shouldNotBeEqualToDifferentType() {
        NodeInfo node = createNode("n1", "10.0.0.1", 8080, 7946);
        NodeInfoHashAdapter adapter = new NodeInfoHashAdapter(node);

        assertThat(adapter).isNotEqualTo("not-an-adapter");
    }

    @Test
    void shouldBeEqualToItself() {
        NodeInfo node = createNode("n1", "10.0.0.1", 8080, 7946);
        NodeInfoHashAdapter adapter = new NodeInfoHashAdapter(node);

        assertThat(adapter).isEqualTo(adapter);
    }

    @Test
    void shouldProduceReadableToString() {
        NodeInfo node = createNode("cache-node-7", "10.0.0.7", 8082, 7946);
        NodeInfoHashAdapter adapter = new NodeInfoHashAdapter(node);

        String result = adapter.toString();

        assertThat(result)
                .contains("cache-node-7")
                .contains("10.0.0.7")
                .contains("8082");
    }
}
