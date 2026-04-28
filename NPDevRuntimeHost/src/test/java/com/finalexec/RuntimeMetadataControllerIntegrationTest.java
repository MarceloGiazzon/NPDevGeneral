package com.finalexec;

import com.finalexec.api.RuntimeMetadataController;
import com.finalexec.npdev.service.RuntimeMetadataService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RuntimeMetadataControllerIntegrationTest {

    private static final String API_KEY = "dev-key";

    private MockMvc mockMvc;
    private final RuntimeMetadataService runtimeMetadataService = new RuntimeMetadataService(new com.fasterxml.jackson.databind.ObjectMapper());
    private final RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
    private final ExecutionContext executionContext = Mockito.mock(ExecutionContext.class);

    @BeforeEach
    void setUp() {
        when(runtimeContextService.currentContext(any())).thenReturn(executionContext);
        when(executionContext.hasRole("ADMIN")).thenReturn(true);
        RuntimeMetadataController controller = new RuntimeMetadataController(runtimeMetadataService, runtimeContextService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void exposesRuntimeMetadataOverviewAndCatalogEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/runtime/metadata")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceType").value("generated-runtime-metadata"))
                .andExpect(jsonPath("$.namespace").value("canonical.clinicdemo"))
                .andExpect(jsonPath("$.catalogCount").value(9))
                .andExpect(jsonPath("$.catalogs[0].name").value("concepts"));

        mockMvc.perform(get("/api/admin/runtime/metadata/concepts/Appointment")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filteredCount").value(1))
                .andExpect(jsonPath("$.concept.name").value("Appointment"))
                .andExpect(jsonPath("$.relatedCatalogCounts.fields").value(9));

        mockMvc.perform(get("/api/admin/runtime/metadata/actions")
                        .header("X-Api-Key", API_KEY)
                        .param("concept", "Appointment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedCatalog").value("actions"))
                .andExpect(jsonPath("$.actionKinds").isArray())
                .andExpect(jsonPath("$.items[?(@.name=='CreateAppointment')]").exists());

        mockMvc.perform(get("/api/admin/runtime/metadata/procedures")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedCatalog").value("procedures"))
                .andExpect(jsonPath("$.items").isArray());

        mockMvc.perform(get("/api/admin/runtime/metadata/panels")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedCatalog").value("panels"))
                .andExpect(jsonPath("$.items").isArray());

        mockMvc.perform(get("/api/admin/runtime/metadata/preview/Appointment")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewSupport.tabs[0]").value("Overview"))
                .andExpect(jsonPath("$.previewSupport.referencePickers[0].fieldPath").value("patientId"))
                .andExpect(jsonPath("$.previewSupport.actionLabels[0].label").isNotEmpty());
    }
}
