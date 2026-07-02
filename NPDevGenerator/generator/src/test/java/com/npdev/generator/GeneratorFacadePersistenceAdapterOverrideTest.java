package com.npdev.generator;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.NpdevSettings;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingScope;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.dsl.v1.settings.SettingTarget;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves an unsupported {@code persistence.adapter} override fails generation loudly instead of
 * silently falling through unwrapped -- the bug fixed alongside this test: the generated
 * service-base.mustache wrap used to compare the resolved override string against the literal
 * "audited" at the TEMPLATE level, so a typo'd or stale value (e.g. "audit") generated cleanly and
 * simply never applied, with nothing to say so.
 */
class GeneratorFacadePersistenceAdapterOverrideTest {

    private static Path canonicalDemoModel() {
        return Path.of("..", "resources", "Models", "canonical-demo", "model.json").normalize();
    }

    private static CompiledModel compileCanonicalDemo() throws Exception {
        ModelAst ast = new JsonModelParser().parse(canonicalDemoModel());
        return new ModelCompiler().compile(ast);
    }

    @Test
    void unsupportedPersistenceAdapterOverrideFailsGenerationLoudly() throws Exception {
        CompiledModel compiled = compileCanonicalDemo();
        Path out = Files.createTempDirectory("npdev-persistence-adapter-invalid-");
        Path migrations = Files.createTempDirectory("npdev-persistence-adapter-invalid-mig-");

        SettingStore store = SettingStore.builder()
                .layer(SettingScope.CONCEPT, "concept:Patient",
                        Map.of(NpdevSettings.PERSISTENCE_ADAPTER.id(), "audit"), "test override")
                .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                        new GeneratedSourceWriter(out, new RegenerationPolicy()),
                        new SettingResolver(store))
                        .generate(compiled, out, migrations, canonicalDemoModel())
        );
        assertTrue(exception.getMessage().contains("audit"), exception.getMessage());
        assertTrue(exception.getMessage().contains("persistence.adapter"), exception.getMessage());
    }

    @Test
    void supportedAuditedOverrideGeneratesCleanly() throws Exception {
        CompiledModel compiled = compileCanonicalDemo();
        Path out = Files.createTempDirectory("npdev-persistence-adapter-valid-");
        Path migrations = Files.createTempDirectory("npdev-persistence-adapter-valid-mig-");

        SettingStore store = SettingStore.builder()
                .layer(SettingScope.CONCEPT, "concept:Patient",
                        Map.of(NpdevSettings.PERSISTENCE_ADAPTER.id(), "audited"), "test override")
                .build();

        new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()),
                new SettingResolver(store))
                .generate(compiled, out, migrations, canonicalDemoModel());

        String generated = Files.readString(out.resolve("src/main/java/com/npdev/generated/services/PatientServiceBase.java"));
        assertTrue(generated.contains("AuditingConceptStoreDecorator"), generated);
    }

    @Test
    void supportedTenantOverrideGeneratesTheLiveSwitchDecoratorCleanly() throws Exception {
        CompiledModel compiled = compileCanonicalDemo();
        Path out = Files.createTempDirectory("npdev-persistence-adapter-tenant-");
        Path migrations = Files.createTempDirectory("npdev-persistence-adapter-tenant-mig-");

        SettingStore store = SettingStore.builder()
                .layer(SettingScope.CONCEPT, "concept:Patient",
                        Map.of(NpdevSettings.PERSISTENCE_ADAPTER.id(), "tenant"), "test override")
                .build();

        new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()),
                new SettingResolver(store))
                .generate(compiled, out, migrations, canonicalDemoModel());

        String generated = Files.readString(out.resolve("src/main/java/com/npdev/generated/services/PatientServiceBase.java"));
        assertTrue(generated.contains("TenantControlledConceptStoreDecorator"), generated);
        assertFalse(generated.contains("new com.finalexec.db.AuditingConceptStoreDecorator(conceptStore"), generated);
    }
}
