package com.edgefabric.agentops.act.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * AWS EC2-based InfrastructureProvider for production.
 * Active when neither "test" nor "docker" profile is active.
 * Phase 1 stub — AWS SDK integration deferred to Phase 2 (EPMICMPHE-88).
 */
@Slf4j
@Component
@Profile("!test & !docker")
public class AwsEc2Provider implements InfrastructureProvider {

    @Override
    public String provisionNode(NodeSpec spec) {
        log.warn("[AwsEc2Provider] provisionNode is a Phase 1 stub — AWS SDK not yet integrated. spec={}", spec);
        return "aws-stub-" + spec.nodeType() + "-" + System.currentTimeMillis();
    }

    @Override
    public void terminateNode(String instanceId) {
        log.warn("[AwsEc2Provider] terminateNode is a Phase 1 stub — instanceId={}", instanceId);
    }

    @Override
    public NodeProvisionStatus getProvisionStatus(String instanceId) {
        log.warn("[AwsEc2Provider] getProvisionStatus is a Phase 1 stub — instanceId={}", instanceId);
        return NodeProvisionStatus.RUNNING;
    }
}
