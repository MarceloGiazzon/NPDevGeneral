package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.api.internal.ModelSyncStatusController;
import com.finalexec.npdev.service.internal.ModelSyncStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ModelSyncStatusControllerStandaloneTest {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        Path deployModelPath = tempDir.resolve("model.json");
        Files.writeString(
                deployModelPath,
                """
                {
                  "version": "1.0.0",
                  "concepts": [],
                  "namespace": "trial.sync"
                }
                """
        );

        ModelSyncStatusService service = new ModelSyncStatusService(deployModelPath.toString(), new ObjectMapper());
        ModelSyncStatusController controller = new ModelSyncStatusController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void syncStatusReturnsDivergedWhenModelsDiffer() throws Exception {
        mockMvc.perform(post("/api/admin/model/sync-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version": "different",
                                  "concepts": [],
                                  "namespace": "trial.sync"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inSync").value(false))
                .andExpect(jsonPath("$.status").value("diverged"));
    }

    @Test
    void syncStatusReturnsOkWhenModelsMatch() throws Exception {
        mockMvc.perform(post("/api/admin/model/sync-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"namespace\":\"trial.sync\",\"concepts\":[],\"version\":\"1.0.0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inSync").value(true))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.authoringHash").isNotEmpty())
                .andExpect(jsonPath("$.deployHash").isNotEmpty());
    }
}
