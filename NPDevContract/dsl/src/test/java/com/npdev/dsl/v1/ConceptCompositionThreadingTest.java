package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.AggregateAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledAggregate;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.DeprecationException;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FORM P5-T9e: an aggregate is its root concept's {@code composition}; the aggregate's name IS the concept's name.
 * The AST/IR still carry an {@link AggregateAst} (name == root), so the compiler, canonical JSON and runtime are
 * unchanged; the parser lifts the composition, the resolver qualifies a pack's composition together with its concept.
 */
class ConceptCompositionThreadingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
            {
              "namespace": "composition.demo",
              "dslVersion": "3.0.0",
              "version": "1.0",
              "concepts": [
                { "name": "Order",
                  "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ],
                  "composition": {
                    "collections": [ { "name": "lines", "concept": "OrderLine", "childField": "orderId" } ]
                  } },
                { "name": "OrderLine",
                  "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true },
                              { "name": "orderId", "type": "uuid" } ] }
              ],
              "screens": [{ "name": "OrderWorkbench", "kind": "workbench", "concept": "Order", "admin": { } }]
            }
            """;

    @TempDir
    Path temp;

    @Test
    void aCompositionBecomesAnAggregateNamedAfterItsRootConceptAndRoundTrips() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(MODEL));
        assertEquals(List.of(), new SemanticValidator().validate(ast));
        assertEquals(1, ast.getAggregates().size());
        AggregateAst aggregate = ast.getAggregates().get(0);
        assertEquals("Order", aggregate.name());
        assertEquals("Order", aggregate.root());
        assertEquals("OrderLine", aggregate.collections().get(0).concept());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertEquals(1, compiled.getAggregates().size());
        CompiledAggregate restored = CompiledModelCanonicalJsonReader
                .fromJson(CompiledModelCanonicalJson.toJson(compiled)).getAggregates().get(0);
        assertEquals("Order", restored.name());
        assertFalse(CompiledModelCanonicalJson.toJson(compiled).contains("\"composition\""),
                "the compiled IR keeps its aggregates[] shape: only the authoring surface changed");
    }

    @Test
    void aConceptWithoutACompositionIsNotAnAggregate() throws Exception {
        String json = """
                { "namespace": "composition.none", "dslVersion": "3.0.0", "version": "1.0",
                  "concepts": [ { "name": "Note", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ] }
                """;
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        assertTrue(ast.getAggregates().isEmpty());
    }

    @Test
    void theRemovedTopLevelAggregatesKeyIsRefusedWithAPointerToComposition() {
        String json = MODEL.replace("\"screens\"", "\"aggregates\": [ { \"name\": \"OrderAggregate\", \"root\": \"Order\" } ], \"screens\"");
        DeprecationException refused = assertThrows(DeprecationException.class,
                () -> new JsonModelParser().parse(MAPPER.readTree(json)));
        assertTrue(refused.getMessage().contains("composition"), refused.getMessage());
    }

    @Test
    void aMalformedCompositionNamesItsConceptInTheError() {
        String json = MODEL.replace("\"collections\": [", "\"invariants\": \"oops\", \"collections\": [");
        IOException failure = assertThrows(IOException.class, () -> new JsonModelParser().parse(MAPPER.readTree(json)));
        assertTrue(failure.getMessage().contains("composition.invariants"), failure.getMessage());
    }

    @Test
    void aPacksCompositionIsQualifiedWithItsConceptAndItsCollectionConceptIsRewritten() throws Exception {
        write("packs/shop/pack.json", """
                {
                  "dslVersion": "3.0.0",
                  "pack": "shop",
                  "version": "1.0.0",
                  "concepts": [
                    { "name": "Basket", "access": { "read": "true", "write": "true" },
                      "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ],
                      "composition": { "collections": [ { "name": "items", "concept": "BasketItem", "childField": "basketId" } ] } },
                    { "name": "BasketItem", "access": { "read": "true", "write": "true" },
                      "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true },
                                  { "name": "basketId", "type": "uuid" } ] }
                  ]
                }
                """);
        Path model = write("model.json", """
                { "namespace": "composition.pack", "dslVersion": "3.0.0", "version": "1.0",
                  "packs": [ { "$ref": "packs/shop/pack.json" } ],
                  "screens": [{ "name": "BasketWorkbench", "kind": "workbench", "concept": "Basket", "admin": { } }] }
                """);
        ResolvedModelSource resolved = new ModelSourceResolver().resolve(model);
        JsonNode root = resolved.resolvedRoot();
        assertFalse(root.has("aggregates"), "the composed model.json is authoring-shaped: no top-level aggregates");
        JsonNode basket = null;
        for (JsonNode concept : root.get("concepts")) {
            if ("shop::Basket".equals(concept.path("name").asText())) {
                basket = concept;
            }
        }
        assertTrue(basket != null, "the pack concept is qualified");
        assertEquals("shop::BasketItem", basket.get("composition").get("collections").get(0).get("concept").asText());
        assertEquals("shop::Basket", root.get("screens").get(0).get("concept").asText(),
                "a bare aggregate reference resolves to the pack's qualified concept");

        ModelAst ast = new JsonModelParser().parse(resolved);
        assertEquals(List.of(), new SemanticValidator().validate(ast));
        assertEquals("shop::Basket", ast.getAggregates().get(0).name());
    }

    private Path write(String relative, String content) throws IOException {
        Path target = temp.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        return target;
    }
}
