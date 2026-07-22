package com.npdev.generator.emitters;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingScope;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ui.frame.mode is a registered concept-scope SettingKey controlling whether a section renders
 * inside the normal header+sidenav shell ("full", the only behavior before this), a chromeless
 * variant ("minimal"), or fully raw ("none"). Proves: an unconfigured concept stays "full"
 * (regression safety), a concept:Name override changes only that concept's emitted frameMode,
 * and an unrecognized value falls back to the platform default rather than emitting garbage.
 */
public class BusinessUiEmitterFrameModeCascadeTest {

    @Test
    void unconfiguredConceptDefaultsToFullFrame() throws Exception {
        CompiledModel model = loadUserMinimal();
        Path out = Files.createTempDirectory("npdev-frame-default-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));

        assertEquals("full", conceptFrameMode(readManifest(out), "User"));
    }

    @Test
    void conceptScopedOverrideChangesOnlyThatConceptsFrameMode() throws Exception {
        CompiledModel model = loadUserMinimal();
        Path out = Files.createTempDirectory("npdev-frame-override-");
        SettingStore store = SettingStore.builder()
                .layer(SettingScope.CONCEPT, "concept:User", Map.of("ui.frame.mode", "minimal"), "test override")
                .build();

        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(store));

        assertEquals("minimal", conceptFrameMode(readManifest(out), "User"));
    }

    @Test
    void unrecognizedFrameModeValueFallsBackToPlatformDefault() throws Exception {
        CompiledModel model = loadUserMinimal();
        Path out = Files.createTempDirectory("npdev-frame-invalid-");
        SettingStore store = SettingStore.builder()
                .layer(SettingScope.CONCEPT, "concept:User", Map.of("ui.frame.mode", "not-a-real-mode"), "test override")
                .build();

        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(store));

        assertEquals("full", conceptFrameMode(readManifest(out), "User"));
    }

    private static CompiledModel loadUserMinimal() throws Exception {
        Path model = Path.of("..", "test-models", "user-minimal", "model.json").normalize();
        assertTrue(Files.exists(model), "Expected test model at: " + model.toAbsolutePath());
        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(model);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: " + errors);
        return new ModelCompiler().compile(ast);
    }

    private static String readManifest(Path out) throws Exception {
        return Files.readString(out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json"));
    }

    /** Cheap, dependency-free extraction: find the concept node by name, then its frameMode value. */
    private static String conceptFrameMode(String manifest, String conceptName) {
        String marker = "\"conceptName\" : \"" + conceptName + "\"";
        int conceptStart = manifest.indexOf(marker);
        assertTrue(conceptStart >= 0, "concept \"" + conceptName + "\" not found in manifest:\n" + manifest);
        int frameKey = manifest.indexOf("\"frameMode\"", conceptStart);
        assertTrue(frameKey >= 0, "frameMode key not found after concept \"" + conceptName + "\":\n" + manifest);
        int valueStart = manifest.indexOf('"', manifest.indexOf(':', frameKey) + 1) + 1;
        int valueEnd = manifest.indexOf('"', valueStart);
        return manifest.substring(valueStart, valueEnd);
    }
}
