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
        assertEquals(9, ((Number) overview.get("catalogCount")).intValue());
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
}
