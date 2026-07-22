package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code module} is the real DSL anchor for MODULE settings-cascade scope (previously the
 * resolver/config-reading mechanism was test-proven, but no concept in any real model ever
 * declared a module, so {@code SettingTarget.conceptInModule} was never actually reachable end to
 * end). This proves the anchor survives parse -> compile -> specialization merge.
 */
class ConceptModuleTest {

    private static Path writeModel(String json) throws IOException {
        Path modelPath = Files.createTempFile("npdev-concept-module-", ".json");
        Files.writeString(modelPath, json);
        return modelPath;
    }

    @Test
    void conceptModuleSurvivesParseAndCompile() throws Exception {
        Path modelPath = writeModel("""
                {
                  "namespace": "concept.module.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Invoice",
                      "module": "Billing",
                      "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ]
                    }
                  ]
                }
                """);

        ModelAst parsed = new JsonModelParser().parse(modelPath);
        assertEquals("Billing", parsed.getConcepts().get(0).getModule());

        CompiledModel compiled = new ModelCompiler().compile(parsed);
        assertEquals("Billing", compiled.getConcepts().stream()
                .filter(c -> c.getName().equals("Invoice"))
                .findFirst().orElseThrow()
                .getModule());
    }

    @Test
    void conceptWithoutModuleCompilesWithNullModule() throws Exception {
        Path modelPath = writeModel("""
                {
                  "namespace": "concept.module.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Order",
                      "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ]
                    }
                  ]
                }
                """);

        CompiledModel compiled = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        assertNull(compiled.getConcepts().stream()
                .filter(c -> c.getName().equals("Order"))
                .findFirst().orElseThrow()
                .getModule());
    }

    @Test
    void specializationInheritsBaseModuleWhenNotOverridden() throws Exception {
        Path modelPath = writeModel("""
                {
                  "namespace": "concept.module.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Invoice",
                      "module": "Billing",
                      "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ]
                    },
                    {
                      "name": "CreditNote",
                      "specializes": "Invoice",
                      "fields": [ { "name": "reason", "type": "string" } ]
                    }
                  ]
                }
                """);

        CompiledModel compiled = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        assertEquals("Billing", compiled.getConcepts().stream()
                .filter(c -> c.getName().equals("CreditNote"))
                .findFirst().orElseThrow()
                .getModule());
    }

    @Test
    void specializationOverridesBaseModuleWhenDeclared() throws Exception {
        Path modelPath = writeModel("""
                {
                  "namespace": "concept.module.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Invoice",
                      "module": "Billing",
                      "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ]
                    },
                    {
                      "name": "ArchivedInvoice",
                      "specializes": "Invoice",
                      "module": "Archive",
                      "fields": [ { "name": "archivedAt", "type": "string" } ]
                    }
                  ]
                }
                """);

        CompiledModel compiled = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        assertEquals("Archive", compiled.getConcepts().stream()
                .filter(c -> c.getName().equals("ArchivedInvoice"))
                .findFirst().orElseThrow()
                .getModule());
    }
}
