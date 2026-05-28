package com.edgefabric.caching.controller;

import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.NodeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClusterController.class)
class ClusterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MembershipList membershipList;

    @Autowired
    private ObjectMapper objectMapper;

    // 🔧 Helper method (clean test data)
    private NodeInfo createNode(String id, String host) {
        return new NodeInfo(id, host, 8082, 9092);
    }

    // ✅ 1. Successful join
    @Test
    void shouldJoinClusterAndReturnMembershipList() throws Exception {

        NodeInfo incoming = createNode("node-1", "192.168.1.10");

        List<NodeInfo> mockResponse = List.of(
                createNode("node-2", "192.168.1.1"),
                createNode("node-3", "192.168.1.2")
        );

        when(membershipList.getDigest()).thenReturn(mockResponse);

        mockMvc.perform(post("/cluster/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(incoming)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].host").value("192.168.1.1"))
                .andExpect(jsonPath("$[1].host").value("192.168.1.2"));

        verify(membershipList).merge(incoming);
    }

    // ✅ 2. Empty membership list
    @Test
    void shouldReturnEmptyListWhenNoMembers() throws Exception {

        NodeInfo incoming = createNode("node-1", "192.168.1.10");

        when(membershipList.getDigest()).thenReturn(List.of());

        mockMvc.perform(post("/cluster/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(incoming)))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(membershipList).merge(incoming);
    }

}