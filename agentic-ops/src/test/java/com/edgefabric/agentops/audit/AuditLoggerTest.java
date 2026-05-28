package com.edgefabric.agentops.audit;

import com.edgefabric.agentops.act.ActionStatus;
import com.edgefabric.agentops.act.ActionType;
import com.edgefabric.agentops.act.AgentAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Tests for AuditLogger to ensure audit logging is called correctly.
 * Note: These tests verify that the methods execute without errors.
 * Actual log content verification would require log capture setup.
 */
@ExtendWith(MockitoExtension.class)
class AuditLoggerTest {

    @InjectMocks
    private AuditLogger auditLogger;

    @Test
    void logDecision_approvesAction_logsSuccessfully() {
        AgentAction action = buildAction();

        assertDoesNotThrow(() -> auditLogger.logDecision(action, "APPROVED", "operator1", "looks good"));
    }

    @Test
    void logDecision_rejectsAction_logsSuccessfully() {
        AgentAction action = buildAction();

        assertDoesNotThrow(() -> auditLogger.logDecision(action, "REJECTED", "operator2", "too risky"));
    }

    @Test
    void logDecision_timesOutAction_logsSuccessfully() {
        AgentAction action = buildAction();

        assertDoesNotThrow(() -> auditLogger.logDecision(action, "TIMED_OUT", "system", "TTL exceeded"));
    }

    @Test
    void logProposal_createsAction_logsSuccessfully() {
        AgentAction action = buildAction();

        assertDoesNotThrow(() -> auditLogger.logProposal(action));
    }

    @Test
    void logProposal_withNullExpiresAt_logsSuccessfully() {
        AgentAction action = AgentAction.builder()
                .id(UUID.randomUUID().toString())
                .type(ActionType.DRAIN)
                .target("node-1")
                .status(ActionStatus.PENDING_APPROVAL)
                .reasoning("test reason")
                .proposedAt(Instant.now())
                .expiresAt(null)
                .build();

        assertDoesNotThrow(() -> auditLogger.logProposal(action));
    }

    @Test
    void logDecision_multipleDecisions_logsSuccessfully() {
        AgentAction action1 = buildAction();
        AgentAction action2 = buildAction();

        assertDoesNotThrow(() -> {
            auditLogger.logDecision(action1, "APPROVED", "operator1", "comment1");
            auditLogger.logDecision(action2, "REJECTED", "operator2", "comment2");
        });
    }

    private AgentAction buildAction() {
        return AgentAction.builder()
                .id(UUID.randomUUID().toString())
                .type(ActionType.DRAIN)
                .target("node-1")
                .status(ActionStatus.PENDING_APPROVAL)
                .reasoning("test reason")
                .proposedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }
}
