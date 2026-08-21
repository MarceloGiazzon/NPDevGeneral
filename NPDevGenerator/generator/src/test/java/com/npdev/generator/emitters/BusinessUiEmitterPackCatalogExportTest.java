package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PACK-14: the generated {@code GeneratedPackCatalogController.exportConceptToPack} must delegate to
 * the shared {@code PackExportReferenceClassifier} instead of copying a concept verbatim -- a pack
 * exported with a dangling reference fails loudly at composition with no context. Asserted against the
 * emitted controller, not the template, since that is what ships into every generated app.
 */
public class BusinessUiEmitterPackCatalogExportTest {

    private static String emitControllerJava() throws Exception {
        Path modelPath = Files.createTempFile("npdev-pack-export-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "packexport.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Customer",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        Path out = Files.createTempDirectory("npdev-pack-export-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        Path controller = out.resolve("src/main/java/com/npdev/generated/controllers/GeneratedPackCatalogController.java");
        return Files.readString(controller);
    }

    @Test
    void exportConceptToPackDelegatesToTheSharedReferenceClassifier() throws Exception {
        String java = emitControllerJava();

        assertTrue(java.contains("import com.npdev.dsl.v1.pack.PackExportReferenceClassifier;"),
                "the controller must import the shared classifier");
        assertTrue(java.contains("PackExportReferenceClassifier.classify("),
                "the controller must delegate to the shared classifier, not copy the concept verbatim");
        assertTrue(java.contains("allowUnresolvedRefs"),
                "the controller must support the opt-in for recording unresolved references");
        assertTrue(java.contains("unresolvedReferences"),
                "unresolved references must be recorded, never dropped silently");
    }
}
