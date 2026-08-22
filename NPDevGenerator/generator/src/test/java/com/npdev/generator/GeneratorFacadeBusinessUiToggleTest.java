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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the resolution pipeline is actually wired: the {@code ui.generateBusinessUi} setting
 * gates whether the business UI is emitted, while the rest of generation is unaffected.
 */
class GeneratorFacadeBusinessUiToggleTest {

    private static final String BUSINESS_UI_MARKER = "src/main/resources/static/npdev-business-ui/app.js";

    private static Path canonicalDemoModel() {
        return Path.of("..", "resources", "Models", "canonical-demo", "model.json").normalize();
    }

    private static CompiledModel compileCanonicalDemo() throws Exception {
        ModelAst ast = new JsonModelParser().parse(canonicalDemoModel());
        return new ModelCompiler().compile(ast);
    }

    @Test
    void businessUiEmittedByDefault() throws Exception {
        CompiledModel compiled = compileCanonicalDemo();
        Path out = Files.createTempDirectory("npdev-businessui-default-");
        Path migrations = Files.createTempDirectory("npdev-businessui-default-mig-");

        new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .generate(compiled, out, migrations, canonicalDemoModel());

        assertTrue(Files.exists(out.resolve(BUSINESS_UI_MARKER)),
                "Default resolver should emit the business UI: " + BUSINESS_UI_MARKER);
        assertTrue(Files.exists(out.resolve("src/main/resources/npdev/resolved-settings.json")),
                "The resolved-settings provenance manifest should always be emitted.");
    }

    @Test
    void businessUiSuppressedWhenSettingIsFalse() throws Exception {
        CompiledModel compiled = compileCanonicalDemo();
        Path out = Files.createTempDirectory("npdev-businessui-off-");
        Path migrations = Files.createTempDirectory("npdev-businessui-off-mig-");

        SettingStore store = SettingStore.builder()
                .layer(SettingScope.APP, SettingTarget.APP_SELECTOR,
                        Map.of(NpdevSettings.UI_GENERATE_BUSINESS_UI.id(), false), "test")
                .build();

        new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()),
                new SettingResolver(store))
                .generate(compiled, out, migrations, canonicalDemoModel());

        assertFalse(Files.exists(out.resolve(BUSINESS_UI_MARKER)),
                "ui.generateBusinessUi=false should suppress the business UI: " + BUSINESS_UI_MARKER);
    }
}
