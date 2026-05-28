package com.edgefabric.agentops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the EdgeFabric Agentic Operations service.
 * Runs on port 8090 and exposes observe endpoints + MCP tool DTOs.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class AgentOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentOpsApplication.class, args);
    }
}
