package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledIndex;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 Phase 5 (5.1/5.2): pins the new manifest keys {@code SchemaRealizationEmitter} emits for
 * the runtime executor's data pre-checks and literal backfills --
 * {@code businessTableRequiredColumns}, {@code businessTableColumnDefaultLiterals},
 * {@code businessTableExpressionDefaultColumns}, {@code businessTableUniqueConstraints} -- and
 * cross-checks the unique-constraint manifest data against the actual generated V1 SQL (parity
 * protection for the intentionally-parallel {@code collectUniqueConstraints} implementation, see
 * its javadoc).
 */
final class SchemaRealizationEmitterRequiredDefaultUniqueManifestTest {

    @TempDir
    Path tempDir;

    @Test
    void requiredColumnsAndLiteralAndExpressionDefaultsAreThreadedIntoTheManifest() throws Exception {
        CompiledSchema literalDefaultSchema = new CompiledSchema(
                null, Map.of(), null, List.of(), List.of(), "PENDING", "description",
                null, null, null, null, null);
        CompiledSchema expressionDefaultSchema = new CompiledSchema(
                null, Map.of(), null, List.of(), List.of(), null, "now()", null, "description",
                null, null, null, null, null);

        CompiledField id = new CompiledField("id", "uuid", "java.util.UUID", true, true, false);
        CompiledField status = new CompiledField(
                "status", "string", "String", false, true, false, List.of(), null, literalDefaultSchema);
        CompiledField approvedAt = new CompiledField(
                "approvedAt", "datetime", "java.time.Instant", false, true, false, List.of(), null, expressionDefaultSchema);
        CompiledField nickname = new CompiledField("nickname", "string", "String", false, false, false);

        CompiledConcept widget = new CompiledConcept(
                "Widget", "Widget", "widgets", List.of(id, status, approvedAt, nickname));
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(widget.getName(), widget));

        Path outRoot = tempDir.resolve("app");
        new SchemaRealizationEmitter().emit(model, outRoot, plan(), tempDir.resolve("model.json"));

        JsonNode manifest = readManifest(outRoot);
        JsonNode required = manifest.path("businessTableRequiredColumns").path("widgets");
        assertTrue(containsText(required, "status"));
        assertTrue(containsText(required, "approved_at"));
        assertFalse(containsText(required, "nickname"), "an optional field must not appear in the required-columns list");

        JsonNode literalDefaults = manifest.path("businessTableColumnDefaultLiterals").path("widgets");
        assertEquals("\"PENDING\"", literalDefaults.path("status").asText(), literalDefaults.toString());
        assertTrue(literalDefaults.path("approved_at").isMissingNode(),
                "an expression-only default must not be threaded as a literal: " + literalDefaults);

        JsonNode expressionDefaults = manifest.path("businessTableExpressionDefaultColumns").path("widgets");
        assertTrue(containsText(expressionDefaults, "approved_at"));
        assertFalse(containsText(expressionDefaults, "status"), "a field with a literal default must not also appear as expression-default-only");
    }

    @Test
    void uniqueConstraintManifestDataMatchesTheActualGeneratedDdl() throws Exception {
        CompiledField id = new CompiledField("id", "uuid", "java.util.UUID", true, true, false);
        CompiledField email = new CompiledField("email", "string", "String", false, true, true);
        CompiledField orgId = new CompiledField("orgId", "string", "String", false, true, false);
        CompiledField sku = new CompiledField("sku", "string", "String", false, true, true, List.of(), null,
                null, null, null, List.of(), null, "anchor");

        CompiledConcept membership = new CompiledConcept(
                "Membership", "Membership", "memberships",
                List.of(id, email, orgId, sku),
                List.of(),
                List.of(new CompiledInvariant("unique(orgId,email)", "unique", "orgId", null, List.of("orgId", "email"))),
                null, null, null, null,
                List.of(new CompiledIndex("idxx_note", List.of("email"), false))
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(membership.getName(), membership));

        Path outRoot = tempDir.resolve("app");
        new SchemaRealizationEmitter().emit(model, outRoot, plan(), tempDir.resolve("model.json"));

        Path schemaDir = outRoot.resolve("src/main/resources/db/schema-realization");
        String v1Sql = Files.readString(schemaDir.resolve("V1__npdev_schema_realization.sql"));

        JsonNode manifest = readManifest(outRoot);
        JsonNode declarations = manifest.path("businessTableUniqueConstraints").path("memberships");
        assertTrue(declarations.isArray() && declarations.size() >= 3, declarations.toString());

        for (JsonNode decl : declarations) {
            String name = decl.path("name").asText();
            boolean tenantScoped = decl.path("tenantScoped").asBoolean();
            List<String> columns = toList(decl.path("columns"));
            String expectedColumnList = (tenantScoped ? "tenant_id, " : "") + String.join(", ", columns);
            // Every manifest-declared constraint must correspond to a real uniqueness-enforcing
            // statement in the generated V1 SQL, with the exact same name and column list -- proving
            // collectUniqueConstraints (manifest-only) stays in sync with appendBusinessTable/
            // appendExplicitIndexes (DDL-emission) despite being a deliberately parallel
            // implementation. An ordinary (non-anchor) single-field unique emits a
            // CREATE UNIQUE INDEX; every other kind (anchor, compound, explicit-index) emits an
            // ADD CONSTRAINT ... UNIQUE.
            String asConstraint = "ADD CONSTRAINT " + name + " UNIQUE (" + expectedColumnList + ")";
            String asUniqueIndex = "CREATE UNIQUE INDEX IF NOT EXISTS " + name + " ON memberships (" + expectedColumnList + ")";
            assertTrue(v1Sql.contains(asConstraint) || v1Sql.contains(asUniqueIndex),
                    "manifest-declared constraint '" + name + "' on (" + expectedColumnList
                            + ") not found verbatim (as either an ADD CONSTRAINT or a CREATE UNIQUE INDEX) in "
                            + "generated SQL:\n" + v1Sql);
        }

        // email: single-field unique, tenant-scoped.
        assertTrue(declarations.toString().contains("\"columns\":[\"email\"]") || anyMatches(declarations, "email", true),
                declarations.toString());
        // sku: connectable anchor, globally unique (not tenant-scoped).
        assertTrue(anyMatches(declarations, "sku", false), declarations.toString());
        // (orgId, email): compound unique invariant, tenant-scoped.
        assertTrue(anyMatchesColumns(declarations, List.of("org_id", "email"), true), declarations.toString());
    }

    private static boolean anyMatches(JsonNode declarations, String singleColumn, boolean tenantScoped) {
        for (JsonNode decl : declarations) {
            List<String> columns = toList(decl.path("columns"));
            if (columns.equals(List.of(singleColumn)) && decl.path("tenantScoped").asBoolean() == tenantScoped) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyMatchesColumns(JsonNode declarations, List<String> expectedColumns, boolean tenantScoped) {
        for (JsonNode decl : declarations) {
            if (toList(decl.path("columns")).equals(expectedColumns) && decl.path("tenantScoped").asBoolean() == tenantScoped) {
                return true;
            }
        }
        return false;
    }

    private static List<String> toList(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode item : array) {
                out.add(item.asText());
            }
        }
        return out;
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

    private GeneratedDatabasePlan plan() {
        return new GeneratedDatabasePlan(
                "required-default-unique-manifest-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                false, // externallyProvisioned (STOR-14) -- NPDev provisioned this test's database
                "required-default-unique-manifest-test",
                "required-default-unique-manifest-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:required-default-unique-manifest-test",
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
