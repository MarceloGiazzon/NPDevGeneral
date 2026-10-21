package com.npdev.generator.emitters;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * REG-270: a concept ending in "y" was served at {@code /ticket_categorys}. The REST collection name now
 * takes its plural from the shared English rule, while the physical table keeps its name.
 */
class RestCollectionNameEmitterTest {

    @TempDir
    Path tempDir;

    private CompiledModel model() throws Exception {
        Path modelPath = tempDir.resolve("model.json");
        Files.writeString(modelPath, """
                {
                  "namespace": "rest.plural",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "TicketCategory", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "name", "type": "string", "required": true } ] },
                    { "name": "Box", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "label", "type": "string" } ] },
                    { "name": "Widget", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "label", "type": "string" } ] }
                  ]
                }
                """);
        return new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
    }

    @Test
    void controllersAndTheGenericBindingMapServeTheCorrectPlural() throws Exception {
        CompiledModel model = model();
        Path out = tempDir.resolve("out");
        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(out, new RegenerationPolicy());

        new ControllerEmitter(templates, writer).emit(model);
        new BusinessUiEmitter(templates, writer).emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));

        Path controllers = out.resolve("src/main/java/com/npdev/generated/controllers");
        String category = Files.readString(controllers.resolve("TicketCategoryControllerBase.java"));
        String box = Files.readString(controllers.resolve("BoxControllerBase.java"));
        String widget = Files.readString(controllers.resolve("WidgetControllerBase.java"));
        assertTrue(category.contains("/api/ticket_categories/"), category);
        assertFalse(category.contains("ticket_categorys"), category);
        assertTrue(box.contains("/api/boxes/"), box);
        assertTrue(widget.contains("/api/widgets/"), widget);

        String generic = Files.readString(controllers.resolve("GeneratedConceptCrudController.java"));
        assertTrue(generic.contains("bindings.put(\"ticket_categories\""), generic);
        assertTrue(generic.contains("bindings.put(\"boxes\""), generic);
        assertTrue(generic.contains("bindings.put(\"widgets\""), generic);
        assertFalse(generic.contains("bindings.put(\"ticket_categorys\""), "no route may keep the old plural");
        // The metadata still names the real physical table, which this fix deliberately leaves alone.
        assertTrue(generic.contains("\"ticket_categorys\""), generic);
        // The plural display label uses the same rule.
        assertTrue(generic.contains("TicketCategories"), generic);
    }
}
