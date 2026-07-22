package com.npdev.generator.emitters;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * buildItemsSchemaNode() used to flatten any property that was itself an object/array to a bare
 * {"type": "object"} with no further detail -- so the renderer could only ever edit one level of
 * nesting. This proves the recursive fix: Order.shipping (1) -> address (2) -> geo (3) all emit
 * their own nested objectSchema in the manifest, not just the first level.
 */
public class BusinessUiEmitterNestedObjectSchemaTest {

    @Test
    void emitsObjectSchemaThreeLevelsDeep() throws Exception {
        Path modelPath = Path.of("..", "test-models", "nested-schema-demo", "model.json").normalize();
        assertTrue(Files.exists(modelPath), "Expected test model at: " + modelPath.toAbsolutePath());

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: " + errors);
        CompiledModel model = new ModelCompiler().compile(ast);

        Path out = Files.createTempDirectory("npdev-nested-schema-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model);

        String manifest = Files.readString(out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json"));

        // Level 1: the "shipping" field itself has an objectSchema.
        assertTrue(manifest.contains("\"name\" : \"shipping\""), "expected the shipping field:\n" + manifest);
        // Level 2: "address" (a property of shipping) must carry its OWN nested objectSchema,
        // not just {"type": "object"} -- this is exactly what was broken before the fix.
        assertTrue(
                manifest.contains("\"address\"") && manifest.contains("\"street\""),
                "expected address's own nested properties (street, geo) to be present, not flattened:\n" + manifest
        );
        // Level 3: "geo" (a property of address) must carry its OWN nested objectSchema with
        // leaf properties lat/lng -- proving recursion went a full 3 levels deep, not just 2.
        assertTrue(
                manifest.contains("\"geo\"") && manifest.contains("\"lat\"") && manifest.contains("\"lng\""),
                "expected geo's own nested leaf properties (lat, lng) 3 levels deep:\n" + manifest
        );
    }
}
