package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledConceptAccess;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledIndex;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledLifecycle;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPresentationMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 P2 (2.4): pins {@code SchemaRealizationEmitter}'s new {@code businessTableRenames}
 * manifest key -- a flat {@code Map<newTableName, oldTableName>} threaded from a concept's
 * declared {@code renamedFrom}, mirroring the existing field-level
 * {@code businessTableRenamedColumns} key. Covers both branches of the design: the ordinary
 * no-override rename (old != new, entry present) and the explicit-tableName-override rename
 * (old == new, no entry -- a physical no-op per SqlIdentifierSupport's "override persists across
 * rename" convention, see SqlIdentifierSupportTest).
 */
final class SchemaRealizationEmitterConceptRenameTest {

    @TempDir
    Path tempDir;

    @Test
    void conceptRenameWithoutTableNameOverrideProducesABusinessTableRenamesEntry() throws Exception {
        CompiledConcept gadget = renamedConcept("Gadget", "Widget", "");

        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(gadget.getName(), gadget));

        Path outRoot = tempDir.resolve("app");
        new SchemaRealizationEmitter().emit(model, outRoot, plan(), tempDir.resolve("model.json"));

        JsonNode manifest = readManifest(outRoot);
        JsonNode renames = manifest.path("businessTableRenames");

        assertTrue(renames.isObject(), "businessTableRenames must be an object: " + renames);
        assertEquals("widgets", renames.path("gadgets").asText(null),
                "expected gadgets -> widgets in businessTableRenames: " + renames);
    }

    @Test
    void conceptRenameWithExplicitTableNameOverridePreservesTheSamePhysicalTableAndEmitsNoRename() throws Exception {
        CompiledConcept gadget = renamedConcept("Gadget", "Widget", "legacy_products");

        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(gadget.getName(), gadget));

        Path outRoot = tempDir.resolve("app");
        new SchemaRealizationEmitter().emit(model, outRoot, plan(), tempDir.resolve("model.json"));

        JsonNode manifest = readManifest(outRoot);
        JsonNode renames = manifest.path("businessTableRenames");
        JsonNode businessTables = manifest.path("businessTables");

        assertTrue(renames.isMissingNode() || renames.isObject() && !renames.fieldNames().hasNext(),
                "an explicit tableName override must NOT produce a rename entry (no-op): " + renames);
        assertTrue(containsText(businessTables, "legacy_products"),
                "the concept's table must still be the overridden name: " + businessTables);
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

    private static CompiledConcept renamedConcept(String name, String renamedFrom, String tableNameOverride) {
        return new CompiledConcept(
                name,
                name,
                tableNameOverride,
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false)),
                List.<String>of(),
                List.<CompiledInvariant>of(),
                (CompiledLifecycle) null,
                (CompiledPresentationMetadata) null,
                (String) null,
                (String) null,
                List.<CompiledIndex>of(),
                (CompiledConceptAccess) null,
                renamedFrom
        );
    }

    private GeneratedDatabasePlan plan() {
        return new GeneratedDatabasePlan(
                "concept-rename-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                "concept-rename-test",
                "concept-rename-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:concept-rename-test",
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
