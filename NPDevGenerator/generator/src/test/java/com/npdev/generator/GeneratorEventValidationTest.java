package com.npdev.generator;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratorEventValidationTest {

    @Test
    void generationSucceedsWhenMutationEventsAreDeclared() throws Exception {
        CompiledModel model = compile("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "name", "type": "string", "required": true },
                        { "name": "email", "type": "string", "required": true }
                      ],
                      "invariants": [
                        { "type": "unique", "fields": ["email"] }
                      ]
                    }
                  ],
                  "events": [
                    { "name": "UserCreated" },
                    { "name": "UserUpdated" },
                    { "name": "UserDeleted" }
                  ]
                }
                """);

        Path out = Files.createTempDirectory("npdev-events-ok-");
        Path migrations = Files.createTempDirectory("npdev-events-mig-");
        GeneratorFacade facade = new GeneratorFacade(
                new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy())
        );
        facade.generate(model, out, migrations);

        Path service = out.resolve("src/main/java/com/npdev/generated/services/UserServiceBase.java");
        String serviceContent = Files.readString(service);
        assertTrue(serviceContent.contains("ALLOWED_MUTATION_TOPICS"));
        assertTrue(serviceContent.contains("Undeclared mutation event topic"));
    }

    @Test
    void declaringOneCrudEventStillEmitsAllThreeDefaults() throws Exception {
        CompiledModel model = compile("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "name", "type": "string", "required": true }
                      ]
                    }
                  ],
                  "events": [
                    { "name": "UserCreated" }
                  ]
                }
                """);

        Path out = Files.createTempDirectory("npdev-events-subset-");
        Path migrations = Files.createTempDirectory("npdev-events-mig-subset-");
        GeneratorFacade facade = new GeneratorFacade(
                new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy())
        );
        facade.generate(model, out, migrations);

        Path service = out.resolve("src/main/java/com/npdev/generated/services/UserServiceBase.java");
        String serviceContent = Files.readString(service);
        // Declared events add to the defaults, never subtract (before 2026-10-31 this was an
        // allow-list and only "created" survived).
        assertAllThreeDefaultsEmitted(serviceContent);
        // UserCreated is the default User.created topic, not a second one.
        assertEquals(1, occurrences(serviceContent, "topics.add(\"User.created\")"));
    }

    @Test
    void declaringAnUnrelatedEventKeepsEveryDefaultCrudEvent() throws Exception {
        String serviceContent = generateService("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "WorkOrder",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "title", "type": "string", "required": true }
                      ]
                    }
                  ],
                  "events": [
                    { "name": "WorkOrderStarted", "payload": [ { "name": "id", "type": "uuid" } ] }
                  ]
                }
                """, "WorkOrder");
        assertAllThreeDefaultsEmitted(serviceContent);
        assertTrue(serviceContent.contains("topics.add(\"WorkOrder.created\")"));
        assertTrue(serviceContent.contains("topics.add(\"WorkOrder.updated\")"));
        assertTrue(serviceContent.contains("topics.add(\"WorkOrder.deleted\")"));
    }

    @Test
    void aConceptEventNamedLikeTheDefaultTopicIsPublishedOnceNotTwice() throws Exception {
        String serviceContent = generateService("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "WorkOrder",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "title", "type": "string", "required": true }
                      ],
                      "events": [
                        { "name": "WorkOrder.created", "mode": "create" },
                        { "name": "WorkOrderOpened", "mode": "create" }
                      ]
                    }
                  ]
                }
                """, "WorkOrder");
        assertAllThreeDefaultsEmitted(serviceContent);
        assertEquals(0, occurrences(serviceContent, "publishDeclaredEvent(\"WorkOrder.created\""));
        assertEquals(1, occurrences(serviceContent, "publishDeclaredEvent(\"WorkOrderOpened\""));
    }

    @Test
    void noDeclaredEventsEmitsAllThreeDefaults() throws Exception {
        String serviceContent = generateService("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "WorkOrder",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "title", "type": "string", "required": true }
                      ]
                    }
                  ]
                }
                """, "WorkOrder");
        assertAllThreeDefaultsEmitted(serviceContent);
    }

    private static void assertAllThreeDefaultsEmitted(String serviceContent) {
        assertEquals(1, occurrences(serviceContent, "publishMutationEvent(\"created\""));
        assertEquals(1, occurrences(serviceContent, "publishMutationEvent(\"updated\""));
        assertEquals(1, occurrences(serviceContent, "publishMutationEvent(\"deleted\""));
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static String generateService(String json, String concept) throws Exception {
        CompiledModel model = compile(json);
        Path out = Files.createTempDirectory("npdev-events-gen-");
        Path migrations = Files.createTempDirectory("npdev-events-gen-mig-");
        new GeneratorFacade(
                new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy())
        ).generate(model, out, migrations);
        return Files.readString(out.resolve("src/main/java/com/npdev/generated/services/" + concept + "ServiceBase.java"));
    }

    @Test
    void generationFailsForUnsupportedBindingAdapter() throws Exception {
        CompiledModel model = compile("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "name", "type": "string", "required": true }
                      ]
                    }
                  ],
                  "bindings": [
                    { "capability": "eventBus", "adapter": "kafka" }
                  ]
                }
                """);

        Path out = Files.createTempDirectory("npdev-binding-fail-");
        Path migrations = Files.createTempDirectory("npdev-binding-mig-fail-");
        GeneratorFacade facade = new GeneratorFacade(
                new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy())
        );

        RuntimeException ex = assertThrows(RuntimeException.class, () -> facade.generate(model, out, migrations));
        assertTrue(ex.getMessage().contains("unsupported eventBus adapter binding"));
    }

    private static CompiledModel compile(String json) throws Exception {
        Path modelPath = Files.createTempFile("npdev-events-model-", ".json");
        Files.writeString(modelPath, json, StandardCharsets.UTF_8);

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no semantic errors, got: " + errors);

        return new ModelCompiler().compile(ast);
    }
}

