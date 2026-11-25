package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-360 (f): the generated create/edit form offered the platform's own fields as inputs -- a rollups[]
 * target (a count the platform keeps) and a derivedExpression field (recomputed on every write) -- because
 * the manifest marked only the id read-only. The browser filters form fields by that flag (formFields(),
 * which the CSV import and the bulk edit share), so the flag is the whole contract: a field the platform
 * keeps must be {@code readOnly}, an ordinary field must not be, and the id still is.
 */
public class BusinessUiEmitterSystemKeptFieldsTest {

    private static final String MODEL = """
            {
              "namespace": "systemkept.demo",
              "dslVersion": "3.0.0",
              "version": "1.0",
              "concepts": [
                {
                  "name": "Rider",
                  "rollups": [ { "field": "ridesRequested", "from": "Ride", "via": "rider", "fn": "count" } ],
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "name", "type": "string", "required": true },
                    { "name": "ridesRequested", "type": "integer" },
                    { "name": "bonus", "type": "decimal", "derivedExpression": "ridesRequested * 2" }
                  ]
                },
                {
                  "name": "Ride",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "rider", "type": "reference", "reference": { "target": "Rider" } }
                  ]
                }
              ]
            }
            """;

    private static JsonNode emittedFields(String conceptName) throws Exception {
        Path modelPath = Files.createTempFile("npdev-systemkept-", ".json");
        Files.writeString(modelPath, MODEL);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        Path out = Files.createTempDirectory("npdev-systemkept-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        JsonNode manifest = new ObjectMapper().readTree(Files.readString(
                out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json")));
        for (JsonNode concept : manifest.get("concepts")) {
            if (conceptName.equals(concept.get("conceptName").asText())) {
                return concept.get("fields");
            }
        }
        throw new AssertionError("concept " + conceptName + " is not in the manifest");
    }

    private static boolean readOnly(JsonNode fields, String name) {
        for (JsonNode field : fields) {
            if (name.equals(field.get("name").asText())) {
                assertNotNull(field.get("readOnly"), "field " + name + " has no readOnly flag");
                return field.get("readOnly").asBoolean();
            }
        }
        throw new AssertionError("field " + name + " is not in the manifest");
    }

    @Test
    void aRollupTargetAndADerivedFieldAreReadOnlyInTheForm() throws Exception {
        JsonNode rider = emittedFields("Rider");
        assertTrue(readOnly(rider, "ridesRequested"), "a rollups[] target is kept by the platform, not typed");
        assertTrue(readOnly(rider, "bonus"), "a derivedExpression field is recomputed on every write, not typed");
    }

    @Test
    void anOrdinaryFieldStaysEditableAndTheIdStaysReadOnly() throws Exception {
        JsonNode rider = emittedFields("Rider");
        assertFalse(readOnly(rider, "name"), "an ordinary field must stay an input");
        assertTrue(readOnly(rider, "id"), "the id has always been read-only");
        assertFalse(readOnly(emittedFields("Ride"), "rider"),
                "a concept with no rollups and no derived field keeps every non-id field editable");
    }
}
