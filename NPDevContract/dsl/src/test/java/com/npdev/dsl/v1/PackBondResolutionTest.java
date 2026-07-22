package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackBondResolutionTest {
    @TempDir
    Path temp;

    @Test
    void packNaturalKeyAnchorResolvesSemanticallyAndSurvivesCompile() throws Exception {
        write("packs/catalog/fragments/product.json", """
                {
                  "concepts": [
                    { "name": "Product", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "skuId", "type": "string", "required": true, "unique": true, "connectable": "anchor" }
                    ] }
                  ]
                }
                """);
        write("packs/catalog/fragments/variant.json", """
                {
                  "concepts": [
                    { "name": "Variant", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      {
                        "name": "productSku",
                        "type": "reference",
                        "required": true,
                        "reference": { "target": "Product", "via": "skuId", "onDelete": "cascade" }
                      }
                    ] }
                  ]
                }
                """);
        write("packs/catalog/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "catalog",
                  "version": "1.0.0",
                  "fragments": [
                    { "$ref": "fragments/product.json" },
                    { "$ref": "fragments/variant.json" }
                  ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "pack.bond.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [
                    { "$ref": "packs/catalog/pack.json", "as": "cat" }
                  ],
                  "concepts": [
                    { "name": "Order", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      {
                        "name": "productSku",
                        "type": "reference",
                        "reference": { "target": "cat::Product", "via": "skuId", "onDelete": "restrict" }
                      }
                    ] }
                  ]
                }
                """);

        ResolvedModelSource resolvedSource = new ModelSourceResolver().resolve(model);
        ModelAst ast = new JsonModelParser().parse(resolvedSource);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected pack bond model to validate, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledConcept product = compiled.findConcept("cat::Product").orElseThrow();
        CompiledConcept variant = compiled.findConcept("cat::Variant").orElseThrow();
        CompiledConcept order = compiled.findConcept("Order").orElseThrow();

        assertEquals("CatProduct", product.getClassName());
        assertEquals("cat_products", product.getTableName());
        assertEquals("cat::Product", field(variant, "productSku").getReferenceSemantics().getTarget());
        assertEquals("skuId", field(variant, "productSku").getReferenceSemantics().getVia());
        assertEquals("cascade", field(variant, "productSku").getReferenceSemantics().getOnDelete());
        assertEquals("cat::Product", field(order, "productSku").getReferenceSemantics().getTarget());
        assertEquals("skuId", field(order, "productSku").getReferenceSemantics().getVia());
    }

    private static CompiledField field(CompiledConcept concept, String name) {
        return concept.getFields().stream()
                .filter(field -> name.equals(field.getName()))
                .findFirst()
                .orElseThrow();
    }

    private Path write(String relative, String content) throws Exception {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
