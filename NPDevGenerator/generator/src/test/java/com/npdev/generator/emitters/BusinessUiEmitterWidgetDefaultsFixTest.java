package com.npdev.generator.emitters;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two pre-existing defaulting bugs fixed alongside the widget/datatype compatibility work: (1)
 * "int"/"long" fields fell through to the generic "text" widget instead of "number" (only the
 * literal type "integer" got numeric treatment); (2) a reference field's own inline model.json
 * ui.widget was always ignored -- BusinessUiEmitter.widget() unconditionally forced "lookup"
 * unless the SEPARATE field.widget cascade setting was used instead.
 */
public class BusinessUiEmitterWidgetDefaultsFixTest {

    private static Path writeModel() throws IOException {
        Path modelPath = Files.createTempFile("npdev-widget-defaults-fix-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "widget.defaults.fix.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Country",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "name", "type": "string", "required": true },
                        { "name": "flagUrl", "type": "string" }
                      ]
                    },
                    {
                      "name": "Product",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "quantity", "type": "int" },
                        { "name": "warehouseCode", "type": "long" },
                        {
                          "name": "originRef",
                          "type": "reference",
                          "reference": { "target": "Country", "displayField": "name" },
                          "ui": { "label": "Origin", "widget": "select", "imageField": "flagUrl" }
                        }
                      ]
                    }
                  ]
                }
                """);
        return modelPath;
    }

    private static String emitAndReadManifest() throws Exception {
        ModelAst ast = new JsonModelParser().parse(writeModel());
        CompiledModel model = new ModelCompiler().compile(ast);
        Path out = Files.createTempDirectory("npdev-widget-defaults-fix-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return Files.readString(out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json"));
    }

    private static String fieldWidget(String manifest, String fieldName) {
        String marker = "\"name\" : \"" + fieldName + "\"";
        int fieldStart = manifest.indexOf(marker);
        assertTrue(fieldStart >= 0, "field " + fieldName + " not found in manifest:\n" + manifest);
        int widgetKey = manifest.indexOf("\"widget\"", fieldStart);
        int valueStart = manifest.indexOf('"', manifest.indexOf(':', widgetKey) + 1) + 1;
        int valueEnd = manifest.indexOf('"', valueStart);
        return manifest.substring(valueStart, valueEnd);
    }

    @Test
    void intFieldWithNoDeclaredWidgetDefaultsToNumber() throws Exception {
        assertEquals("number", fieldWidget(emitAndReadManifest(), "quantity"));
    }

    @Test
    void longFieldWithNoDeclaredWidgetDefaultsToNumber() throws Exception {
        assertEquals("number", fieldWidget(emitAndReadManifest(), "warehouseCode"));
    }

    @Test
    void referenceFieldHonorsItsOwnInlineUiWidgetWithoutACascadeOverride() throws Exception {
        assertEquals("select", fieldWidget(emitAndReadManifest(), "originRef"));
    }

    @Test
    void referenceFieldEmitsItsDeclaredImageField() throws Exception {
        String manifest = emitAndReadManifest();
        int fieldStart = manifest.indexOf("\"name\" : \"originRef\"");
        int imageFieldKey = manifest.indexOf("\"imageField\"", fieldStart);
        assertTrue(imageFieldKey >= 0, "expected imageField in the originRef reference metadata:\n" + manifest);
        int valueStart = manifest.indexOf('"', manifest.indexOf(':', imageFieldKey) + 1) + 1;
        int valueEnd = manifest.indexOf('"', valueStart);
        assertEquals("flagUrl", manifest.substring(valueStart, valueEnd));
    }
}
