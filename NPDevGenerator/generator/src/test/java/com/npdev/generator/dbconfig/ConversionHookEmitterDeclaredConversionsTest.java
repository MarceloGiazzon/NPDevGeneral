package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S7 Phase B (B13 declarative conversion vocabulary) + S8 W1.2 (roadmap deferred item #4) + Wave 4
 * (BOUNDARY_LIFT_PLAN_2026-09-02.md package 4.2, vocabulary expansion): proves the compiler resolves
 * and the generator compiles each of the seven ops (copy/split/lookup/merge/convert/coalesce/case)
 * to the exact {@code db/conversion-hooks/<id>/{hook.json,convert.sql}} shape {@link
 * ConversionHookRunner} (NPDevRuntimeHost) executes at boot -- and that an unresolvable field/op is
 * a named compile error, never a silently-dropped conversion (the vocabulary's own X0 rule). The
 * generated SQL's actual DATA-LAYER correctness (real conversion, idempotence) is proven
 * separately, against a real H2 database, by NPDevRuntimeHost's {@code
 * ConversionHookRunnerDeclaredConversionsTest} -- this test proves the generator produces that
 * exact SQL in the first place.
 */
class ConversionHookEmitterDeclaredConversionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CompiledModel compile(String json) throws IOException {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new ModelCompiler().compile(ast);
    }

    private static final String MODEL_JSON = """
            {
              "namespace": "s7.conversions.test",
              "dslVersion": "1.0.0",
              "version": "1.0",
              "concepts": [
                { "name": "Customer", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "fullName", "type": "string" },
                    { "name": "firstName", "type": "string", "required": true },
                    { "name": "lastName", "type": "string", "required": true },
                    { "name": "displayName", "type": "string" },
                    { "name": "primaryEmail", "type": "string" },
                    { "name": "secondaryEmail", "type": "string" },
                    { "name": "contactEmail", "type": "string" }
                ] },
                { "name": "Order", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "legacyCode", "type": "string" },
                    { "name": "externalRef", "type": "string", "required": true },
                    { "name": "priorityText", "type": "string" },
                    { "name": "priorityNumber", "type": "integer" },
                    { "name": "regionCode", "type": "string" },
                    { "name": "regionLabel", "type": "string" }
                ] },
                { "name": "Product", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "sku", "type": "string" }
                ] },
                { "name": "OrderLine", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "productSku", "type": "string" },
                    { "name": "productId", "type": "uuid", "required": true }
                ] }
              ],
              "conversions": [
                { "id": "0001-split-name", "concept": "Customer", "op": "split", "from": "fullName",
                  "into": [ { "field": "firstName", "take": "before-first-space" },
                            { "field": "lastName", "take": "after-first-space" } ] },
                { "id": "0002-copy-code", "concept": "Order", "op": "copy",
                  "from": "legacyCode", "to": "externalRef" },
                { "id": "0003-lookup-fk", "concept": "OrderLine", "op": "lookup",
                  "match": { "concept": "Product", "on": "sku", "equals": "productSku" },
                  "set": "productId" },
                { "id": "0004-merge-name", "concept": "Customer", "op": "merge",
                  "from": [ "firstName", "lastName" ], "to": "displayName", "with": " " },
                { "id": "0005-convert-priority", "concept": "Order", "op": "convert",
                  "from": "priorityText", "to": "priorityNumber" },
                { "id": "0006-coalesce-contact-email", "concept": "Customer", "op": "coalesce",
                  "from": [ "primaryEmail", "secondaryEmail" ], "to": "contactEmail" },
                { "id": "0007-case-region-label", "concept": "Order", "op": "case",
                  "from": "regionCode", "to": "regionLabel",
                  "when": [ { "equals": "N", "then": "North" }, { "equals": "S", "then": "South" } ],
                  "else": "Unknown" }
              ]
            }
            """;

    @Test
    void compilesAllSevenOpsToHookJsonAndConvertSqlOnDisk(@TempDir Path tempDir) throws IOException {
        CompiledModel model = compile(MODEL_JSON);
        assertEquals(7, model.getConversions().size());

        Path outRoot = tempDir.resolve("App");
        new ConversionHookEmitter().emit(model, null, outRoot);
        Path hooksOut = outRoot.resolve("src/main/resources/db/conversion-hooks");

        assertHookGenerated(hooksOut, "0001-split-name",
                new String[] {
                        "ADD_REQUIRED_COLUMN:customers:first_name",
                        "ADD_REQUIRED_COLUMN:customers:last_name"
                },
                new String[] {
                        "ALTER TABLE customers ADD COLUMN IF NOT EXISTS first_name VARCHAR(255)",
                        "ALTER TABLE customers ADD COLUMN IF NOT EXISTS last_name VARCHAR(255)",
                        "SUBSTRING(full_name FROM 1 FOR POSITION(' ' IN full_name) - 1)",
                        "SUBSTRING(full_name FROM POSITION(' ' IN full_name) + 1)",
                        "ALTER TABLE customers ALTER COLUMN first_name SET NOT NULL",
                        "ALTER TABLE customers ALTER COLUMN last_name SET NOT NULL"
                });

        assertHookGenerated(hooksOut, "0002-copy-code",
                new String[] { "ADD_REQUIRED_COLUMN:orders:external_ref" },
                new String[] {
                        "ALTER TABLE orders ADD COLUMN IF NOT EXISTS external_ref VARCHAR(255)",
                        "UPDATE orders SET external_ref = legacy_code WHERE external_ref IS NULL",
                        "ALTER TABLE orders ALTER COLUMN external_ref SET NOT NULL"
                });

        assertHookGenerated(hooksOut, "0003-lookup-fk",
                new String[] { "ADD_REQUIRED_COLUMN:order_lines:product_id" },
                new String[] {
                        "ALTER TABLE order_lines ADD COLUMN IF NOT EXISTS product_id UUID",
                        "SELECT m.id FROM products m WHERE m.sku = order_lines.product_sku",
                        "ALTER TABLE order_lines ALTER COLUMN product_id SET NOT NULL"
                });

        assertHookGenerated(hooksOut, "0004-merge-name",
                new String[] { "ADD_REQUIRED_COLUMN:customers:display_name" },
                new String[] {
                        "ALTER TABLE customers ADD COLUMN IF NOT EXISTS display_name VARCHAR(255)",
                        "UPDATE customers SET display_name = CONCAT(first_name, ' ', last_name) "
                                + "WHERE display_name IS NULL AND first_name IS NOT NULL AND last_name IS NOT NULL",
                        "ALTER TABLE customers ALTER COLUMN display_name SET NOT NULL"
                });

        assertHookGenerated(hooksOut, "0005-convert-priority",
                new String[] { "ADD_REQUIRED_COLUMN:orders:priority_number" },
                new String[] {
                        "ALTER TABLE orders ADD COLUMN IF NOT EXISTS priority_number INTEGER",
                        "UPDATE orders SET priority_number = CAST(priority_text AS INTEGER) WHERE priority_number IS NULL",
                        "ALTER TABLE orders ALTER COLUMN priority_number SET NOT NULL"
                });

        assertHookGenerated(hooksOut, "0006-coalesce-contact-email",
                new String[] { "ADD_REQUIRED_COLUMN:customers:contact_email" },
                new String[] {
                        "ALTER TABLE customers ADD COLUMN IF NOT EXISTS contact_email VARCHAR(255)",
                        "UPDATE customers SET contact_email = COALESCE(primary_email, secondary_email) "
                                + "WHERE contact_email IS NULL",
                        "ALTER TABLE customers ALTER COLUMN contact_email SET NOT NULL"
                });

        assertHookGenerated(hooksOut, "0007-case-region-label",
                new String[] { "ADD_REQUIRED_COLUMN:orders:region_label" },
                new String[] {
                        "ALTER TABLE orders ADD COLUMN IF NOT EXISTS region_label VARCHAR(255)",
                        "UPDATE orders SET region_label = CASE WHEN region_code = 'N' THEN 'North' "
                                + "WHEN region_code = 'S' THEN 'South' ELSE 'Unknown' END WHERE region_label IS NULL",
                        "ALTER TABLE orders ALTER COLUMN region_label SET NOT NULL"
                });
    }

    private void assertHookGenerated(Path hooksOut, String id, String[] expectedClaims,
            String[] expectedSqlFragments) throws IOException {
        Path dir = hooksOut.resolve(id);
        String hookJson = Files.readString(dir.resolve("hook.json"), StandardCharsets.UTF_8);
        for (String claim : expectedClaims) {
            assertTrue(hookJson.contains(claim), id + " hook.json missing claim " + claim + ":\n" + hookJson);
        }
        String convertSql = Files.readString(dir.resolve("convert.sql"), StandardCharsets.UTF_8);
        for (String fragment : expectedSqlFragments) {
            assertTrue(convertSql.contains(fragment),
                    id + " convert.sql missing fragment [" + fragment + "]:\n" + convertSql);
        }
    }

    @Test
    void unresolvableConceptIsANamedCompileErrorNotASilentSkip() {
        String json = MODEL_JSON.replace("\"concept\": \"Customer\", \"op\": \"split\"",
                "\"concept\": \"NoSuchConcept\", \"op\": \"split\"");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> compile(json));
        assertTrue(exception.getMessage().contains("NoSuchConcept"), exception.getMessage());
        assertTrue(exception.getMessage().contains("0001-split-name"), exception.getMessage());
    }

    @Test
    void unresolvableFieldIsANamedCompileErrorNotASilentSkip() {
        String json = MODEL_JSON.replace("\"from\": \"legacyCode\", \"to\": \"externalRef\"",
                "\"from\": \"noSuchField\", \"to\": \"externalRef\"");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> compile(json));
        assertTrue(exception.getMessage().contains("noSuchField"), exception.getMessage());
        assertTrue(exception.getMessage().contains("0002-copy-code"), exception.getMessage());
    }

    @Test
    void unresolvableLookupMatchFieldIsANamedCompileError() {
        String json = MODEL_JSON.replace("\"on\": \"sku\"", "\"on\": \"noSuchField\"");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> compile(json));
        assertTrue(exception.getMessage().contains("noSuchField"), exception.getMessage());
        assertTrue(exception.getMessage().contains("0003-lookup-fk"), exception.getMessage());
    }

    /** S8 W1.2: a merge with fewer than 2 source fields is refused. The JSON schema's own
     *  {@code minItems: 2} on the array-shaped "from" catches this FIRST (before the compiler's own
     *  {@code compileConversions} redundant check, kept as defense-in-depth for a compiled model
     *  built without going through schema validation at all) -- "that's just copy" either way, never
     *  silently accepted as a 1-field merge. */
    @Test
    void mergeWithFewerThanTwoSourceFieldsIsANamedCompileError() {
        String json = MODEL_JSON.replace(
                "\"from\": [ \"firstName\", \"lastName\" ], \"to\": \"displayName\", \"with\": \" \"",
                "\"from\": [ \"firstName\" ], \"to\": \"displayName\", \"with\": \" \"");
        IOException exception = assertThrows(IOException.class, () -> compile(json));
        // The json-schema-validator library's own violation text is locale-dependent (observed in
        // Portuguese in this environment) -- assert on the two locale-INDEPENDENT parts instead: the
        // hardcoded English wrapper text and the JSON path (conversions[3], the 4th declared entry).
        assertTrue(exception.getMessage().contains("schema validation failed"), exception.getMessage());
        assertTrue(exception.getMessage().contains("conversions[3].from"), exception.getMessage());
    }

    @Test
    void mergeWithAnUnresolvableSourceFieldIsANamedCompileErrorNotASilentSkip() {
        String json = MODEL_JSON.replace("\"firstName\", \"lastName\" ], \"to\": \"displayName\"",
                "\"firstName\", \"noSuchField\" ], \"to\": \"displayName\"");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> compile(json));
        assertTrue(exception.getMessage().contains("noSuchField"), exception.getMessage());
        assertTrue(exception.getMessage().contains("0004-merge-name"), exception.getMessage());
    }

    @Test
    void convertWithAnUnresolvableFromFieldIsANamedCompileErrorNotASilentSkip() {
        String json = MODEL_JSON.replace("\"from\": \"priorityText\", \"to\": \"priorityNumber\"",
                "\"from\": \"noSuchField\", \"to\": \"priorityNumber\"");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> compile(json));
        assertTrue(exception.getMessage().contains("noSuchField"), exception.getMessage());
        assertTrue(exception.getMessage().contains("0005-convert-priority"), exception.getMessage());
    }

    /** Wave 4 (B13 vocabulary expansion): coalesce with fewer than 2 source fields is refused --
     *  same "that's just copy" reasoning and same schema-first-then-compiler defense-in-depth shape
     *  merge's own equivalent test proves above. */
    @Test
    void coalesceWithFewerThanTwoSourceFieldsIsANamedCompileError() {
        String json = MODEL_JSON.replace(
                "\"from\": [ \"primaryEmail\", \"secondaryEmail\" ], \"to\": \"contactEmail\"",
                "\"from\": [ \"primaryEmail\" ], \"to\": \"contactEmail\"");
        IOException exception = assertThrows(IOException.class, () -> compile(json));
        assertTrue(exception.getMessage().contains("schema validation failed"), exception.getMessage());
        assertTrue(exception.getMessage().contains("conversions[5].from"), exception.getMessage());
    }

    @Test
    void coalesceWithAnUnresolvableSourceFieldIsANamedCompileErrorNotASilentSkip() {
        String json = MODEL_JSON.replace("\"primaryEmail\", \"secondaryEmail\" ], \"to\": \"contactEmail\"",
                "\"primaryEmail\", \"noSuchField\" ], \"to\": \"contactEmail\"");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> compile(json));
        assertTrue(exception.getMessage().contains("noSuchField"), exception.getMessage());
        assertTrue(exception.getMessage().contains("0006-coalesce-contact-email"), exception.getMessage());
    }

    @Test
    void caseWithAnUnresolvableFromFieldIsANamedCompileErrorNotASilentSkip() {
        String json = MODEL_JSON.replace("\"from\": \"regionCode\", \"to\": \"regionLabel\"",
                "\"from\": \"noSuchField\", \"to\": \"regionLabel\"");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> compile(json));
        assertTrue(exception.getMessage().contains("noSuchField"), exception.getMessage());
        assertTrue(exception.getMessage().contains("0007-case-region-label"), exception.getMessage());
    }

    /** Wave 4 (B13 vocabulary expansion): 'else' is optional -- a case with none declared must emit
     *  a bare {@code CASE WHEN ... END} with no ELSE clause, so an unmatched row is left NULL (the
     *  X0 discipline's data-layer half is proven separately, against real H2, by
     *  {@code ConversionHookRunnerDeclaredConversionsTest#caseConversionWithNoElseOnAnUnmatchedValueFailsTheBootLoudlyRatherThanLeavingANullResidue}). */
    @Test
    void caseWithNoElseOmitsTheElseClauseFromTheGeneratedSql(@TempDir Path tempDir) throws IOException {
        String json = MODEL_JSON.replaceAll(",\\s*\"else\":\\s*\"Unknown\"", "");
        CompiledModel model = compile(json);
        Path outRoot = tempDir.resolve("App");
        new ConversionHookEmitter().emit(model, null, outRoot);
        Path hooksOut = outRoot.resolve("src/main/resources/db/conversion-hooks");

        String convertSql = Files.readString(hooksOut.resolve("0007-case-region-label").resolve("convert.sql"),
                StandardCharsets.UTF_8);
        assertTrue(convertSql.contains(
                "UPDATE orders SET region_label = CASE WHEN region_code = 'N' THEN 'North' "
                        + "WHEN region_code = 'S' THEN 'South' END WHERE region_label IS NULL"), convertSql);
        assertTrue(!convertSql.contains("ELSE"), "no elseValue declared -- generated SQL must carry no ELSE clause:\n" + convertSql);
    }

    /** Wave 4 (B13 vocabulary expansion): case with zero 'when' clauses is refused by the schema's
     *  own {@code minItems: 1}, same defense-in-depth shape as coalesce/merge above. */
    @Test
    void caseWithNoWhenClausesIsANamedCompileError() {
        String json = MODEL_JSON.replace(
                "\"when\": [ { \"equals\": \"N\", \"then\": \"North\" }, { \"equals\": \"S\", \"then\": \"South\" } ],",
                "\"when\": [ ],");
        IOException exception = assertThrows(IOException.class, () -> compile(json));
        assertTrue(exception.getMessage().contains("schema validation failed"), exception.getMessage());
        assertTrue(exception.getMessage().contains("conversions[6].when"), exception.getMessage());
    }
}
