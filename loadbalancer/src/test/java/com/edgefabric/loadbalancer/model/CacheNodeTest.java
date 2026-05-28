package com.edgefabric.loadbalancer.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CacheNodeTest {

    @Test
    void sameNodeIdShouldBeEqual() {
        CacheNode n1 = new CacheNode("node-1", "localhost", 8081);
        CacheNode n2 = new CacheNode("node-1", "localhost", 8082);

        assertEquals(n1, n2);
        assertEquals(n1.hashCode(), n2.hashCode());
    }

    @Test
    void differentNodeIdShouldNotBeEqual() {
        CacheNode n1 = new CacheNode("node-1", "localhost", 8081);
        CacheNode n2 = new CacheNode("node-2", "localhost", 8082);

        assertNotEquals(n1, n2);
    }

    @Test
    void shouldNotEqualNullOrDifferentType() {
        CacheNode node = new CacheNode("node-1", "localhost", 8081);

        assertNotEquals(null,node);
        assertNotEquals( "node-1",node);
    }

    @Test
    void shouldReturnNodeId() {
        CacheNode node = new CacheNode("node-1", "localhost", 8081);
        assertEquals("node-1", node.getNodeId());
    }

    @Test
    void shouldReturnHostAndPort() {
        CacheNode node = new CacheNode("node-1", "10.0.1.1", 9090);
        assertEquals("10.0.1.1", node.getHost());
        assertEquals(9090, node.getPort());
    }

    @Test
    void toStringShouldContainNodeIdHostAndPort() {
        CacheNode node = new CacheNode("node-1", "10.0.1.1", 8081);
        String str = node.toString();
        assertTrue(str.contains("node-1"));
        assertTrue(str.contains("10.0.1.1"));
        assertTrue(str.contains("8081"));
    }

    @Test
    void sameReferenceShouldBeEqual() {
        CacheNode node = new CacheNode("node-1", "localhost", 8081);
        assertEquals(node, node);
    }
}
