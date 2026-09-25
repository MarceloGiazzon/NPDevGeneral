package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.api.internal.ModelSyncStatusController;
import com.finalexec.npdev.service.internal.ModelSyncStatusService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ModelSyncStatusControllerStandaloneTest {

    private final RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);

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
        ModelSyncStatusController controller = new ModelSyncStatusController(service, runtimeContextService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private void asRole(String... roles) {
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("dev", "operator").withRoles(Set.of(roles)));
    }

    @Test
    void syncStatusReturnsDivergedWhenModelsDiffer() throws Exception {
        asRole("SUPERUSER");
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
        asRole("SUPERUSER");
        mockMvc.perform(post("/api/admin/model/sync-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"namespace\":\"trial.sync\",\"concepts\":[],\"version\":\"1.0.0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inSync").value(true))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.authoringHash").isNotEmpty())
                .andExpect(jsonPath("$.deployHash").isNotEmpty());
    }

    @Test
    void syncStatusForbidsCallerWithoutSuperUserRole() throws Exception {
        asRole("ADMIN");
        mockMvc.perform(post("/api/admin/model/sync-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"namespace\":\"trial.sync\",\"concepts\":[],\"version\":\"1.0.0\"}"))
                .andExpect(status().isForbidden());
    }
}
