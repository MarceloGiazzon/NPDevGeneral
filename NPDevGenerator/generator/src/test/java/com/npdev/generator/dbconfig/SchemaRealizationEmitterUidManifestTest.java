package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledConceptAccess;
import com.npdev.dsl.v1.compiled.CompiledEnumOption;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledFieldAccess;
import com.npdev.dsl.v1.compiled.CompiledFieldPicker;
import com.npdev.dsl.v1.compiled.CompiledFileMetadata;
import com.npdev.dsl.v1.compiled.CompiledIndex;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledLifecycle;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledOrigin;
import com.npdev.dsl.v1.compiled.CompiledPresentationMetadata;
import com.npdev.dsl.v1.compiled.CompiledReferenceSemantics;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-209 (B1 lift, package P7): pins the two uid-carrying manifest keys of
 * {@code SchemaRealizationEmitter} -- {@code businessTableColumnUids}
 * ({@code Map<table, Map<column, fieldUid>>}) and {@code businessTableUids}
 * ({@code Map<table, conceptUid>}). A table appears under a key only when it actually declares a
 * non-blank uid; the negative control proves that the positive case cannot pass vacuously.
 */
final class SchemaRealizationEmitterUidManifestTest {

    @TempDir
    Path tempDir;

    @Test
    void conceptAndFieldUidsAreEmittedUnderTheirTableInTheManifest() throws Exception {
        CompiledConcept gadget = concept("Gadget", "concept-uid-gadget", "field-uid-label");

        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(gadget.getName(), gadget));

        Path outRoot = tempDir.resolve("app");
        new SchemaRealizationEmitter().emit(model, outRoot, plan(), tempDir.resolve("model.json"));

        JsonNode manifest = readManifest(outRoot);
        JsonNode columnUids = manifest.path("businessTableColumnUids");
        JsonNode tableUids = manifest.path("businessTableUids");

        assertTrue(columnUids.isObject(), "businessTableColumnUids must be an object: " + columnUids);
        assertEquals("field-uid-label", columnUids.path("gadgets").path("label").asText(null),
                "expected gadgets.label -> field-uid-label in businessTableColumnUids: " + columnUids);
        // The id field carries no uid, so it must not be listed among the uid'd columns.
        assertFalse(columnUids.path("gadgets").has("id"),
                "a field without a uid must not appear in businessTableColumnUids: " + columnUids);

        assertTrue(tableUids.isObject(), "businessTableUids must be an object: " + tableUids);
        assertEquals("concept-uid-gadget", tableUids.path("gadgets").asText(null),
                "expected gadgets -> concept-uid-gadget in businessTableUids: " + tableUids);
    }

    @Test
    void conceptsAndFieldsWithoutAUidOrWithABlankUidAreNotListedInTheManifest() throws Exception {
        CompiledConcept widget = concept("Widget", null, null);
        CompiledConcept gizmo = concept("Gizmo", "   ", "   ");

        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0",
                Map.of(widget.getName(), widget, gizmo.getName(), gizmo));

        Path outRoot = tempDir.resolve("app");
        new SchemaRealizationEmitter().emit(model, outRoot, plan(), tempDir.resolve("model.json"));

        JsonNode manifest = readManifest(outRoot);
        JsonNode columnUids = manifest.path("businessTableColumnUids");
        JsonNode tableUids = manifest.path("businessTableUids");

        // Negative control: the tables really are in the manifest, they just carry no uids.
        assertTrue(containsText(manifest.path("businessTables"), "widgets"),
                "widgets must be a business table: " + manifest.path("businessTables"));
        assertTrue(containsText(manifest.path("businessTables"), "gizmos"),
                "gizmos must be a business table: " + manifest.path("businessTables"));

        assertFalse(columnUids.has("widgets"), "no field uid -> table not listed: " + columnUids);
        assertFalse(columnUids.has("gizmos"), "blank field uid -> table not listed: " + columnUids);
        assertFalse(tableUids.has("widgets"), "no concept uid -> table not listed: " + tableUids);
        assertFalse(tableUids.has("gizmos"), "blank concept uid -> table not listed: " + tableUids);
    }

    private static boolean containsText(JsonNode array, String value) {
        if (array == null || !array.isArray()) {
            return false;
        }
        for (JsonNode item : array) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode readManifest(Path outRoot) throws Exception {
        Path resourcesRoot = outRoot.resolve("src/main/resources");
        return new ObjectMapper().readTree(
                resourcesRoot.resolve("npdev/db/schema-realization-manifest.json").toFile());
    }

    private static CompiledField labelField(String uid) {
        return new CompiledField(
                "label",
                "string",
                "java.lang.String",
                false,
                false,
                false,
                List.<String>of(),
                (String) null,
                (CompiledReferenceSemantics) null,
                (String) null,
                (CompiledSchema) null,
                List.<CompiledEnumOption>of(),
                (CompiledPresentationMetadata) null,
                (String) null,
                (String) null,
                (CompiledFileMetadata) null,
                false,
                (CompiledFieldPicker) null,
                (CompiledFieldAccess) null,
                uid
        );
    }

    private static CompiledConcept concept(String name, String conceptUid, String labelFieldUid) {
        return new CompiledConcept(
                name,
                name,
                "",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        labelField(labelFieldUid)),
                List.<String>of(),
                List.<CompiledInvariant>of(),
                (CompiledLifecycle) null,
                (CompiledPresentationMetadata) null,
                (String) null,
                (String) null,
                List.<CompiledIndex>of(),
                (CompiledConceptAccess) null,
                (String) null,
                (String) null,
                (CompiledOrigin) null,
                false,
                false,
                conceptUid
        );
    }

    private GeneratedDatabasePlan plan() {
        return new GeneratedDatabasePlan(
                "uid-manifest-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                false, // externallyProvisioned (STOR-14) -- NPDev provisioned this test's database
                "uid-manifest-test",
                "uid-manifest-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:uid-manifest-test",
                "org.h2.Driver",
                "sa",
                "",
                "",
                0,
                "",
                "",
                false,
                true,
                new SchemaLifecyclePolicy(
                        SchemaLifecycleStrategy.KEEP_EXISTING_IF_COMPATIBLE,
                        false,
                        "",
                        SchemaLifecyclePolicy.NPDEV_TABLE_SCOPE
                ),
                "test-fingerprint",
                tempDir.resolve("database.json"),
                List.of("test")
        );
    }
}
