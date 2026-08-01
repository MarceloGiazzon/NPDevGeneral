package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.service.RuntimeMetadataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** docs/REMEDIATION_PLAN.md R-D3: compiled-metadata.json sat 3 months stale (April baseline, 11
     * catalogs) after {@code CompiledMetadataCanonicalJson} grew a 12th ("invocations") -- the split
     * manifests ({@code metadata/index.json} + {@code metadata/invocations.manifest.json}) were
     * regenerated at some point but the raw fixture never was, so this service was exercised against
     * a snapshot that predated a catalog it is now expected to serve. This module has no compile-time
     * dependency on the DSL/generator emitter, so the strongest guard available here is cross-checking
     * the fixture against its OWN sibling artifact rather than the emitter's source: both are supposed
     * to be generated from the same compiled model in the same pass, so every catalog the split index
     * knows about must also appear in the raw compiled-metadata.json. {@code domainTypes} is the one
     * deliberate exception -- it is never split into its own manifest/index entry. Fails loudly the
     * next time only one of the two fixtures gets regenerated. */
    @Test
    void compiledMetadataCatalogNamesStayCompleteAgainstTheSplitManifestIndex() {
        Map<String, Object> overview = runtimeMetadataService.overview();
        @SuppressWarnings("unchecked")
        Set<String> compiledCatalogNames = (Set<String>) overview.get("compiledCatalogNames");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> indexCatalogs = (List<Map<String, Object>>) overview.get("catalogs");

        Set<String> indexSourceCatalogNames = indexCatalogs.stream()
                .map(entry -> String.valueOf(entry.get("sourceCatalog")))
                .collect(Collectors.toSet());

        Set<String> expected = new HashSet<>(indexSourceCatalogNames);
        expected.add("domainTypes"); // deliberately never split into its own manifest/index entry

        assertEquals(expected, compiledCatalogNames,
                "compiled-metadata.json's catalog set has drifted from metadata/index.json's -- one of "
                        + "the two fixtures was regenerated and the other was not. Regenerate both from "
                        + "the same compiled model (see docs/REMEDIATION_PLAN.md R-D3).");
    }

    /**
     * REG-103 (Move 12 P2.1): LC-C2's premise is writing metadata into a RUNNING app, but this
     * service loaded both catalogs from the classpath ONLY -- baked into the jar, so a label/panel
     * edit could never become visible without a rebuild. Proves the external-path override (mirroring
     * {@code NPDevModelProvider}'s own convention) actually takes precedence over the classpath copy,
     * for BOTH files -- not just one accidentally matching by coincidence.
     */
    @Test
    void externalCompiledMetadataAndIndexFilesOverrideTheClasspathCopy(@TempDir Path tempDir) throws Exception {
        Path compiledMetadataFile = tempDir.resolve("compiled-metadata.json");
        Path metadataIndexFile = tempDir.resolve("index.json");
        Files.writeString(compiledMetadataFile,
                "{\"namespace\":\"external-override-ns\",\"dslVersion\":\"9.9.9\",\"version\":\"1.2.3\"}");
        Files.writeString(metadataIndexFile,
                "{\"metadataManifestVersion\":\"ext-v1\",\"metadataVersion\":\"ext-v2\"}");

        RuntimeMetadataService externallyConfigured = new RuntimeMetadataService(
                new ObjectMapper(), compiledMetadataFile.toString(), metadataIndexFile.toString());

        Map<String, Object> overview = externallyConfigured.overview();

        assertEquals("external-override-ns", overview.get("namespace"),
                "an external compiled-metadata.json at the configured path must win over the classpath copy");
        assertEquals("9.9.9", overview.get("dslVersion"));
        assertEquals("1.2.3", overview.get("modelVersion"));
        assertEquals("ext-v1", overview.get("metadataManifestVersion"),
                "an external metadata/index.json at the configured path must win over the classpath copy");
        assertEquals("ext-v2", overview.get("metadataVersion"));

        // The control: this service's own sibling test (loadsRuntimeMetadataOverviewFromGeneratedClasspathArtifacts,
        // using the 1-arg constructor -- exactly what all other call sites in this suite already use)
        // asserts namespace "canonical.clinicdemo" from the classpath fixture, still passing unchanged
        // -- proving "no property set" behaviour is byte-identical to before this fix.
    }

    /**
     * Move 13 P5.1 (REG-103's own named residual): the two-catalog override above left
     * {@code loadManifest}'s per-catalog manifest files (e.g. {@code panels.manifest.json}) and
     * {@code schema-realization-manifest.json} classpath-only -- there is no fixed set of per-catalog
     * manifest files to name individually (the catalog list comes from the index at runtime), so the
     * fix is a generic external-root derivation ({@code externalPathFor}) rather than another named
     * {@code @Value}. Proves an external {@code panels.manifest.json}, placed at the SAME relative
     * path the classpath copy uses, wins -- with compiledMetadataPath/metadataIndexPath left at their
     * defaults (so metadata/index.json itself still comes from the classpath, exactly as a real app
     * would have it before this specific file is ever touched).
     */
    @Test
    void externalPerCatalogManifestAndSchemaRealizationManifestOverrideTheClasspathCopy(@TempDir Path tempDir) throws Exception {
        Path panelsManifest = tempDir.resolve("npdev/metadata/panels.manifest.json");
        Files.createDirectories(panelsManifest.getParent());
        Files.writeString(panelsManifest, "{\"items\":[],\"marker\":\"external-panels-manifest\"}");

        Path schemaRealizationManifest = tempDir.resolve("npdev/db/schema-realization-manifest.json");
        Files.createDirectories(schemaRealizationManifest.getParent());
        Files.writeString(schemaRealizationManifest, "{\"schemaFingerprint\":\"external-fingerprint-123\"}");

        RuntimeMetadataService externallyConfigured = new RuntimeMetadataService(
                new ObjectMapper(),
                "compiled-metadata-path-not-present-so-classpath-wins.json",
                "metadata-index-path-not-present-so-classpath-wins.json",
                tempDir.toString());

        Map<String, Object> panelsCatalog = externallyConfigured.catalog("panels", null, null, null);
        assertEquals("external-panels-manifest", panelsCatalog.get("marker"),
                "an external panels.manifest.json at the derived path (generatedResourcesRoot + the "
                        + "classpath-relative path the index declares) must win over the classpath copy");

        assertEquals("external-fingerprint-123", externallyConfigured.schemaFingerprint(),
                "an external schema-realization-manifest.json at the derived path must win over the classpath copy");
    }
}
