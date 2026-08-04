package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.dsl.v1.validation.SemanticValidator;
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
    void validlyImportedButNonexistentConceptFailsAtCompileTimeNotResolveTime() throws Exception {
        // D3's gate (ModelSourceResolver) only checks whether the CROSS-CONTEXT REFERENCE is
        // declared -- it does not know whether the target concept actually exists. That existence
        // check is the compiler/semantic-validation layer's job (S2_SPEC.md sec.5's X0 requirement:
        // "unresolvable qualified reference -> named compile error"), and it already exists today
        // for pack-qualified names (PackValidation "concept not found") -- this proves it also fires
        // for a validly-imported but nonexistent CONTEXT-qualified name, with no new code needed.
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
                  { "name": "GhostQuery", "concept": "inventory::Ghost", "where": "id != null" }
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

        // Resolution itself succeeds -- "inventory" IS a declared import of "sales" (D3's gate).
        ModelAst ast = new JsonModelParser().parse(model);

        // But the target concept "inventory::Ghost" does not exist -- caught downstream, not silently.
        java.util.List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.stream().anyMatch(e -> e.contains("concept not found") && e.contains("inventory::Ghost")),
                errors.toString());
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

    /** S8 Wave 4 (ADR-0011 D4's v2 opt-in): a context declaring {@code physicallyIsolate} is NOT
     *  "malformed" -- {@code validateNoMalformedRef}'s own structural gate (checked BEFORE
     *  {@code model.schema.json}'s {@code context} definition even applies) hard-codes which extra
     *  keys a {@code /contexts/N} entry may carry alongside {@code $ref}, and had to be widened
     *  here too, a genuine fourth edit site the plan's own "three edit sites, all confirmed" list
     *  did not name -- found only by validating a REAL context-fragment-composing model
     *  (dsl-conformance-max), not by the AST-level tests above, which all bypass this resolver-level
     *  gate by feeding {@link JsonModelParser} already-resolved JSON directly. */
    @Test
    void physicallyIsolateOnAContextDeclarationIsNotMalformed() throws Exception {
        write("contexts/wms.json", contextFragment("wms", """
                "concepts": [
                  { "name": "Sale", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                ]
                """, null));
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "contexts": [
                    { "name": "wms", "$ref": "contexts/wms.json", "physicallyIsolate": true }
                  ],
                  "concepts": []
                }
                """);

        JsonNode resolved = new ModelSourceResolver().resolve(model).resolvedRoot();
        assertEquals("wms::Sale", resolved.get("concepts").get(0).get("name").asText());
        assertTrue(resolved.get("contexts").get(0).get("physicallyIsolate").asBoolean(),
                "physicallyIsolate must survive the resolver's own contexts[] registry rebuild");

        ModelAst ast = new JsonModelParser().parse(model);
        assertTrue(ast.getContexts().get(0).physicallyIsolate());
    }

    @Test
    void physicallyIsolateAbsentDefaultsToFalseAndIsNotEmittedByTheResolver() throws Exception {
        write("contexts/wms.json", contextFragment("wms", """
                "concepts": [
                  { "name": "Sale", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                ]
                """, null));
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "contexts": [ { "name": "wms", "$ref": "contexts/wms.json" } ],
                  "concepts": []
                }
                """);

        JsonNode resolved = new ModelSourceResolver().resolve(model).resolvedRoot();
        assertFalse(resolved.get("contexts").get(0).has("physicallyIsolate"),
                "must not be emitted at all when absent -- I4's byte-identical regression DoD");

        ModelAst ast = new JsonModelParser().parse(model);
        assertFalse(ast.getContexts().get(0).physicallyIsolate());
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
    @Test
    void flowStepScopeIsQualifiedAlongsideTheConceptItInvariantChecks() throws Exception {
        // S3 (found via the bounded-contexts codemod trial against AppGen/apps/pack-sample):
        // invariantCheck / createConcept / updateConcept steps name their target concept via
        // `scope`, the same field FlowValidation.collectConceptMutationScopes reads for all three --
        // this was missing from the rewrite table, so a context-qualified concept's own flow
        // invariantCheck stayed unqualified ("Sale" instead of "wms::Sale") and validation then
        // rejected the mismatch qualification itself introduced.
        write("contexts/wms.json", contextFragment("wms", """
                "concepts": [
                  { "name": "Sale", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                ],
                "flows": [
                  { "name": "ProcessSale",
                    "input": { "concept": "Sale", "mode": "create" },
                    "steps": [
                      { "type": "invariantCheck", "scope": "Sale", "invariants": [] },
                      { "type": "return", "value": "input" }
                    ]
                  }
                ]
                """, null));
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "contexts": [ { "name": "wms", "$ref": "contexts/wms.json" } ],
                  "concepts": []
                }
                """);

        JsonNode resolved = new ModelSourceResolver().resolve(model).resolvedRoot();
        JsonNode step = resolved.get("flows").get(0).get("steps").get(0);

        assertEquals("wms::Sale", resolved.get("flows").get(0).get("input").get("concept").asText());
        assertEquals("wms::Sale", step.get("scope").asText(),
                "invariantCheck's scope must be qualified the same way the concept it checks was");
    }

    /** S4 (ADR-0011 D3 extended to groupBy): a {@code groupBy} join path embedding a
     *  {@code context::} prefix is gate-checked the same way any other qualified reference is --
     *  this proves the HAPPY path (the crossed context IS imported) resolves with the field string
     *  left byte-identical (D1's grammar keeps join hops unqualified; only the context name is
     *  checked, never rewritten). See {@link #groupByJoinCrossingAnUnimportedContextFailsWithNamedError}
     *  for the RED half. */
    @Test
    void groupByJoinCrossingAnImportedContextResolves() throws Exception {
        write("contexts/inventory.json", contextFragment("inventory", """
                "concepts": [
                  { "name": "Lote", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "produtoId", "type": "string" } ] }
                ]
                """, null));
        write("contexts/wms.json", contextFragment("wms", """
                "concepts": [
                  { "name": "Movimento", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "lote", "type": "reference", "reference": { "target": "inventory::Lote" } },
                    { "name": "quantidade", "type": "integer" } ]
                  }
                ],
                "queries": [
                  { "name": "MovimentosPorProduto", "concept": "Movimento",
                    "groupBy": [ "inventory::lote.produtoId" ],
                    "aggregates": [ { "name": "total", "fn": "sum", "field": "quantidade" } ] }
                ]
                """, "[\"inventory\"]"));
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "contexts": [
                    { "name": "inventory", "$ref": "contexts/inventory.json" },
                    { "name": "wms", "$ref": "contexts/wms.json" }
                  ],
                  "concepts": []
                }
                """);

        JsonNode resolved = new ModelSourceResolver().resolve(model).resolvedRoot();
        JsonNode groupBy = resolved.get("queries").get(0).get("groupBy");

        assertEquals("inventory::lote.produtoId", groupBy.get(0).asText(),
                "the join path string must survive resolution byte-identical -- only the embedded "
                        + "context:: prefix is gate-checked, never rewritten");
    }

    /** S4 (ADR-0011 D3 extended to groupBy) RED half: "wms" does NOT import "inventory" -- the
     *  groupBy join's embedded {@code inventory::} prefix must fail exactly like any other
     *  undeclared cross-context reference, never silently resolve. */
    @Test
    void groupByJoinCrossingAnUnimportedContextFailsWithNamedError() throws Exception {
        write("contexts/inventory.json", contextFragment("inventory", """
                "concepts": [
                  { "name": "Lote", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "produtoId", "type": "string" } ] }
                ]
                """, null));
        // "wms" does NOT import "inventory" -- its groupBy join is undeclared.
        write("contexts/wms.json", contextFragment("wms", """
                "concepts": [
                  { "name": "Movimento", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "lote", "type": "reference", "reference": { "target": "inventory::Lote" } },
                    { "name": "quantidade", "type": "integer" } ]
                  }
                ],
                "queries": [
                  { "name": "MovimentosPorProduto", "concept": "Movimento",
                    "groupBy": [ "inventory::lote.produtoId" ],
                    "aggregates": [ { "name": "total", "fn": "sum", "field": "quantidade" } ] }
                ]
                """, null));
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "contexts": [
                    { "name": "inventory", "$ref": "contexts/inventory.json" },
                    { "name": "wms", "$ref": "contexts/wms.json" }
                  ],
                  "concepts": []
                }
                """);

        IOException failure = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(failure.getMessage().contains("does not declare"), failure.getMessage());
        assertTrue(failure.getMessage().contains("inventory::lote.produtoId"), failure.getMessage());
    }

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
