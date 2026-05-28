package com.edgefabric.agentops.act.infra;

import java.util.Map;

/**
 * Specification for provisioning a new infrastructure node.
 */
public record NodeSpec(
        String nodeType,
        String region,
        Map<String, String> tags
) {}
