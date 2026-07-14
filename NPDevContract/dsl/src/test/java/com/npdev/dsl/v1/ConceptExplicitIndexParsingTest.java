package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledIndex;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-6 (2/2): a concept's {@code indexes:[]} authoring block parses end-to-end into
 * {@link CompiledIndex} entries on the {@link CompiledConcept} -- the model-schema.json addition,
 * JsonModelParser, and ModelCompiler wiring all agree on the same shape.
 */
class ConceptExplicitIndexParsingTest {

    @Test
    void authoredIndexesCompileToCompiledIndexEntries() throws Exception {
        Path modelPath = Files.createTempFile("npdev-explicit-index-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "explicit.index.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Shipment",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "carrier", "type": "string", "required": true },
                        { "name": "trackingCode", "type": "string", "required": true },
                        { "name": "warehouseId", "type": "string", "required": true }
                      ],
                      "indexes": [
                        { "fields": ["carrier", "warehouseId"] },
                        { "name": "ux_shipment_tracking", "fields": ["trackingCode"], "unique": true }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledConcept shipment = compiled.findConcept("Shipment").orElseThrow();

        List<CompiledIndex> indexes = shipment.getIndexes();
        assertEquals(2, indexes.size());

        CompiledIndex multiColumn = indexes.get(0);
        assertEquals(List.of("carrier", "warehouseId"), multiColumn.getFields());
        assertTrue(!multiColumn.isUnique());

        CompiledIndex unique = indexes.get(1);
        assertEquals("ux_shipment_tracking", unique.getName());
        assertEquals(List.of("trackingCode"), unique.getFields());
        assertTrue(unique.isUnique());
    }

    @Test
    void indexWithoutFieldsIsRejected() {
        assertThrows(Exception.class, () -> {
            Path modelPath = Files.createTempFile("npdev-explicit-index-invalid-", ".json");
            Files.writeString(modelPath, """
                    {
                      "namespace": "explicit.index.invalid",
                      "dslVersion": "1.0.0",
                      "version": "1.0",
                      "concepts": [
                        {
                          "name": "Shipment",
                          "fields": [
                            { "name": "id", "type": "uuid", "id": true, "required": true }
                          ],
                          "indexes": [
                            { "unique": true }
                          ]
                        }
                      ]
                    }
                    """);
            new JsonModelParser().parse(modelPath);
        });
    }

    @Test
    void indexesSurviveNoInheritanceResolution() throws Exception {
        // Regression: ModelResolver.sanitizeConcept (the path every concept without extends/specializes
        // goes through) used to reconstruct ConceptAst via the pre-indexes constructor, silently
        // dropping any declared indexes even though JsonModelParser/ModelCompiler both carried them.
        Path modelPath = Files.createTempFile("npdev-index-no-inherit-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "explicit.index.no.inherit",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Widget",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "sku", "type": "string", "required": true }
                      ],
                      "indexes": [
                        { "fields": ["sku"] }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledConcept widget = compiled.findConcept("Widget").orElseThrow();

        assertEquals(1, widget.getIndexes().size());
        assertEquals(List.of("sku"), widget.getIndexes().get(0).getFields());
    }

    @Test
    void indexesSurviveSpecializesInheritanceMerge() throws Exception {
        // Regression: ModelResolver.mergeConcept (the specializes/extends path) had the same drop.
        // A specialized concept must see both its own and its base's declared indexes.
        Path modelPath = Files.createTempFile("npdev-index-inherit-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "explicit.index.inherit",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Base",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "code", "type": "string", "required": true }
                      ],
                      "indexes": [
                        { "fields": ["code"] }
                      ]
                    },
                    {
                      "name": "Derived",
                      "specializes": "Base",
                      "fields": [
                        { "name": "extra", "type": "string", "required": true }
                      ],
                      "indexes": [
                        { "fields": ["extra"] }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledConcept derived = compiled.findConcept("Derived").orElseThrow();

        List<List<String>> indexFieldLists = derived.getIndexes().stream().map(CompiledIndex::getFields).toList();
        assertTrue(indexFieldLists.contains(List.of("code")), "inherited base index must survive merge: " + indexFieldLists);
        assertTrue(indexFieldLists.contains(List.of("extra")), "specialization's own index must survive merge: " + indexFieldLists);
    }

    @Test
    void conceptWithNoIndexesCompilesWithAnEmptyList() throws Exception {
        Path modelPath = Files.createTempFile("npdev-no-index-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "no.index.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Plain",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertTrue(compiled.findConcept("Plain").orElseThrow().getIndexes().isEmpty());
    }
}
