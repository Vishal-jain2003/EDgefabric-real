package com.edgefabric.agentops.chat;

import com.edgefabric.agentops.observe.ClusterSnapshot;
import com.edgefabric.agentops.observe.LoadBalancerSnapshot;
import com.edgefabric.agentops.observe.ObserveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private AnthropicChatModel chatModel;

    @Mock
    private ObserveService observeService;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(chatModel, observeService, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void chat_includesSnapshotInPromptAndReturnsModelResponse() {
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
        when(observeService.getSnapshot()).thenReturn(snapshot);
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage("Cluster is healthy")))));

        com.edgefabric.agentops.chat.ChatResponse response = chatService.chat("What nodes are unhealthy?");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(observeService).getSnapshot();
        verify(chatModel).call(promptCaptor.capture());

        Prompt prompt = promptCaptor.getValue();
        assertThat(prompt.getInstructions()).hasSize(2);
        assertThat(prompt.getInstructions().getFirst().getText()).contains("CURRENT CLUSTER STATE:");
        assertThat(prompt.getInstructions().getFirst().getText()).contains("\"totalNodes\" : 1");
        assertThat(prompt.getInstructions().get(1).getText()).isEqualTo("What nodes are unhealthy?");
        assertThat(response.response()).isEqualTo("Cluster is healthy");
        assertThat(response.snapshot()).isEqualTo(snapshot);
    }
}
