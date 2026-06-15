package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelCompilerPackNamingTest {

    @Test
    void packNamespacedConceptCompilesToSafeClassName() throws Exception {
        CompiledConcept product = compileConcept("cat::Product");

        assertEquals("CatProduct", product.getClassName());
    }

    @Test
    void packNamespacedConceptCompilesToSafeTableName() throws Exception {
        CompiledConcept product = compileConcept("cat::Product");

        assertEquals("cat_products", product.getTableName());
    }

    @Test
    void packAliasAffectsCompiledClassAndTableName() throws Exception {
        CompiledConcept variant = compileConcept("catalog::Variant");

        assertEquals("CatalogVariant", variant.getClassName());
        assertEquals("catalog_variants", variant.getTableName());
    }

    @Test
    void hyphenatedPackAliasCompilesToSafeClassAndTableName() throws Exception {
        CompiledConcept product = compileConcept("sales-core::Product");

        assertEquals("SalesCoreProduct", product.getClassName());
        assertEquals("sales_core_products", product.getTableName());
    }

    private static CompiledConcept compileConcept(String conceptName) throws Exception {
        Path modelPath = Files.createTempFile("npdev-pack-naming-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "pack.naming.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "%s",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """.formatted(conceptName));

        ModelAst ast = new JsonModelParser().parse(modelPath);
        CompiledModel compiled = new ModelCompiler().compile(ast);
        return (CompiledConcept) compiled.findEntity(conceptName).orElseThrow();
    }
}
