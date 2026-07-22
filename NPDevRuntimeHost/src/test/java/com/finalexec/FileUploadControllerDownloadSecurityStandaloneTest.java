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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HARDEN-DL-P3: proves the download path is inert against a stored-XSS payload -- an HTML file
 * that slips past an upload allowlist must never come back {@code inline} with an attacker-chosen
 * content-type, regardless of what the download request claims.
 */
class FileUploadControllerDownloadSecurityStandaloneTest {

    @TempDir
    Path tempRoot;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
    private MockMvc mockMvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(runtimeContextService.currentContext(any())).thenReturn(ExecutionContext.of("acme", "tester"));

        CompiledField idField = new CompiledField("id", "uuid", "java.util.UUID", true, true, false);
        CompiledField attachmentField = new CompiledField(
                "attachment", "file", "com.npdev.kernel.ports.FileHandle",
                false, false, false,
                List.of(), null, null, null, null, List.of(), null, null, null,
                new CompiledFileMetadata(List.of("text/html", "image/png"), null, false)
        );
        CompiledConcept doc = new CompiledConcept("Doc", "Doc", "docs", List.of(idField, attachmentField));
        CompiledModel model = new CompiledModel("harden.dl", "1.0.0", "1.0.0", Map.of(doc.getName(), doc));

        ObjectProvider<CompiledModel> compiledModelProvider = Mockito.mock(ObjectProvider.class);
        when(compiledModelProvider.getIfAvailable()).thenReturn(model);

        FileUploadController controller = new FileUploadController(
                new FileSystemFileStoreAdapter(tempRoot), compiledModelProvider, runtimeContextService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void htmlPayloadRoundTripsAsAnInertAttachmentEvenWhenTheRequestClaimsOtherwise() throws Exception {
        byte[] payload = "<script>alert(document.cookie)</script>".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "payload.html", "text/html", payload);

        String uploadJson = mockMvc.perform(multipart("/api/files/Doc/attachment").file(file))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> handle = objectMapper.readValue(uploadJson, Map.class);

        // A tampered contentType/originalName on the download request must have zero effect -- the
        // controller no longer binds those params at all, so this is a strict superset check that
        // no future regression re-adds trust in caller-supplied type/name.
        mockMvc.perform(get("/api/files")
                        .param("storeId", (String) handle.get("storeId"))
                        .param("key", (String) handle.get("key"))
                        .param("contentType", "image/png")
                        .param("originalName", "totally-safe.png"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/html"))
                .andExpect(header().string("Content-Disposition", startsWithAttachment()))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
    }

    @Test
    void inlineSafeImageTypeStaysInlineButStillCarriesTheHardeningHeaders() throws Exception {
        byte[] pngBytes = {(byte) 0x89, 'P', 'N', 'G'};
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", pngBytes);

        String uploadJson = mockMvc.perform(multipart("/api/files/Doc/attachment").file(file))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> handle = objectMapper.readValue(uploadJson, Map.class);

        mockMvc.perform(get("/api/files")
                        .param("storeId", (String) handle.get("storeId"))
                        .param("key", (String) handle.get("key")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Disposition", startsWithInline()))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
    }

    private static org.hamcrest.Matcher<String> startsWithAttachment() {
        return org.hamcrest.Matchers.startsWith("attachment;");
    }

    private static org.hamcrest.Matcher<String> startsWithInline() {
        return org.hamcrest.Matchers.startsWith("inline;");
    }
}
