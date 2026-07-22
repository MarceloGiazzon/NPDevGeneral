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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * field.widget is a registered cascading SettingKey, but BusinessUiEmitter.widget() previously
 * never consulted it -- only the field's direct model.json ui.widget attribute. This proves the
 * fix: an unconfigured app's manifest is unchanged (regression safety), and a real
 * field:Concept.field override actually changes what's emitted.
 */
public class BusinessUiEmitterFieldWidgetCascadeTest {

    @Test
    void unconfiguredFieldKeepsTheExistingTypeBasedDefault() throws Exception {
        CompiledModel model = loadUserMinimal();
        Path out = Files.createTempDirectory("npdev-widget-default-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));

        String manifest = readManifest(out);
        assertTrue(manifest.contains("\"name\" : \"email\""), "expected the email field in the manifest:\n" + manifest);
        // No ui.widget is declared on User.email and no override exists -- default stays "text".
        assertTrue(emailWidget(manifest).equals("text"), "expected the unconfigured default \"text\", got: " + emailWidget(manifest));
    }

    @Test
    void fieldScopedOverrideWinsOverTheTypeBasedDefault() throws Exception {
        CompiledModel model = loadUserMinimal();
        Path out = Files.createTempDirectory("npdev-widget-override-");
        SettingStore store = SettingStore.builder()
                .layer(SettingScope.FIELD, "field:User.email", Map.of("field.widget", "tel"), "test override")
                .build();

        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(store));

        String manifest = readManifest(out);
        assertTrue(emailWidget(manifest).equals("tel"), "expected the field-scoped override \"tel\" to win, got: " + emailWidget(manifest) + "\n" + manifest);
    }

    @Test
    void overrideAtOneFieldDoesNotLeakToASiblingField() throws Exception {
        CompiledModel model = loadUserMinimal();
        Path out = Files.createTempDirectory("npdev-widget-sibling-");
        SettingStore store = SettingStore.builder()
                .layer(SettingScope.FIELD, "field:User.email", Map.of("field.widget", "tel"), "test override")
                .build();

        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(store));

        String manifest = readManifest(out);
        assertFalse(fieldWidget(manifest, "name").equals("tel"), "the override on email must not leak to the sibling field name:\n" + manifest);
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

    private static String emailWidget(String manifest) {
        return fieldWidget(manifest, "email");
    }

    /** Cheap, dependency-free extraction: find the field object by name, then its widget value. */
    private static String fieldWidget(String manifest, String fieldName) {
        String marker = "\"name\" : \"" + fieldName + "\"";
        int fieldStart = manifest.indexOf(marker);
        assertTrue(fieldStart >= 0, "field \"" + fieldName + "\" not found in manifest:\n" + manifest);
        int widgetKey = manifest.indexOf("\"widget\"", fieldStart);
        assertTrue(widgetKey >= 0, "widget key not found after field \"" + fieldName + "\":\n" + manifest);
        int valueStart = manifest.indexOf('"', manifest.indexOf(':', widgetKey) + 1) + 1;
        int valueEnd = manifest.indexOf('"', valueStart);
        return manifest.substring(valueStart, valueEnd);
    }
}
