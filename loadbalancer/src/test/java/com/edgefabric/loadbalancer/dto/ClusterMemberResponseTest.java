package com.edgefabric.loadbalancer.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClusterMemberResponseTest {

    @Test
    void allArgsConstructorAndGetters() {
        ClusterMemberResponse response = new ClusterMemberResponse("node-1", "10.0.1.1", 8082);

        assertEquals("node-1", response.getNodeId());
        assertEquals("10.0.1.1", response.getHost());
        assertEquals(8082, response.getPort());
    }

    @Test
    void noArgsConstructorAndSetters() {
        ClusterMemberResponse response = new ClusterMemberResponse();
        response.setNodeId("node-2");
        response.setHost("10.0.1.2");
        response.setPort(9090);

        assertEquals("node-2", response.getNodeId());
        assertEquals("10.0.1.2", response.getHost());
        assertEquals(9090, response.getPort());
    }

    @Test
    void equalsAndHashCode() {
        ClusterMemberResponse a = new ClusterMemberResponse("n1", "h1", 80);
        ClusterMemberResponse b = new ClusterMemberResponse("n1", "h1", 80);
        ClusterMemberResponse c = new ClusterMemberResponse("n2", "h2", 81);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    void toStringContainsFields() {
        ClusterMemberResponse response = new ClusterMemberResponse("node-1", "10.0.1.1", 8082);
        String str = response.toString();
        assertTrue(str.contains("node-1"));
        assertTrue(str.contains("10.0.1.1"));
        assertTrue(str.contains("8082"));
    }
}

