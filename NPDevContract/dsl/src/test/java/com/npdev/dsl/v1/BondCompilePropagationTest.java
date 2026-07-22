package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Phase 1: bond attributes (via / onDelete / connectable) and concept truthLevel must
 * survive parse -> compile -> canonical JSON -> read-back unchanged.
 */
class BondCompilePropagationTest {

    @Test
    void bondAttributesAndTruthLevelSurviveCompileAndCanonicalRoundTrip() throws Exception {
        Path modelPath = Files.createTempFile("npdev-bond-compile-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "bond.compile.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Product",
                      "truthLevel": "T3",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "skuId", "type": "string", "unique": true, "connectable": "anchor" }
                      ]
                    },
                    {
                      "name": "Invoice",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "productId", "type": "reference",
                          "reference": { "target": "Product", "via": "skuId", "onDelete": "cascade" } }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        CompiledModel compiled = new ModelCompiler().compile(ast);

        // Survives compile.
        assertBondCarried(compiled, "compiled");

        // Survives canonical JSON round-trip.
        String json = CompiledModelCanonicalJson.toJson(compiled);
        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(json);
        assertBondCarried(restored, "round-tripped");
    }

    private static void assertBondCarried(CompiledModel model, String stage) {
        CompiledConcept product = model.findConcept("Product").orElseThrow();
        assertEquals("T3", product.getTruthLevel(), stage + ": concept truthLevel must survive");
        CompiledField sku = field(product, "skuId");
        assertEquals("anchor", sku.getConnectable(), stage + ": connectable anchor must survive");

        CompiledConcept invoice = model.findConcept("Invoice").orElseThrow();
        assertEquals("T1", invoice.getTruthLevel(), stage + ": default truthLevel must be T1");
        CompiledField productRef = field(invoice, "productId");
        assertNotNull(productRef.getReferenceSemantics(), stage + ": reference semantics present");
        assertEquals("skuId", productRef.getReferenceSemantics().getVia(), stage + ": via must survive");
        assertEquals("cascade", productRef.getReferenceSemantics().getOnDelete(), stage + ": onDelete must survive");
    }

    private static CompiledField field(CompiledConcept concept, String name) {
        return concept.getFields().stream()
                .filter(f -> name.equals(f.getName()))
                .findFirst()
                .orElseThrow();
    }
}
