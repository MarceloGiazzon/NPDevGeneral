package com.npdev.generator.emitters;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingScope;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A single (N:1/1:1) reference field's widget cascade supports exactly two overrides today --
 * "select" (a plain, whole-set-fetched-upfront &lt;select&gt;) and "autocomplete" (live-search
 * suggestions, for a candidate set too large for "select") -- with any other value or no override
 * at all falling back to the default picker ("lookup"). Proves all three paths against a real
 * reference field, not just the non-reference field:Concept.field cases
 * BusinessUiEmitterFieldWidgetCascadeTest already covers.
 */
public class BusinessUiEmitterReferenceWidgetOverrideTest {

    private static Path writeModel() throws IOException {
        Path modelPath = Files.createTempFile("npdev-reference-widget-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "reference.widget.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Person",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "name", "type": "string", "required": true }
                      ]
                    },
                    {
                      "name": "Order",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "personRef",
                          "type": "reference",
                          "required": true,
                          "reference": { "target": "Person", "displayField": "name" }
                        }
                      ]
                    }
                  ]
                }
                """);
        return modelPath;
    }

    private static CompiledModel compile() throws Exception {
        ModelAst ast = new JsonModelParser().parse(writeModel());
        return new ModelCompiler().compile(ast);
    }

    private static String emitAndReadManifest(SettingStore store) throws Exception {
        CompiledModel model = compile();
        Path out = Files.createTempDirectory("npdev-reference-widget-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(store));
        return Files.readString(out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json"));
    }

    private static String personRefWidget(String manifest) {
        String marker = "\"name\" : \"personRef\"";
        int fieldStart = manifest.indexOf(marker);
        int widgetKey = manifest.indexOf("\"widget\"", fieldStart);
        int valueStart = manifest.indexOf('"', manifest.indexOf(':', widgetKey) + 1) + 1;
        int valueEnd = manifest.indexOf('"', valueStart);
        return manifest.substring(valueStart, valueEnd);
    }

    @Test
    void unconfiguredReferenceFieldDefaultsToLookup() throws Exception {
        assertEquals("lookup", personRefWidget(emitAndReadManifest(SettingStore.empty())));
    }

    @Test
    void selectOverrideAppliesToReferenceField() throws Exception {
        SettingStore store = SettingStore.builder()
                .layer(SettingScope.FIELD, "field:Order.personRef", Map.of("field.widget", "select"), "test override")
                .build();
        assertEquals("select", personRefWidget(emitAndReadManifest(store)));
    }

    @Test
    void autocompleteOverrideAppliesToReferenceField() throws Exception {
        SettingStore store = SettingStore.builder()
                .layer(SettingScope.FIELD, "field:Order.personRef", Map.of("field.widget", "autocomplete"), "test override")
                .build();
        assertEquals("autocomplete", personRefWidget(emitAndReadManifest(store)));
    }

    @Test
    void unsupportedOverrideFallsBackToLookupInsteadOfABareTextInput() throws Exception {
        SettingStore store = SettingStore.builder()
                .layer(SettingScope.FIELD, "field:Order.personRef", Map.of("field.widget", "textarea"), "test override")
                .build();
        assertEquals("lookup", personRefWidget(emitAndReadManifest(store)));
    }
}
