package com.edgefabric.agentops.config;

import com.edgefabric.agentops.approval.ApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ActionExpiryScheduler {

    private final ApprovalService approvalService;

    @Scheduled(fixedDelayString = "${agentops.actions.expiry-check-interval-ms:60000}")
    public void expireStaleActions() {
        approvalService.expireStale();
    }
}
