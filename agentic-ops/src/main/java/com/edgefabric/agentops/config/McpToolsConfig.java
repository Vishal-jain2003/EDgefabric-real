package com.edgefabric.agentops.config;

import com.edgefabric.agentops.alert.AlertStore;
import com.edgefabric.agentops.mcp.tools.ActionTools;
import com.edgefabric.agentops.mcp.tools.ActTools;
import com.edgefabric.agentops.observe.ObserveService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolsConfig {

    @Bean
    public ToolCallbackProvider observeToolsProvider(ObserveService observeService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(observeService)
                .build();
    }

    @Bean
    public ToolCallbackProvider alertToolsProvider(AlertStore alertStore) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(alertStore)
                .build();
    }

    @Bean
    public ToolCallbackProvider actionToolsProvider(ActionTools actionTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(actionTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider actToolsProvider(ActTools actTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(actTools)
                .build();
    }
}
