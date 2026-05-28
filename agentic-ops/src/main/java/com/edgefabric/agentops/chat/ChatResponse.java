package com.edgefabric.agentops.chat;

import com.edgefabric.agentops.observe.ClusterSnapshot;

public record ChatResponse(
        String response,
        ClusterSnapshot snapshot
) {}
