package com.edgefabric.agentops.act;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.http.HttpStatus.*;

@WebMvcTest(ActController.class)
class ActControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ActOrchestrator orchestrator;

    @MockitoBean
    private RollbackExecutor rollbackExecutor;

    private ExecutionRecord buildSuccessRecord(String actionId) {
        ExecutionStep step = new ExecutionStep("step1_pre_validation", ExecutionStatus.SUCCESS, 50L, null);
        return ExecutionRecord.builder()
                .actionId(actionId)
                .actionType(ActionType.DRAIN)
                .target("node-1")
                .mode(ExecutionMode.AUTOMATIC)
                .status(ExecutionStatus.SUCCESS)
                .startedAt(Instant.now().minusSeconds(1))
                .completedAt(Instant.now())
                .steps(List.of(step))
                .rollbackStrategy(RollbackStrategy.UNDO)
                .build();
    }

    @Test
    void execute_approvedAction_returns200WithRecord() throws Exception {
        ExecutionRecord execRecord = buildSuccessRecord("action-123");
        when(orchestrator.execute("action-123")).thenReturn(execRecord);

        mockMvc.perform(post("/api/v1/actions/action-123/execute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionId").value("action-123"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void execute_notFound_returns404() throws Exception {
        when(orchestrator.execute("not-found"))
                .thenThrow(new ResponseStatusException(NOT_FOUND, "Action 'not-found' not found"));

        mockMvc.perform(post("/api/v1/actions/not-found/execute"))
                .andExpect(status().isNotFound());
    }

    @Test
    void execute_notApproved_returns409() throws Exception {
        when(orchestrator.execute("pending-1"))
                .thenThrow(new ResponseStatusException(CONFLICT, "Action is not APPROVED — status: PENDING_APPROVAL"));

        mockMvc.perform(post("/api/v1/actions/pending-1/execute"))
                .andExpect(status().isConflict());
    }

    @Test
    void rollback_executedAction_returns200WithReport() throws Exception {
        RollbackReport report = new RollbackReport(
                "action-456", "operator", "test reason",
                2, 2, 0,
                Instant.now().minusSeconds(1), Instant.now(), true
        );
        when(rollbackExecutor.rollback(eq("action-456"), any(), any())).thenReturn(report);

        String body = """
                {"reason": "test reason"}
                """;

        mockMvc.perform(post("/api/v1/actions/action-456/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionId").value("action-456"))
                .andExpect(jsonPath("$.succeeded").value(true));
    }

    @Test
    void rollback_missingReason_returns200WithDefaultReason() throws Exception {
        RollbackReport report = new RollbackReport(
                "action-789", "anonymous", "manual rollback",
                1, 1, 0,
                Instant.now().minusSeconds(1), Instant.now(), true
        );
        when(rollbackExecutor.rollback(eq("action-789"), any(), any())).thenReturn(report);

        // Empty body — reason defaults to "manual rollback"
        mockMvc.perform(post("/api/v1/actions/action-789/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void rollback_notFound_returns404() throws Exception {
        when(rollbackExecutor.rollback(eq("missing"), any(), any()))
                .thenThrow(new ResponseStatusException(NOT_FOUND, "Action 'missing' not found"));

        mockMvc.perform(post("/api/v1/actions/missing/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"test\"}"))
                .andExpect(status().isNotFound());
    }
}
