package com.edgefabric.caching.controller;

import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.service.DrainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DrainControllerTest {

    private MockMvc mockMvc;

    @Mock
    private DrainService drainService;

    @Mock
    private MembershipList membershipList;

    @InjectMocks
    private DrainController controller;

    private NodeInfo self;

    @BeforeEach
    void setUp() {
        self = new NodeInfo("self-node", "127.0.0.1", 8082, 7946);
        when(membershipList.getSelf()).thenReturn(self);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void postDrainReturns200WhenSuccessful() throws Exception {
        when(drainService.startDrain()).thenReturn(true);

        mockMvc.perform(post("/internal/cluster/drain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAINING"))
                .andExpect(jsonPath("$.nodeId").value("self-node"));
    }

    @Test
    void postDrainReturns409WhenAlreadyDraining() throws Exception {
        when(drainService.startDrain()).thenReturn(false);

        mockMvc.perform(post("/internal/cluster/drain"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Node is not in ALIVE state"))
                .andExpect(jsonPath("$.nodeId").value("self-node"));
    }

    @Test
    void deleteDrainReturns200WhenSuccessful() throws Exception {
        when(drainService.cancelDrain()).thenReturn(true);

        mockMvc.perform(delete("/internal/cluster/drain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALIVE"))
                .andExpect(jsonPath("$.nodeId").value("self-node"));
    }

    @Test
    void deleteDrainReturns409WhenNotDraining() throws Exception {
        when(drainService.cancelDrain()).thenReturn(false);

        mockMvc.perform(delete("/internal/cluster/drain"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Node is not in DRAINING state"))
                .andExpect(jsonPath("$.nodeId").value("self-node"));
    }
}

