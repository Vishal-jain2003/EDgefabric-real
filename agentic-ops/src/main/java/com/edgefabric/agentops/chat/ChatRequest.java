package com.edgefabric.agentops.chat;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(@NotBlank String message) {}
