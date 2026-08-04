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
 * S7 Phase B (B13 declarative conversion vocabulary): proves the compiler resolves and the
 * generator compiles each of the three ops (copy/split/lookup) to the exact
 * {@code db/conversion-hooks/<id>/{hook.json,convert.sql}} shape {@link ConversionHookRunner}
 * (NPDevRuntimeHost) executes at boot -- and that an unresolvable field/op is a named compile
 * error, never a silently-dropped conversion (the vocabulary's own X0 rule). The generated SQL's
 * actual DATA-LAYER correctness (real conversion, idempotence) is proven separately, against a
 * real H2 database, by NPDevRuntimeHost's {@code ConversionHookRunnerDeclaredConversionsTest} --
 * this test proves the generator produces that exact SQL in the first place.
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
                    { "name": "lastName", "type": "string", "required": true }
                ] },
                { "name": "Order", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "legacyCode", "type": "string" },
                    { "name": "externalRef", "type": "string", "required": true }
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
                  "set": "productId" }
              ]
            }
            """;

    @Test
    void compilesAllThreeOpsToHookJsonAndConvertSqlOnDisk(@TempDir Path tempDir) throws IOException {
        CompiledModel model = compile(MODEL_JSON);
        assertEquals(3, model.getConversions().size());

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
}
