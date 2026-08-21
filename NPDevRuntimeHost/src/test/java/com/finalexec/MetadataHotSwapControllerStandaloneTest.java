package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.api.MetadataHotSwapController;
import com.finalexec.npdev.service.RuntimeMetadataService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R1.7 (roadmap Wave 1, "hot metadata swap"): the authenticated HTTP surface over
 * {@link RuntimeMetadataService#applyMetadataOnlyReload}. Mirrors
 * {@code RuntimeMetadataControllerStandaloneTest}'s standalone-MockMvc shape.
 */
class MetadataHotSwapControllerStandaloneTest {

    @TempDir
    Path appExternalRoot;

    private MockMvc mockMvc;
    private RuntimeMetadataService runtimeMetadataService;
    private final RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
    private final ExecutionContext executionContext = Mockito.mock(ExecutionContext.class);

    @BeforeEach
    void setUp() throws IOException {
        Path generatedResourcesRoot = appExternalRoot.resolve("npdev-generated/src/main/resources");
        writeFixture(generatedResourcesRoot.resolve("npdev/compiled-metadata.json"), compiledMetadataJson("old-namespace"));
        writeFixture(generatedResourcesRoot.resolve("npdev/metadata/index.json"), indexJson());
        writeFixture(generatedResourcesRoot.resolve("npdev/metadata/concepts.manifest.json"), conceptsManifestJson("Old Label"));

        runtimeMetadataService = new RuntimeMetadataService(
                new ObjectMapper(),
                generatedResourcesRoot.resolve("npdev/compiled-metadata.json").toString(),
                generatedResourcesRoot.resolve("npdev/metadata/index.json").toString(),
                generatedResourcesRoot.toString());

        when(runtimeContextService.currentContext(any())).thenReturn(executionContext);

        MetadataHotSwapController controller = new MetadataHotSwapController(runtimeMetadataService, runtimeContextService, new com.finalexec.config.ModelHolder());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void applyRequiresSuperUserNotJustAdmin() throws Exception {
        when(executionContext.hasRole("SUPERUSER")).thenReturn(false);
        when(executionContext.hasRole("ADMIN")).thenReturn(true); // ADMIN alone must NOT be enough

        mockMvc.perform(post("/api/admin/runtime/metadata-hotswap/apply")
                        .contentType("application/json")
                        .content("{\"classification\":\"METADATA_ONLY\",\"classificationReasons\":[],\"metadataSourceRoot\":\""
                                + escapeJson(appExternalRoot.toString()) + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void applyRefusesWhenClassificationIsNotMetadataOnly() throws Exception {
        when(executionContext.hasRole("SUPERUSER")).thenReturn(true);
        Path sourceRoot = stageNewMetadata("attempted", "Attempted Label");

        mockMvc.perform(post("/api/admin/runtime/metadata-hotswap/apply")
                        .contentType("application/json")
                        .content("{\"classification\":\"MANUAL_REVIEW\",\"classificationReasons\":[\"dropped a column\"],\"metadataSourceRoot\":\""
                                + escapeJson(sourceRoot.toString()) + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value("NOT_METADATA_ONLY"));

        // Nothing touched -- the pre-existing catalog is still visible unchanged.
        assertOverviewNamespace("old-namespace");
    }

    @Test
    void applySwapsMetadataLiveAndStatusReflectsIt() throws Exception {
        when(executionContext.hasRole("SUPERUSER")).thenReturn(true);
        when(executionContext.hasRole("ADMIN")).thenReturn(true);
        Path sourceRoot = stageNewMetadata("swapped", "Swapped Label");

        mockMvc.perform(post("/api/admin/runtime/metadata-hotswap/apply")
                        .contentType("application/json")
                        .content("{\"classification\":\"METADATA_ONLY\",\"classificationReasons\":[\"no schema-shaped change\"],\"metadataSourceRoot\":\""
                                + escapeJson(sourceRoot.toString()) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.metadataGeneration").value(1))
                .andExpect(jsonPath("$.catalogsUpdated.length()").value(3));

        assertOverviewNamespace("swapped");

        mockMvc.perform(get("/api/admin/runtime/metadata-hotswap/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadataGeneration").value(1));
    }

    @Test
    void statusRequiresAdminContext() throws Exception {
        when(executionContext.hasRole("ADMIN")).thenReturn(false);
        mockMvc.perform(get("/api/admin/runtime/metadata-hotswap/status"))
                .andExpect(status().isForbidden());
    }

    private void assertOverviewNamespace(String expected) {
        String namespace = String.valueOf(runtimeMetadataService.overview().get("namespace"));
        org.junit.jupiter.api.Assertions.assertEquals(expected, namespace);
    }

    private Path stageNewMetadata(String namespace, String label) throws IOException {
        Path sourceRoot = appExternalRoot.resolve("reload-source-" + namespace);
        writeFixture(sourceRoot.resolve("src/main/resources/npdev/compiled-metadata.json"), compiledMetadataJson(namespace));
        writeFixture(sourceRoot.resolve("src/main/resources/npdev/metadata/index.json"), indexJson());
        writeFixture(sourceRoot.resolve("src/main/resources/npdev/metadata/concepts.manifest.json"), conceptsManifestJson(label));
        return sourceRoot;
    }

    private static void writeFixture(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String compiledMetadataJson(String namespace) {
        return "{"
                + "\"namespace\":\"" + namespace + "\","
                + "\"dslVersion\":\"2.0\","
                + "\"version\":\"1\","
                + "\"catalogs\":{\"concepts\":{}}"
                + "}";
    }

    private static String indexJson() {
        return "{"
                + "\"metadataManifestVersion\":\"1.0.0\","
                + "\"metadataVersion\":\"1.0.0\","
                + "\"catalogs\":[{\"name\":\"concepts\",\"path\":\"npdev/metadata/concepts.manifest.json\",\"count\":1}]"
                + "}";
    }

    private static String conceptsManifestJson(String label) {
        return "{"
                + "\"metadataManifestVersion\":\"1.0.0\","
                + "\"metadataVersion\":\"1.0.0\","
                + "\"catalog\":\"concepts\","
                + "\"items\":[{\"name\":\"Thing\",\"label\":\"" + label + "\"}]"
                + "}";
    }
}
