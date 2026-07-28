package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.service.RuntimeMetadataService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeMetadataServiceTest {

    private final RuntimeMetadataService runtimeMetadataService = new RuntimeMetadataService(new ObjectMapper());

    @Test
    void loadsRuntimeMetadataOverviewFromGeneratedClasspathArtifacts() {
        Map<String, Object> overview = runtimeMetadataService.overview();

        assertEquals("generated-runtime-metadata", overview.get("sourceType"));
        assertEquals("canonical.clinicdemo", overview.get("namespace"));
        assertEquals(11, ((Number) overview.get("catalogCount")).intValue());
        assertTrue(overview.containsKey("compiledCatalogNames"));
    }

    @Test
    void exposesConceptPreviewSupportWithoutRawModelParsing() {
        Map<String, Object> preview = runtimeMetadataService.previewSupport("Appointment");

        @SuppressWarnings("unchecked")
        Map<String, Object> concept = (Map<String, Object>) preview.get("concept");
        @SuppressWarnings("unchecked")
        Map<String, Object> previewSupport = (Map<String, Object>) preview.get("previewSupport");
        @SuppressWarnings("unchecked")
        List<String> tabs = (List<String>) previewSupport.get("tabs");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actionLabels = (List<Map<String, Object>>) previewSupport.get("actionLabels");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> referencePickers = (List<Map<String, Object>>) previewSupport.get("referencePickers");

        assertEquals("Appointment", concept.get("name"));
        assertTrue(tabs.contains("Overview"));
        assertTrue(tabs.contains("Visit lifecycle"));
        assertTrue(actionLabels.stream().anyMatch(item -> "Create appointment".equals(item.get("label"))));
        assertTrue(referencePickers.stream().anyMatch(item -> "patientId".equals(item.get("fieldPath"))));
    }

    /** F2.2: the invocations/transitions catalogs (F2.1, pre-existing respectively) were emitted into
     * {@code compiled-metadata.json} but never split into their own manifest file, so
     * {@code RuntimeMetadataService.catalog(...)} had no way to serve them -- the bundle endpoint's
     * arrays would have 404'd. Proves the alias + split-manifest wiring added in this change. */
    @Test
    void exposesInvocationsAndTransitionsCatalogsFilteredByConcept() {
        Map<String, Object> invocations = runtimeMetadataService.catalog("invocations", "Appointment", null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> invocationItems = (List<Map<String, Object>>) invocations.get("items");
        assertTrue(invocationItems.stream().anyMatch(item -> "createDirect:Appointment".equals(item.get("id"))));
        assertTrue(invocationItems.stream().allMatch(item -> "Appointment".equals(item.get("concept"))),
                "Filtering the invocations catalog by concept must exclude other concepts' entries.");

        Map<String, Object> transitions = runtimeMetadataService.catalog("transitions", "Appointment", null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> transitionItems = (List<Map<String, Object>>) transitions.get("items");
        assertTrue(transitionItems.stream().anyMatch(item -> "Scheduled".equals(item.get("from")) && "CheckedIn".equals(item.get("to"))));
    }

    @Test
    void schemaFingerprintReusesTheSchemaLifecycleExecutorManifestVerbatim() {
        String fingerprint = runtimeMetadataService.schemaFingerprint();
        assertTrue(fingerprint.startsWith("sha256:"), "Expected a sha256: schema fingerprint, got: " + fingerprint);
    }
}
