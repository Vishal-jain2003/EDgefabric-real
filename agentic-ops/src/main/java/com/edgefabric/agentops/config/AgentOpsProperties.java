package com.edgefabric.agentops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/**
 * Externalised configuration for the agentic-ops module.
 * All URLs default to docker-compose values so local dev works out-of-the-box.
 */
@Validated
@ConfigurationProperties(prefix = "agentops")
public record AgentOpsProperties(
        String loadbalancerBaseUrl,
        String prometheusBaseUrl,
        String lokiBaseUrl,
        String tempoBaseUrl,
        LlmProperties llm,
        /**
         * Optional Docker-to-localhost IP remapping for local dev.
         * Maps a Docker-internal IP (e.g. "172.18.0.2") to the reachable
         * "host:port" string (e.g. "localhost:8081").
         * When agentic-ops runs outside Docker, the LB returns container IPs
         * which are unreachable from the host — this map fixes that.
         */
        Map<String, String> dockerNodeHostMap,
        /**
         * Maps Docker-internal IP to the Prometheus instance label used in metrics.
         * e.g. "172.18.0.2" → "cache-node-1:8082"
         * Required for per-node Prometheus queries (latency, drain, self-healing).
         */
        Map<String, String> prometheusInstanceMap,
        ActionsProperties actions
) {

    public AgentOpsProperties {
        if (loadbalancerBaseUrl == null || loadbalancerBaseUrl.isBlank()) {
            loadbalancerBaseUrl = "http://localhost:8080";
        }
        if (prometheusBaseUrl == null || prometheusBaseUrl.isBlank()) {
            prometheusBaseUrl = "http://localhost:9090";
        }
        if (lokiBaseUrl == null || lokiBaseUrl.isBlank()) {
            lokiBaseUrl = "http://localhost:3100";
        }
        if (tempoBaseUrl == null || tempoBaseUrl.isBlank()) {
            tempoBaseUrl = "http://localhost:3200";
        }
        if (llm == null) {
            llm = new LlmProperties(null, 0);
        }
        if (dockerNodeHostMap == null) {
            dockerNodeHostMap = Map.of();
        }
        if (prometheusInstanceMap == null) {
            prometheusInstanceMap = Map.of();
        }
        if (actions == null) {
            actions = new ActionsProperties(15, 60000);
        }
    }

    public record LlmProperties(
            String model,
            int maxTokens
    ) {
        public LlmProperties {
            if (model == null || model.isBlank()) {
                model = "claude-sonnet-4-5";
            }
            if (maxTokens == 0) {
                maxTokens = 2048;
            }
        }
    }

    public record ActionsProperties(
            int approvalTimeoutMinutes,
            long expiryCheckIntervalMs
    ) {
        public ActionsProperties {
            if (approvalTimeoutMinutes <= 0) approvalTimeoutMinutes = 15;
            if (expiryCheckIntervalMs <= 0) expiryCheckIntervalMs = 60000;
        }
    }
}
