package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * REG-336: a reference to a satellite (Customer {@code satelliteOf} Organization) is labelled by the record it extends
 * -- the generated UI manifest carries {@code reference.labelVia}, derived from {@code satelliteOf} with no DSL keyword.
 * A reference to an ordinary concept carries none, and neither does one to a satellite whose base has nothing to show.
 */
class BusinessUiEmitterLabelViaTest {

    private static final String MODEL = """
            {
              "namespace": "label.via.demo",
              "dslVersion": "3.0.0",
              "version": "1.0",
              "concepts": [
                {
                  "name": "Organization",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "name", "type": "string", "required": true }
                  ]
                },
                {
                  "name": "Customer",
                  "satelliteOf": "Organization",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "organizationId", "type": "reference", "required": true, "unique": true,
                      "reference": { "target": "Organization", "displayField": "name" } },
                    { "name": "code", "type": "string", "required": true }
                  ]
                },
                {
                  "name": "Widget",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "name", "type": "string", "required": true }
                  ]
                },
                {
                  "name": "SalesOrder",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "customerId", "type": "reference", "required": true,
                      "reference": { "target": "Customer", "displayField": "code" } },
                    { "name": "widgetId", "type": "reference", "required": true,
                      "reference": { "target": "Widget", "displayField": "name" } }
                  ]
                }
              ]
            }
            """;

    private static JsonNode reference(String fieldName) throws Exception {
        return reference(MODEL, fieldName);
    }

    private static JsonNode reference(String modelJson, String fieldName) throws Exception {
        Path modelPath = Files.createTempFile("npdev-label-via-", ".json");
        Files.writeString(modelPath, modelJson);
        ModelAst ast = new JsonModelParser().parse(modelPath);
        CompiledModel model = new ModelCompiler().compile(ast);
        Path out = Files.createTempDirectory("npdev-label-via-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        String manifest = Files.readString(out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json"));
        for (JsonNode concept : new ObjectMapper().readTree(manifest).get("concepts")) {
            if (!"SalesOrder".equals(concept.get("conceptName").asText())) {
                continue;
            }
            for (JsonNode field : concept.get("fields")) {
                if (fieldName.equals(field.get("name").asText())) {
                    return field.get("reference");
                }
            }
        }
        throw new AssertionError(fieldName + " not found in generated-ui-manifest.json");
    }

    @Test
    void aReferenceToASatelliteNamesTheHopToTheRecordItExtends() throws Exception {
        JsonNode reference = reference("customerId");
        assertEquals("code", reference.get("displayField").asText(), "the satellite's own label stays as the fallback");
        JsonNode hop = reference.get("labelVia");
        assertNotNull(hop, "a satellite reference carries labelVia");
        assertEquals("organizationId", hop.get("field").asText());
        assertEquals("name", hop.get("displayField").asText());
        assertEquals("id", hop.get("targetIdField").asText());
        assertEquals("/api/concepts/organizations", hop.get("endpointBase").asText());
    }

    @Test
    void aReferenceToAnOrdinaryConceptHasNoHop() throws Exception {
        assertNull(reference("widgetId").get("labelVia"));
    }

    @Test
    void aSatelliteWhoseHopNamesNoRealFieldOnTheBaseHasNoHop() throws Exception {
        String model = MODEL.replace(
                "\"reference\": { \"target\": \"Organization\", \"displayField\": \"name\" } },",
                "\"reference\": { \"target\": \"Organization\", \"displayField\": \"noSuchField\" } },");
        assertNull(reference(model, "customerId").get("labelVia"));
    }
}
