package com.npdev.adapters.externalai.inproc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.ports.ExternalAiStructuredRequest;
import com.npdev.kernel.ports.ExternalAiStructuredResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P4 (G3): the offline adapter answers a structured prompt with a deterministic schema instance. */
class InProcExternalAiStructuredTest {

    @TempDir
    Path packDir;

    @Test
    void answersWithADeterministicInstanceOfTheSchemaAndNoTokens() throws Exception {
        String schema = """
                { "type": "object", "required": ["cells", "title"],
                  "properties": {
                    "title": { "type": "string", "minLength": 10 },
                    "mood": { "enum": ["calm", "loud"] },
                    "cells": { "type": "array", "minItems": 2, "maxItems": 5, "items": {
                      "type": "object", "properties": {
                        "row": { "type": "integer", "minimum": 0 },
                        "col": { "type": "integer", "minimum": 1 },
                        "approved": { "type": "boolean" } } } } } }
                """;
        InProcExternalAiCapabilityAdapter adapter = new InProcExternalAiCapabilityAdapter(packDir);
        ExternalAiStructuredResult first = adapter.generateStructured(
                new ExternalAiStructuredRequest("gemini", null, "paint", schema, null, null, null));
        ExternalAiStructuredResult second = adapter.generateStructured(
                new ExternalAiStructuredRequest("gemini", null, "paint", schema, null, null, null));

        JsonNode answer = new ObjectMapper().readTree(first.json());
        assertEquals(first.json(), second.json(), "offline answers are deterministic");
        assertTrue(answer.path("title").asText().length() >= 10);
        assertEquals("calm", answer.path("mood").asText());
        assertEquals(2, answer.path("cells").size());
        assertEquals(1, answer.at("/cells/0/col").asInt());
        assertEquals("offline", first.model());
        assertEquals(0, first.inputTokens());
        assertEquals(0, first.outputTokens());
    }
}
