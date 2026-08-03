package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B20 (S2, {@code __OutsideRepo\s1\b20-design.md} D1-D4 + D8, owner-accepted 2026-08-03): the RED/
 * GREEN proof for bounded-context composition. Each test names the exact model-level behaviour a
 * B20 design decision promises -- D1's {@code context::Concept} qualification, D2's fragment-file
 * composition (reusing the existing pack/$ref machinery, not a parallel mechanism), D3's explicit
 * import gate (an undeclared cross-context reference is a NAMED error, never a silent resolve), and
 * D8's acyclic-import-graph requirement.
 */
class BoundedContextResolutionTest {

    @TempDir
    Path temp;

    @Test
    void twoContextsComposeWithQualifiedNamesAndAnImportedReferenceResolves() throws Exception {
        write("contexts/inventory.json", contextFragment("inventory", """
                "concepts": [
                  { "name": "Widget", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                ]
                """, null));
        write("contexts/sales.json", contextFragment("sales", """
                "concepts": [
                  { "name": "Order", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                ],
                "queries": [
                  { "name": "OpenWidgets", "concept": "inventory::Widget", "where": "id != null" }
                ]
                """, "[\"inventory\"]"));
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "contexts": [
                    { "name": "inventory", "$ref": "contexts/inventory.json" },
                    { "name": "sales", "$ref": "contexts/sales.json" }
                  ],
                  "concepts": []
                }
                """);

        JsonNode resolved = new ModelSourceResolver().resolve(model).resolvedRoot();
        JsonNode concepts = resolved.get("concepts");

        assertEquals("inventory::Widget", concepts.get(0).get("name").asText());
        assertEquals("sales::Order", concepts.get(1).get("name").asText());
        // The already-qualified reference is left as-authored, not double-rewritten.
        assertEquals("inventory::Widget", resolved.get("queries").get(0).get("concept").asText());

        // The declared-contexts registry itself survives for introspection (ContextAst).
        ModelAst ast = new JsonModelParser().parse(model);
        assertEquals(2, ast.getContexts().size());
        assertEquals("inventory", ast.getContexts().get(0).name());
        assertEquals("sales", ast.getContexts().get(1).name());
    }

    @Test
    void undeclaredCrossContextReferenceFailsWithNamedError() throws Exception {
        write("contexts/inventory.json", contextFragment("inventory", """
                "concepts": [
                  { "name": "Widget", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                ]
                """, null));
        // "sales" does NOT import "inventory" -- its query reference is undeclared.
        write("contexts/sales.json", contextFragment("sales", """
                "concepts": [
                  { "name": "Order", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                ],
                "queries": [
                  { "name": "OpenWidgets", "concept": "inventory::Widget", "where": "id != null" }
                ]
                """, null));
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "contexts": [
                    { "name": "inventory", "$ref": "contexts/inventory.json" },
                    { "name": "sales", "$ref": "contexts/sales.json" }
                  ],
                  "concepts": []
                }
                """);

        IOException failure = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(failure.getMessage().contains("does not declare"), failure.getMessage());
        assertTrue(failure.getMessage().contains("inventory::Widget"), failure.getMessage());
    }

    @Test
    void importOfUndeclaredContextFailsAtDeclarationTime() throws Exception {
        write("contexts/sales.json", contextFragment("sales", """
                "concepts": [
                  { "name": "Order", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                ]
                """, "[\"noSuchContext\"]"));
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "contexts": [
                    { "name": "sales", "$ref": "contexts/sales.json" }
                  ],
                  "concepts": []
                }
                """);

        IOException failure = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(failure.getMessage().contains("undeclared context 'noSuchContext'"), failure.getMessage());
    }

    @Test
    void contextImportingItselfIsRejected() throws Exception {
        write("contexts/sales.json", contextFragment("sales", """
                "concepts": [
                  { "name": "Order", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                ]
                """, "[\"sales\"]"));
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "contexts": [
                    { "name": "sales", "$ref": "contexts/sales.json" }
                  ],
                  "concepts": []
                }
                """);

        IOException failure = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(failure.getMessage().contains("imports itself"), failure.getMessage());
    }

    @Test
    void importCycleIsRejectedAsAcyclicityRequires() throws Exception {
        write("contexts/a.json", contextFragment("a", """
                "concepts": [
                  { "name": "AThing", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                ]
                """, "[\"b\"]"));
        write("contexts/b.json", contextFragment("b", """
                "concepts": [
                  { "name": "BThing", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                ]
                """, "[\"a\"]"));
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "contexts": [
                    { "name": "a", "$ref": "contexts/a.json" },
                    { "name": "b", "$ref": "contexts/b.json" }
                  ],
                  "concepts": []
                }
                """);

        IOException failure = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(failure.getMessage().contains("Import cycle detected"), failure.getMessage());
    }

    @Test
    void packQualifiedReferenceInsideAContextRemainsUnrestricted() throws Exception {
        // D3's gate only applies to references naming a DECLARED CONTEXT; a pack-qualified reference
        // is unrestricted, exactly as it already is today without any bounded context involved.
        write("pack/catalog.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "catalog",
                  "version": "1.0.0",
                  "concepts": [
                    { "name": "Product", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);
        write("contexts/sales.json", contextFragment("sales", """
                "concepts": [
                  { "name": "Order", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                ],
                "queries": [
                  { "name": "AllProducts", "concept": "catalog::Product", "where": "id != null" }
                ]
                """, null));
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [{ "$ref": "pack/catalog.json" }],
                  "contexts": [
                    { "name": "sales", "$ref": "contexts/sales.json" }
                  ],
                  "concepts": []
                }
                """);

        JsonNode resolved = new ModelSourceResolver().resolve(model).resolvedRoot();
        assertEquals("catalog::Product", resolved.get("queries").get(0).get("concept").asText());
    }

    @Test
    void modelWithNoContextsCompilesWithAnEmptyContextRegistry() throws Exception {
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Plain", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(model);
        assertTrue(ast.getContexts().isEmpty());
        JsonNode resolved = new ModelSourceResolver().resolve(model).resolvedRoot();
        assertFalse(resolved.has("contexts"), "a model with no contexts[] key should not gain one");
    }

    /** A context fragment is schema-shaped like a pack (D2: same composition machinery), so it needs
     *  the same required {@code pack}/{@code dslVersion}/{@code version} identity fields -- by
     *  convention set {@code pack} equal to the context's own name to avoid confusion, though nothing
     *  in the resolver reads it (the ROOT model's {@code contexts[].name} is what actually qualifies). */
    private static String contextFragment(String name, String bodyFields, String importsArrayJsonOrNull) {
        String importsField = importsArrayJsonOrNull == null
                ? ""
                : ",\n  \"imports\": " + importsArrayJsonOrNull;
        return """
                {
                  "dslVersion": "1.0.0",
                  "pack": "%s",
                  "version": "1.0.0",
                  %s%s
                }
                """.formatted(name, bodyFields, importsField);
    }

    private Path write(String relative, String content) throws IOException {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
