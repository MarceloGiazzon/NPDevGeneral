package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.api.FileUploadController;
import com.npdev.adapters.filestore.inproc.FileSystemFileStoreAdapter;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledFileMetadata;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenant isolation of the file endpoints. The ownership check used to compare only the first path
 * segment of the key to the caller's tenant, so a tenant-acme caller sending
 * {@code acme/../globex/<uuid>} passed the check, and the store normalised the path to globex's file and
 * served or deleted it. Every key the stores issue is exactly {@code <tenantSegment>/<uuid>}; anything else
 * must be "not found".
 */
class FileUploadControllerTenantTraversalStandaloneTest {

    @TempDir
    Path tempRoot;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> tenant = new AtomicReference<>("globex");
    private MockMvc mockMvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
        when(runtimeContextService.currentContext(any()))
                .thenAnswer(invocation -> ExecutionContext.of(tenant.get(), "tester"));

        CompiledField idField = new CompiledField("id", "uuid", "java.util.UUID", true, true, false);
        CompiledField attachmentField = new CompiledField(
                "attachment", "file", "com.npdev.kernel.ports.FileHandle",
                false, false, false,
                List.of(), null, null, null, null, List.of(), null, null, null,
                new CompiledFileMetadata(List.of("text/plain"), null, false)
        );
        CompiledConcept doc = new CompiledConcept("Doc", "Doc", "docs", List.of(idField, attachmentField));
        CompiledModel model = new CompiledModel("tenant.trav", "1.0.0", "1.0.0", Map.of(doc.getName(), doc));

        ObjectProvider<CompiledModel> compiledModelProvider = Mockito.mock(ObjectProvider.class);
        when(compiledModelProvider.getIfAvailable()).thenReturn(model);

        mockMvc = MockMvcBuilders.standaloneSetup(new FileUploadController(
                new FileSystemFileStoreAdapter(tempRoot), compiledModelProvider, runtimeContextService)).build();
    }

    private Map<String, Object> uploadAs(String tenantId) throws Exception {
        tenant.set(tenantId);
        MockMultipartFile file = new MockMultipartFile(
                "file", "secret.txt", "text/plain", ("secret of " + tenantId).getBytes(StandardCharsets.UTF_8));
        String json = mockMvc.perform(multipart("/api/files/Doc/attachment").file(file))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(json, Map.class);
    }

    @Test
    void aTenantStillReadsItsOwnFile() throws Exception {
        Map<String, Object> handle = uploadAs("acme");
        mockMvc.perform(get("/api/files")
                        .param("storeId", (String) handle.get("storeId"))
                        .param("key", (String) handle.get("key")))
                .andExpect(status().isOk());
    }

    @Test
    void dotDotCannotReachAnotherTenantsFile() throws Exception {
        Map<String, Object> victim = uploadAs("globex");
        String victimUuid = ((String) victim.get("key")).substring("globex/".length());

        tenant.set("acme");
        mockMvc.perform(get("/api/files")
                        .param("storeId", (String) victim.get("storeId"))
                        .param("key", "acme/../globex/" + victimUuid))
                .andExpect(status().isNotFound());
    }

    @Test
    void dotDotCannotDeleteAnotherTenantsFile() throws Exception {
        Map<String, Object> victim = uploadAs("globex");
        String victimUuid = ((String) victim.get("key")).substring("globex/".length());

        tenant.set("acme");
        mockMvc.perform(delete("/api/files")
                        .param("storeId", (String) victim.get("storeId"))
                        .param("key", "acme/../globex/" + victimUuid))
                .andExpect(status().isNotFound());

        tenant.set("globex");
        mockMvc.perform(get("/api/files")
                        .param("storeId", (String) victim.get("storeId"))
                        .param("key", (String) victim.get("key")))
                .andExpect(status().isOk());
    }

    @Test
    void keysThatAreNotExactlyTenantSlashIdAreNotFound() throws Exception {
        Map<String, Object> mine = uploadAs("acme");
        String uuid = ((String) mine.get("key")).substring("acme/".length());
        for (String badKey : new String[] {
                "acme/./" + uuid, "acme//" + uuid, "acme/" + uuid + "/", "acme/sub/" + uuid,
                "acme\\..\\globex\\" + uuid, "acme/..", "acme/" + uuid + "/..", uuid, ""}) {
            mockMvc.perform(get("/api/files")
                            .param("storeId", (String) mine.get("storeId"))
                            .param("key", badKey))
                    .andExpect(status().isNotFound());
        }
    }
}
