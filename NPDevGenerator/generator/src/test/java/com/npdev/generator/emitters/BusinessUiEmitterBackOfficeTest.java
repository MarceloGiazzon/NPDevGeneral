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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FORM P0-T7 back-office quick wins: the manifest carries the facts the UI needs for a column budget, a singular
 * label and an honest empty list, and GET /api/me/permissions exists so the menu can hide what a role can never open.
 */
public class BusinessUiEmitterBackOfficeTest {

    private static Path out;
    private static JsonNode manifest;

    @BeforeAll
    static void emit() throws Exception {
        Path modelPath = Files.createTempFile("npdev-backoffice-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "backoffice.demo",
                  "dslVersion": "3.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Ride",
                      "access": { "read": "riderId == $user.id" },
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "riderId", "type": "string" },
                        { "name": "driverId", "type": "string" },
                        { "name": "status", "type": "string" },
                        { "name": "pickup", "type": "string" },
                        { "name": "dropoff", "type": "string" },
                        { "name": "fare", "type": "decimal" },
                        { "name": "currency", "type": "string" },
                        { "name": "notes", "type": "string" }
                      ]
                    },
                    {
                      "name": "Vehicle",
                      "ui": { "label": "Fleet" },
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "plate", "type": "string" }
                      ]
                    }
                  ]
                }
                """);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        out = Files.createTempDirectory("npdev-backoffice-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        manifest = new ObjectMapper().readTree(Files.readString(
                out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json")));
    }

    private static JsonNode concept(String name) {
        for (JsonNode concept : manifest.get("concepts")) {
            if (name.equals(concept.get("conceptName").asText())) {
                return concept;
            }
        }
        throw new AssertionError("no concept " + name);
    }

    @Test
    void aConceptWithMoreFieldsThanTheBudgetOpensWithSevenColumnsAndKeepsAllOfThemAvailable() {
        JsonNode list = concept("Ride").get("list");
        assertEquals(9, list.get("columns").size(), "every field stays available to the Columns toggle");
        assertEquals(BusinessUiEmitter.DEFAULT_COLUMN_BUDGET, list.get("defaultColumns").size());
        assertEquals("id", list.get("defaultColumns").get(0).asText());
    }

    @Test
    void aSmallConceptHasNoDefaultColumnsKey() {
        assertFalse(concept("Vehicle").get("list").has("defaultColumns"));
    }

    @Test
    void singularNameIsDerivedOnlyWhenTheAuthorDidNotWriteALabel() {
        assertEquals("Ride", concept("Ride").get("singularName").asText());
        assertEquals("Rides", concept("Ride").get("displayName").asText());
        assertFalse(concept("Vehicle").has("singularName"), "an author-written ui.label is used exactly as written");
        assertEquals("Fleet", concept("Vehicle").get("displayName").asText());
    }

    @Test
    void rowScopedIsSetOnlyForAConceptWithAnAccessReadRule() {
        assertTrue(concept("Ride").get("rowScoped").asBoolean());
        assertFalse(concept("Vehicle").has("rowScoped"));
    }

    @Test
    void theMeControllerAnswersPermissionsFromTheSameCheckTheServicesEnforce() throws Exception {
        String controller = Files.readString(out.resolve("src/main/java/com/npdev/generated/controllers/GeneratedMeController.java"));
        assertTrue(controller.contains("@GetMapping(\"/api/me/permissions\")"));
        assertTrue(controller.contains("concepts.put(\"Ride\", permissionsOf(\"Ride\", context));"));
        assertTrue(controller.contains("concepts.put(\"Vehicle\", permissionsOf(\"Vehicle\", context));"));
        assertTrue(controller.contains("runtimeSupport.isCrudPermitted(conceptName, \"READ\", context)"));
    }

    @Test
    void theUiHidesWhatTheRoleCannotOpenAndSaysWhyAListIsEmpty() throws Exception {
        String js = CarvedUiModules.appJsWithModules(out);
        assertTrue(js.contains("/api/me/permissions"));
        assertTrue(js.contains("&& conceptAllows(c, \"list\")"), "navigation drops concepts the role cannot list");
        assertTrue(js.contains("conceptAllows(concept, \"create\")"), "no Create button for a role that cannot create");
        assertTrue(js.contains("\"Create \" + singularLabel(concept)"));
        assertFalse(js.contains("\"Create \" + concept.displayName"));
        assertTrue(js.contains("only sees the ones that belong to it"));
        assertTrue(js.contains("function defaultHiddenColumns(concept)"));
    }
}
