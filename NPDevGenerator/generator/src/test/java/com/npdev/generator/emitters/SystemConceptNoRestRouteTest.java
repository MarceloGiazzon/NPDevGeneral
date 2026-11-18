package com.npdev.generator.emitters;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * REG-339: a {@code system:true} concept (the identity pack's Credential holds password hashes) must not get a
 * typed REST controller. The generic CRUD, menu, export and agent surfaces were already closed; the typed
 * {@code /api/<route>} controller was the one left open, readable by any signed-in user.
 */
class SystemConceptNoRestRouteTest {

    @TempDir
    Path tempDir;

    @Test
    void aSystemConceptGetsNoTypedControllerWhileAnOrdinaryOneDoes() throws Exception {
        Path modelPath = tempDir.resolve("model.json");
        Files.writeString(modelPath, """
                {
                  "namespace": "system.concept",
                  "dslVersion": "3.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Vault", "system": true, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "secretHash", "type": "string" } ] },
                    { "name": "Widget", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "label", "type": "string" } ] }
                  ]
                }
                """);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        Path out = tempDir.resolve("out");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(out, new RegenerationPolicy());

        new ControllerEmitter(new TemplateEngine("npdev-templates/"), writer).emit(model);

        Path controllers = out.resolve("src/main/java/com/npdev/generated/controllers");
        assertTrue(Files.exists(controllers.resolve("WidgetController.java")), "an ordinary concept keeps its controller");
        assertTrue(Files.exists(controllers.resolve("WidgetControllerBase.java")));
        assertFalse(Files.exists(controllers.resolve("VaultController.java")), "a system concept gets no controller");
        assertFalse(Files.exists(controllers.resolve("VaultControllerBase.java")), "nor a controller base");
    }
}
