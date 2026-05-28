package com.edgefabric.agentops.config;

import com.edgefabric.agentops.approval.ApprovalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ActionExpirySchedulerTest {

    @Mock
    private ApprovalService approvalService;

    @InjectMocks
    private ActionExpiryScheduler scheduler;

    @Test
    void expireStaleActions_callsApprovalServiceExpireStale() {
        scheduler.expireStaleActions();

        verify(approvalService).expireStale();
    }
}
