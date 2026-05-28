package com.edgefabric.registry.controller;

import com.edgefabric.registry.exceptions.NodeAlreadyRegisteredException;
import com.edgefabric.registry.service.RegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CacheNodeController.class)
class CacheControllerTest {

    private final MockMvc mockMvc;
    @MockitoBean
    private RegistryService registryService;


    public CacheControllerTest(@Autowired MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void shouldRegisterNodeSuccessfully() throws Exception {
        mockMvc.perform(post("/registry/node")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                             {
                                "cacheNodeId": "node1",
                                "host": "127.0.0.1",
                                "port": 8080
                             }
                             """))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldReturnConflictWhenNodeAlreadyRegistered() throws Exception {

        doThrow(new NodeAlreadyRegisteredException("Node already registered"))
                .when(registryService)
                .register(org.mockito.ArgumentMatchers.any());

        mockMvc.perform(post("/registry/node")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {
                       "cacheNodeId": "node1",
                       "host": "127.0.0.1",
                       "port": 8080
                    }
                    """))
                .andExpect(status().isConflict());
    }


    @Test
    void shouldReturnBadRequestWhenValidationFails() throws Exception {

        mockMvc.perform(post("/registry/node")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {
                      "host": "127.0.0.1",
                      "port": 8080
                    }
                    """))
                .andExpect(status().isBadRequest());
    }



    @Test
    void shouldReturnBadRequestForMalformedJson() throws Exception {

        mockMvc.perform(post("/registry/node")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cacheNodeId": "node1",
                                  "host": "127.0.0.1",
                                  "port":
                                }
                                """))
                .andExpect(status().isBadRequest());
    }



    @Test
    void shouldReturnInternalServerErrorForUnexpectedException() throws Exception {
        doThrow(new RuntimeException("Unexpected error"))
                .when(registryService)
                .register(org.mockito.ArgumentMatchers.any());

        mockMvc.perform(post("/registry/node")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cacheNodeId": "node1",
                                  "host": "127.0.0.1",
                                  "port": 8080
                                }
                                """))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void shouldReturnInternalServerErrorWhenDeregisterFails() throws Exception {

        doThrow(new RuntimeException("Unexpected error"))
                .when(registryService)
                .deregister("node1");

        mockMvc.perform(delete("/registry/node/{cacheNodeId}", "node1"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void shouldDeregisterNodeSuccessfully() throws Exception {
        mockMvc.perform(delete("/registry/node/{cacheNodeId}", "node1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Node de-registered successfully"));

        verify(registryService).deregister("node1");
    }


    @Test
    void shouldReturnBadRequestWhenCacheNodeIdIsBlank() throws Exception {

        String blankCacheNodeId = "   ";

        mockMvc.perform(delete("/registry/node/{cacheNodeId}", blankCacheNodeId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("cacheNodeId must not be blank"));

        verify(registryService, never()).deregister(anyString());
    }

    @Test
    void shouldUpdateHeartbeatSuccessfully() throws Exception {

        mockMvc.perform(post("/registry/node/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"node1\""))
                .andExpect(status().isOk());
    }



    @Test
    void shouldCallServiceToUpdateHeartbeat() throws Exception {

        mockMvc.perform(post("/registry/node/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"node2\""))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(registryService)
                .processHeartbeat("node2");
    }
}