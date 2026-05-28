package com.edgefabric.agentops.approval;

import com.edgefabric.agentops.act.ActionProposer;
import com.edgefabric.agentops.act.ActionRepository;
import com.edgefabric.agentops.act.ActionStatus;
import com.edgefabric.agentops.act.ActionType;
import com.edgefabric.agentops.act.AgentAction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ApprovalController.class)
class ApprovalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ActionRepository actionRepository;

    @MockBean
    private ApprovalService approvalService;

    @MockBean
    private ActionProposer actionProposer;

    @Test
    void getActions_returns200WithList() throws Exception {
        AgentAction action = buildAction(ActionStatus.PENDING_APPROVAL);
        when(actionRepository.findAll()).thenReturn(List.of(action));

        mockMvc.perform(get("/api/v1/actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(action.getId()))
                .andExpect(jsonPath("$[0].status").value("PENDING_APPROVAL"));
    }

    @Test
    void getActionById_found_returns200() throws Exception {
        AgentAction action = buildAction(ActionStatus.PENDING_APPROVAL);
        when(actionRepository.findById(action.getId())).thenReturn(Optional.of(action));

        mockMvc.perform(get("/api/v1/actions/" + action.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(action.getId()));
    }

    @Test
    void getActionById_notFound_returns404() throws Exception {
        when(actionRepository.findById("unknown-id")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/actions/unknown-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createAction_validBody_returns201() throws Exception {
        AgentAction action = buildAction(ActionStatus.PENDING_APPROVAL);
        when(actionProposer.proposeManual(eq("DRAIN"), eq("node-1"), any(), any()))
                .thenReturn(action);

        String body = """
                {
                    "type": "DRAIN",
                    "target": "node-1",
                    "reasoning": "manual override",
                    "plan": ["step1", "step2"]
                }
                """;

        mockMvc.perform(post("/api/v1/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(action.getId()))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }

    @Test
    void approveAction_delegatesToService_returns200() throws Exception {
        AgentAction approved = buildAction(ActionStatus.APPROVED);
        when(approvalService.approve(eq(approved.getId()), eq("operator1"), eq("looks good")))
                .thenReturn(approved);

        String body = """
                {
                    "operatorUsername": "operator1",
                    "comment": "looks good"
                }
                """;

        mockMvc.perform(post("/api/v1/actions/" + approved.getId() + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void rejectAction_delegatesToService_returns200() throws Exception {
        AgentAction rejected = buildAction(ActionStatus.REJECTED);
        when(approvalService.reject(eq(rejected.getId()), eq("operator2"), eq("too risky")))
                .thenReturn(rejected);

        String body = """
                {
                    "operatorUsername": "operator2",
                    "reason": "too risky"
                }
                """;

        mockMvc.perform(post("/api/v1/actions/" + rejected.getId() + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void getActions_withStatusFilter_returns200() throws Exception {
        AgentAction pending = buildAction(ActionStatus.PENDING_APPROVAL);
        when(actionRepository.findByStatus(ActionStatus.PENDING_APPROVAL)).thenReturn(List.of(pending));

        mockMvc.perform(get("/api/v1/actions?status=PENDING_APPROVAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING_APPROVAL"));
    }

    @Test
    void getActions_withInvalidStatusFilter_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/actions?status=INVALID_STATUS"))
                .andExpect(status().isBadRequest());
    }

    private AgentAction buildAction(ActionStatus status) {
        return AgentAction.builder()
                .id(UUID.randomUUID().toString())
                .type(ActionType.DRAIN)
                .target("node-1")
                .status(status)
                .reasoning("test reason")
                .proposedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }
}
