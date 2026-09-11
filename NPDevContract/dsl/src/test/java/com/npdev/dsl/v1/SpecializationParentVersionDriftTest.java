package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.pack.PackLockFile;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3.4 (NPDEV_PATH_A_REALIGNMENT_PLAN.md Decision D3): "pinned or floating parent when a pack's
 * base concept version moves under a live specialization" -- real end-to-end proof through {@link
 * ModelSourceResolver#resolve}, mirroring {@code PackMigrationChainResolutionTest}'s own template
 * (real {@code npdev.lock} fixtures, no mocking of the merge pipeline). Proves both of D3's
 * accepted behaviours: pinned-by-default refuses on drift, {@code specializesFloat: true} opts out.
 */
class SpecializationParentVersionDriftTest {

    @TempDir
    Path temp;

    private static final String INVOICE_PACK_V2 = """
            {
              "dslVersion": "1.0.0",
              "pack": "identity",
              "version": "2.0.0",
              "concepts": [
                { "name": "Invoice", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "code", "type": "string" },
                  { "name": "taxRate", "type": "long" }
                ] }
              ]
            }
            """;

    private static final String INVOICE_PACK_V2_SAME_SHAPE_NO_NEW_FIELD = """
            {
              "dslVersion": "1.0.0",
              "pack": "identity",
              "version": "2.0.0",
              "concepts": [
                { "name": "Invoice", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "code", "type": "string" }
                ] }
              ]
            }
            """;

    private static final String MODEL_PINNED = """
            {
              "namespace": "drift.test",
              "dslVersion": "1.0.0",
              "version": "1.0",
              "packs": [ { "$ref": "packs/identity/pack.json" } ],
              "concepts": [
                { "name": "MedicalInvoice", "specializes": "identity::Invoice", "fields": [
                  { "name": "doctorId", "type": "uuid" }
                ] }
              ]
            }
            """;

    private static final String MODEL_FLOATING = """
            {
              "namespace": "drift.test",
              "dslVersion": "1.0.0",
              "version": "1.0",
              "packs": [ { "$ref": "packs/identity/pack.json" } ],
              "concepts": [
                { "name": "MedicalInvoice", "specializes": "identity::Invoice", "specializesFloat": true, "fields": [
                  { "name": "doctorId", "type": "uuid" }
                ] }
              ]
            }
            """;

    @Test
    void pinnedByDefaultRefusesOnceThePacksBaseConceptHasMovedSinceLastGenerate() throws Exception {
        write("packs/identity/pack.json", INVOICE_PACK_V2_SAME_SHAPE_NO_NEW_FIELD);
        Path model = write("model.json", MODEL_PINNED);
        writeLockWithMigratedVersion("identity", "packs/identity/pack.json", "1.0.0");

        IOException e = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(e.getMessage().contains("PARENT_VERSION_DRIFT"), e.getMessage());
        assertTrue(e.getMessage().contains("MedicalInvoice"), e.getMessage());
        assertTrue(e.getMessage().contains("specializesFloat"), e.getMessage());
    }

    @Test
    void explicitFloatOptInAdoptsTheNewParentShapeInsteadOfRefusing() throws Exception {
        write("packs/identity/pack.json", INVOICE_PACK_V2);
        Path model = write("model.json", MODEL_FLOATING);
        writeLockWithMigratedVersion("identity", "packs/identity/pack.json", "1.0.0");

        ResolvedModelSource source = new ModelSourceResolver().resolve(model);

        JsonNode medicalInvoice = conceptNamed(source, "MedicalInvoice");
        assertEquals("identity::Invoice", medicalInvoice.get("specializes").asText());
    }

    @Test
    void firstEverGenerateWithNoLockNeverRefuses() throws Exception {
        write("packs/identity/pack.json", INVOICE_PACK_V2_SAME_SHAPE_NO_NEW_FIELD);
        Path model = write("model.json", MODEL_PINNED);
        // No npdev.lock at all -- nothing has been generated yet, so there is no prior version to
        // have drifted from.

        ResolvedModelSource source = new ModelSourceResolver().resolve(model);

        assertEquals("identity::Invoice", conceptNamed(source, "MedicalInvoice").get("specializes").asText());
    }

    @Test
    void sameVersionRegenerateIsNotDriftEvenWhenPinned() throws Exception {
        write("packs/identity/pack.json", INVOICE_PACK_V2_SAME_SHAPE_NO_NEW_FIELD);
        Path model = write("model.json", MODEL_PINNED);
        // Already generated against 2.0.0 -- regenerating at the same version must not refuse.
        writeLockWithMigratedVersion("identity", "packs/identity/pack.json", "2.0.0");

        ResolvedModelSource source = new ModelSourceResolver().resolve(model);

        assertEquals("identity::Invoice", conceptNamed(source, "MedicalInvoice").get("specializes").asText());
    }

    @Test
    void redControl_specializingAnAppsOwnConceptNeverRefusesRegardlessOfPackDrift() throws Exception {
        // A concept specializing another concept that is NOT pack-contributed (an app's own root
        // concept) has nothing to pin against -- must never be refused, even with an unrelated pack
        // present that has genuinely drifted.
        write("packs/identity/pack.json", INVOICE_PACK_V2_SAME_SHAPE_NO_NEW_FIELD);
        Path model = write("model.json", """
                {
                  "namespace": "drift.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "$ref": "packs/identity/pack.json" } ],
                  "concepts": [
                    { "name": "Base", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                    { "name": "Derived", "specializes": "Base", "fields": [ { "name": "extra", "type": "string" } ] }
                  ]
                }
                """);
        writeLockWithMigratedVersion("identity", "packs/identity/pack.json", "1.0.0");

        ResolvedModelSource source = new ModelSourceResolver().resolve(model);

        assertEquals("Base", conceptNamed(source, "Derived").get("specializes").asText());
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private JsonNode conceptNamed(ResolvedModelSource source, String name) {
        for (JsonNode concept : source.resolvedRoot().get("concepts")) {
            if (name.equals(concept.get("name").asText())) {
                return concept;
            }
        }
        throw new AssertionError("concept '" + name + "' not found in resolved model: " + source.resolvedRoot());
    }

    private void writeLockWithMigratedVersion(String packId, String relativeSourcePath, String migratedVersion) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path file = temp.resolve(relativeSourcePath);
        String resolvedVersion = mapper.readTree(file.toFile()).get("version").asText();
        Map<String, PackLockFile.LockedPack> packs = new LinkedHashMap<>();
        packs.put(packId, new PackLockFile.LockedPack(
                resolvedVersion, PackLockFile.sha256(file), relativeSourcePath, migratedVersion));
        PackLockFile.of(packs).write(temp);
    }

    private Path write(String relative, String content) throws Exception {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
