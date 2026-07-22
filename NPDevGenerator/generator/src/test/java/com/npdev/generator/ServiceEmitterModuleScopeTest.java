package com.npdev.generator;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.NpdevSettings;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingScope;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves MODULE settings-cascade scope is genuinely reachable through real generation, not just
 * resolver-unit-tested in isolation: a concept declaring {@code module: "Billing"} picks up a
 * {@code module:Billing} config override with NO concept-scope override present at all, and a
 * concept-scope override still wins over a conflicting module-scope one when both exist.
 */
class ServiceEmitterModuleScopeTest {

    private static Path writeModel(String json) throws IOException {
        Path modelPath = Files.createTempFile("npdev-module-scope-", ".json");
        Files.writeString(modelPath, json);
        return modelPath;
    }

    private static CompiledModel compile(Path modelPath) throws Exception {
        ModelAst ast = new JsonModelParser().parse(modelPath);
        return new ModelCompiler().compile(ast);
    }

    private static Path invoiceModel() throws IOException {
        return writeModel("""
                {
                  "namespace": "module.scope.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Invoice",
                      "module": "Billing",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "amount", "type": "int", "required": true }
                      ]
                    }
                  ]
                }
                """);
    }

    @Test
    void moduleScopeOverrideAppliesWithNoConceptScopeOverridePresent() throws Exception {
        Path modelPath = invoiceModel();
        CompiledModel compiled = compile(modelPath);
        Path out = Files.createTempDirectory("npdev-module-scope-apply-");
        Path migrations = Files.createTempDirectory("npdev-module-scope-apply-mig-");

        SettingStore store = SettingStore.builder()
                .layer(SettingScope.MODULE, "module:Billing",
                        Map.of(NpdevSettings.PERSISTENCE_ADAPTER.id(), "audited"), "test module override")
                .build();

        new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()),
                new SettingResolver(store))
                .generate(compiled, out, migrations, modelPath);

        String generated = Files.readString(out.resolve("src/main/java/com/npdev/generated/services/InvoiceServiceBase.java"));
        assertTrue(generated.contains("AuditingConceptStoreDecorator"), generated);
    }

    @Test
    void conceptScopeOverrideStillWinsOverConflictingModuleScope() throws Exception {
        Path modelPath = invoiceModel();
        CompiledModel compiled = compile(modelPath);
        Path out = Files.createTempDirectory("npdev-module-scope-precedence-");
        Path migrations = Files.createTempDirectory("npdev-module-scope-precedence-mig-");

        SettingStore store = SettingStore.builder()
                .layer(SettingScope.MODULE, "module:Billing",
                        Map.of(NpdevSettings.PERSISTENCE_ADAPTER.id(), "audited"), "test module override")
                .layer(SettingScope.CONCEPT, "concept:Invoice",
                        Map.of(NpdevSettings.PERSISTENCE_ADAPTER.id(), ""), "test concept override wins")
                .build();

        new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()),
                new SettingResolver(store))
                .generate(compiled, out, migrations, modelPath);

        String generated = Files.readString(out.resolve("src/main/java/com/npdev/generated/services/InvoiceServiceBase.java"));
        assertFalse(generated.contains("AuditingConceptStoreDecorator"), generated);
    }
}
