package com.edgefabric.registry.controller;

import com.edgefabric.registry.dto.RegistryResponse;
import com.edgefabric.registry.service.RegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LoadBalancerController.class)
class LoadBalancerControllerTest {
    private MockMvc mockMvc;
    @MockitoBean
    private RegistryService registryService;

    private String uri = "/registry/active/nodes";


    public LoadBalancerControllerTest(@Autowired MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void shouldReturnActiveNodesSuccessfully() throws Exception {

        RegistryResponse response =
                new RegistryResponse(1L, Collections.emptyList());

        when(registryService.getRegistryState()).thenReturn(response);

        mockMvc.perform(get(uri))
                .andExpect(status().isOk());
    }



    @Test
    void shouldReturnEmptyActiveNodes() throws Exception {

        RegistryResponse response =
                new RegistryResponse(5L, Collections.emptyList());

        when(registryService.getRegistryState()).thenReturn(response);

        mockMvc.perform(get(uri))
                .andExpect(status().isOk());
    }



    @Test
    void shouldReturnInternalServerErrorWhenServiceFails() throws Exception {

        doThrow(new RuntimeException("Unexpected"))
                .when(registryService)
                .getRegistryState();

        mockMvc.perform(get(uri))
                .andExpect(status().isInternalServerError());
    }
}