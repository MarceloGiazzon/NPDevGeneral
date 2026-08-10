package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SER-G8: the schema-realization manifest carries the model's declared INDEXES (and, for a bonded model,
 * foreign keys) so the runtime can VERIFY that dimension instead of being blind to it — the last gap in
 * the {@code ExternallyManaged} full-shape check (P5.2) and the FK/index deferral the schema-engine
 * rebuild tracked as P0.2.
 *
 * <p>Both are emitted <b>name-lessly</b> (columns + referenced table + uniqueness only): constraint and
 * index names are engine-generated and differ between H2 and Postgres, so the runtime matches by COLUMN
 * SET. Asserting the absence of a name here is part of the contract, not an omission.
 */
final class SchemaRealizationEmitterForeignKeyIndexManifestTest {

    @TempDir
    Path tempDir;

    @Test
    void aDeclaredUniqueInvariantBecomesAUniqueIndexEntryInTheManifest() throws Exception {
        CompiledConcept membership = new CompiledConcept(
                "Membership", "Membership", "memberships",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("orgId", "string", "String", false, true, false),
                        new CompiledField("email", "string", "String", false, true, false)
                ),
                List.of(),
                List.of(new CompiledInvariant(
                        "unique(orgId,email)", "unique", "orgId", null, List.of("orgId", "email")
                ))
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(membership.getName(), membership));

        Path outRoot = tempDir.resolve("app");
        new SchemaRealizationEmitter().emit(model, outRoot, plan(), tempDir.resolve("model.json"));

        JsonNode manifest = readManifest(outRoot);
        JsonNode indexes = manifest.path("businessTableIndexes").path("memberships");
        assertTrue(indexes.isArray() && indexes.size() >= 1,
                "the model's unique invariant must appear in businessTableIndexes: " + manifest.path("businessTableIndexes"));

        JsonNode unique = indexes.get(0);
        assertEquals(true, unique.path("unique").asBoolean(), "a unique invariant emits a UNIQUE index: " + unique);
        assertTrue(unique.path("columns").isArray() && unique.path("columns").size() >= 2,
                "the compound unique must carry both columns: " + unique);
        assertTrue(unique.path("name").isMissingNode(),
                "index entries are name-less by contract (matched by column set): " + unique);
    }

    @Test
    void aModelWithNoBondsAndNoUniquesEmitsTheKeysButNoEntriesForThatTable() throws Exception {
        CompiledConcept widget = new CompiledConcept(
                "Widget", "Widget", "widgets",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("name", "string", "String", false, true, false)
                ),
                List.of(),
                List.of()
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(widget.getName(), widget));

        Path outRoot = tempDir.resolve("app");
        new SchemaRealizationEmitter().emit(model, outRoot, plan(), tempDir.resolve("model.json"));

        JsonNode manifest = readManifest(outRoot);
        // The KEYS must always be present (a manifest consumer can rely on them existing); a table with
        // nothing to declare simply contributes no entry -- NOT an entry with an empty array.
        assertTrue(manifest.has("businessTableForeignKeys"), "manifest must always carry businessTableForeignKeys");
        assertTrue(manifest.has("businessTableIndexes"), "manifest must always carry businessTableIndexes");
        assertTrue(manifest.path("businessTableForeignKeys").path("widgets").isMissingNode(),
                "a bond-free table contributes no FK entry: " + manifest.path("businessTableForeignKeys"));
    }

    private static JsonNode readManifest(Path outRoot) throws Exception {
        Path manifestPath = outRoot.resolve("src/main/resources/npdev/db/schema-realization-manifest.json");
        return new ObjectMapper().readTree(Files.readString(manifestPath));
    }

    private GeneratedDatabasePlan plan() {
        return new GeneratedDatabasePlan(
                "fk-index-manifest-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                false, // externallyProvisioned (STOR-14) -- NPDev provisioned this test's database
                "fk-index-manifest-test",
                "fk-index-manifest-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:fk-index-manifest-test",
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
