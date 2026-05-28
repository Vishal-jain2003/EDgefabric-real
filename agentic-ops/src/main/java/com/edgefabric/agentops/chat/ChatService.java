package com.edgefabric.agentops.chat;

import com.edgefabric.agentops.observe.ClusterSnapshot;
import com.edgefabric.agentops.observe.ObserveService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final AnthropicChatModel chatModel;
    private final ObserveService observeService;
    private final ObjectMapper objectMapper;

    public ChatService(AnthropicChatModel chatModel,
                       ObserveService observeService,
                       ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.observeService = observeService;
        this.objectMapper = objectMapper;
    }

    public ChatResponse chat(String userMessage) {
        ClusterSnapshot snapshot = observeService.getSnapshot();

        String snapshotJson;
        try {
            snapshotJson = objectMapper.copy()
                    .findAndRegisterModules()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            log.warn("Failed to serialize cluster snapshot for chat prompt", exception);
            snapshotJson = "Snapshot unavailable";
        }

        String systemPrompt = """
                You are an expert EdgeFabric cluster operations assistant.
                EdgeFabric is a distributed caching and load-balancing platform.
                
                You have access to the current real-time cluster state below.
                Use this data to answer the user's questions accurately.
                Be concise, specific, and actionable.
                If a node looks unhealthy, say so clearly with the evidence.
                If everything looks fine, say so with supporting metrics.
                
                CURRENT CLUSTER STATE:
                """ + snapshotJson;

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userMessage)
        ));

        String response = chatModel.call(prompt).getResult().getOutput().getText();
        log.info("Chat response generated [messageLength={}, responseLength={}]",
                userMessage.length(), response.length());

        return new ChatResponse(response, snapshot);
    }
}
