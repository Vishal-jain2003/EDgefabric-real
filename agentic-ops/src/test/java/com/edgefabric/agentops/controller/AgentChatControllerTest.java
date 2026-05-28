package com.edgefabric.agentops.controller;

import com.edgefabric.agentops.chat.ChatResponse;
import com.edgefabric.agentops.chat.ChatService;
import com.edgefabric.agentops.observe.ClusterSnapshot;
import com.edgefabric.agentops.observe.LoadBalancerSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentChatController.class)
class AgentChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatService chatService;

    @Test
    void chat_returns200WithChatResponse() throws Exception {
        ClusterSnapshot snapshot = new ClusterSnapshot(
                1,
                1,
                0,
                0,
                Instant.parse("2026-05-21T09:00:00Z"),
                new LoadBalancerSnapshot("UP", 1, 128, 128, "xxhash", List.of()),
                List.of(),
                0L, 0L, 0.0, null, null, null
        );
        when(chatService.chat("What nodes are unhealthy?"))
                .thenReturn(new ChatResponse("All healthy", snapshot));

        mockMvc.perform(post("/api/v1/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"What nodes are unhealthy?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value("All healthy"))
                .andExpect(jsonPath("$.snapshot.totalNodes").value(1))
                .andExpect(jsonPath("$.snapshot.loadBalancer.status").value("UP"));

        verify(chatService).chat("What nodes are unhealthy?");
    }
}
