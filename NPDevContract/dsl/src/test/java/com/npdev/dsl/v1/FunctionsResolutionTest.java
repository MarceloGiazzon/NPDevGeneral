package com.npdev.dsl.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FORM P4-T2: {@code functions[]} are validated and INLINED while the model is resolved. */
class FunctionsResolutionTest {

    @TempDir
    Path temp;

    private static final String PACK = """
            {
              "dslVersion": "3.0.0",
              "pack": "pricing",
              "version": "1.0.0",
              "functions": [
                { "name": "surge", "returns": "decimal",
                  "params": [ { "name": "open", "type": "integer" }, { "name": "free", "type": "integer" } ],
                  "body": "max(open - free, 0) * 0.1 + 1" },
                { "name": "price", "returns": "decimal",
                  "params": [ { "name": "base", "type": "decimal" }, { "name": "open", "type": "integer" }, { "name": "free", "type": "integer" } ],
                  "body": "base * surge(open, free)" }
              ],
              "concepts": [
                { "name": "Quote", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "amount", "type": "decimal", "defaultExpression": "price(base, open, free)" }
                ] }
              ]
            }
            """;

    private String model(String fieldExpression, String extraFunctions) {
        return """
                {
                  "namespace": "demo", "dslVersion": "3.0.0", "version": "1.0",
                  "packs": [ { "$ref": "pack/pricing.json" } ],
                  "functions": [ %s
                    { "name": "double", "returns": "decimal", "params": [ { "name": "x", "type": "decimal" } ], "body": "x * 2" }
                  ],
                  "concepts": [ { "name": "Ride", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "fare", "type": "decimal", "defaultExpression": "%s" }
                  ] } ]
                }
                """.formatted(extraFunctions, fieldExpression);
    }

    private JsonNode resolve(String modelJson) throws IOException {
        write("pack/pricing.json", PACK);
        Path model = write("model.json", modelJson);
        ResolvedModelSource source = new ModelSourceResolver().resolve(model);
        return source.resolvedRoot();
    }

    private String fieldExpression(JsonNode root, String concept, String field) {
        for (JsonNode c : root.get("concepts")) {
            if (c.get("name").asText().equals(concept)) {
                for (JsonNode f : c.get("fields")) {
                    if (f.get("name").asText().equals(field)) {
                        return f.get("defaultExpression").asText();
                    }
                }
            }
        }
        throw new AssertionError("no " + concept + "." + field);
    }

    @Test
    void rootAndPackFunctionsAreInlinedAndTheArrayDisappears() throws Exception {
        JsonNode root = resolve(model("double(pricing::price(base, open, free)) + 1", ""));
        assertFalse(root.has("functions"), "functions are consumed, never reach the parser");
        // root function wrapping a pack function: both expanded away, arguments substituted
        String fare = fieldExpression(root, "Ride", "fare");
        assertFalse(fare.contains("double(") || fare.contains("price("), fare);
        assertTrue(fare.contains("max(open - free, 0)") && fare.contains("base"), fare);
        // the pack's own expression used the pack-local bare name `price`
        String quote = fieldExpression(root, "pricing::Quote", "amount");
        assertFalse(quote.contains("price("), quote);
        assertFalse(quote.contains("surge("), quote);
        assertTrue(quote.contains("max(open - free, 0)"), quote);
    }

    @Test
    void aModelWithoutFunctionsIsUntouched() throws Exception {
        write("model.json", """
                { "namespace": "demo", "dslVersion": "3.0.0", "version": "1.0",
                  "concepts": [ { "name": "A", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "n", "type": "decimal", "defaultExpression": "max(1, 2) * 3" } ] } ] }
                """);
        JsonNode root = new ModelSourceResolver().resolve(temp.resolve("model.json")).resolvedRoot();
        assertEquals("max(1, 2) * 3", fieldExpression(root, "A", "n"));
    }

    private String failure(String modelJson) throws IOException {
        return assertThrows(IOException.class, () -> resolve(modelJson)).getMessage();
    }

    @Test
    void aRecursiveFunctionIsRefusedWithItsCallPath() throws Exception {
        String msg = failure(model("1", """
                { "name": "ping", "returns": "integer", "params": [ { "name": "n", "type": "integer" } ], "body": "pong(n)" },
                { "name": "pong", "returns": "integer", "params": [ { "name": "n", "type": "integer" } ], "body": "ping(n)" },"""));
        assertTrue(msg.contains("calls itself: ping -> pong -> ping"), msg);
    }

    @Test
    void aFunctionThatReadsARowFieldIsRefused() throws Exception {
        String msg = failure(model("1", """
                { "name": "leaky", "returns": "decimal", "params": [], "body": "price * 2" },"""));
        assertTrue(msg.contains("not one of its parameters"), msg);
    }

    @Test
    void aBodyThatDoesNotMatchItsReturnTypeIsRefused() throws Exception {
        String msg = failure(model("1", """
                { "name": "bad", "returns": "boolean", "params": [ { "name": "n", "type": "integer" } ], "body": "n * 2" },"""));
        assertTrue(msg.contains("returns boolean"), msg);
    }

    @Test
    void aFunctionMayNotShadowABuiltIn() throws Exception {
        String msg = failure(model("1", """
                { "name": "max", "returns": "decimal", "params": [], "body": "1" },"""));
        assertTrue(msg.contains("shadow the built-in"), msg);
    }

    @Test
    void aCallWithTheWrongNumberOfArgumentsNamesTheLocation() throws Exception {
        String msg = failure(model("double(1, 2)", ""));
        assertTrue(msg.contains("function double takes 1 argument(s)"), msg);
    }

    private Path write(String relative, String content) throws IOException {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
