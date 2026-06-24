package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code mode} field on a declared event only means something when the event is nested under
 * a concept (it tells generated CRUD which mutation step to publish from) -- a top-level event has
 * no concept for that to bind to. Both directions are tested: an invalid mode value on a
 * concept-nested event is already rejected; a (valid or invalid) mode on a top-level event is
 * rejected outright, since the field is meaningless there rather than just unused.
 */
class EventModeValidationTest {

    private static Path writeModel(String json) throws IOException {
        Path modelPath = Files.createTempFile("npdev-event-mode-", ".json");
        Files.writeString(modelPath, json);
        return modelPath;
    }

    @Test
    void conceptNestedEventRejectsInvalidMode() throws Exception {
        Path modelPath = writeModel("""
                {
                  "namespace": "event.mode.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Order",
                      "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ],
                      "events": [
                        { "name": "OrderTouched", "mode": "bogus", "payload": ["id"] }
                      ]
                    }
                  ]
                }
                """);

        // The schema's own "mode" enum (create|update|delete) rejects "bogus" before the parser's
        // AST-level check ever runs -- both layers agree the value is invalid, just via different
        // (locale-dependent, in the schema validator's case) messages. Assert on the path, not the
        // wording, so this doesn't depend on which layer wins the race.
        IOException exception = assertThrows(IOException.class, () -> new JsonModelParser().parse(modelPath));
        assertTrue(exception.getMessage().contains("mode"), exception.getMessage());
    }

    @Test
    void conceptNestedEventAcceptsValidMode() throws Exception {
        Path modelPath = writeModel("""
                {
                  "namespace": "event.mode.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Order",
                      "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ],
                      "events": [
                        { "name": "OrderCreated", "mode": "create", "payload": ["id"] }
                      ]
                    }
                  ]
                }
                """);

        ModelAst parsed = new JsonModelParser().parse(modelPath);
        assertTrue(parsed.getConcepts().get(0).getEvents().get(0).getTriggerMode().equalsIgnoreCase("create"));
    }

    @Test
    void topLevelEventRejectsMode() throws Exception {
        Path modelPath = writeModel("""
                {
                  "namespace": "event.mode.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Order",
                      "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ]
                    }
                  ],
                  "events": [
                    { "name": "SystemHeartbeat", "mode": "create", "payload": ["id"] }
                  ]
                }
                """);

        IOException exception = assertThrows(IOException.class, () -> new JsonModelParser().parse(modelPath));
        assertTrue(exception.getMessage().contains("mode"), exception.getMessage());
    }

    @Test
    void topLevelEventWithoutModeParsesFine() throws Exception {
        Path modelPath = writeModel("""
                {
                  "namespace": "event.mode.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Order",
                      "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ]
                    }
                  ],
                  "events": [
                    { "name": "SystemHeartbeat", "payload": ["id"] }
                  ]
                }
                """);

        ModelAst parsed = new JsonModelParser().parse(modelPath);
        assertTrue(parsed.getEvents().stream().anyMatch(e -> "SystemHeartbeat".equals(e.getName())));
    }
}
