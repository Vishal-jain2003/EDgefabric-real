package com.edgefabric.agentops.controller;

import com.edgefabric.agentops.chat.ChatRequest;
import com.edgefabric.agentops.chat.ChatResponse;
import com.edgefabric.agentops.chat.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Agent Chat", description = "Conversational interface to the EdgeFabric cluster")
@RestController
@RequestMapping("/api/v1/agent")
public class AgentChatController {

    private final ChatService chatService;

    public AgentChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @Operation(
            summary = "Chat with the cluster agent",
            description = "Send a natural language question. The agent fetches the live cluster snapshot " +
                    "and answers using Claude AI. Example: 'What nodes are unhealthy?' or 'Should I drain any node?'")
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(chatService.chat(request.message()));
    }
}
