package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.DeprecationException;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractSurfaceSupportTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesCompilesAndRoundTripsContractSurfaces() throws Exception {
        Path modelPath = writeTempModel("""
                {
                  "dslVersion": "1.0.0",
                  "namespace": "close.contract",
                  "version": "1.0.0",
                  "concepts": [
                    {
                      "name": "WorkItem",
                      "ui": { "label": "Work item" },
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "status", "type": "string", "required": true, "ui": { "label": "Status" } },
                        { "name": "title", "type": "string", "required": true, "ui": { "label": "Title" } }
                      ]
                    }
                  ],
                  "queries": [
                    {
                      "name": "OpenWorkItems",
                      "concept": "WorkItem",
                      "where": "status == 'open'",
                      "orderBy": ["title"],
                      "parameters": [
                        { "name": "tenantId", "type": "string", "required": true }
                      ],
                      "tracePolicy": "summary"
                    }
                  ],
                  "ruleProfiles": [
                    {
                      "name": "interactive",
                      "appliesTo": ["WorkItem"],
                      "enabled": true
                    }
                  ],
                  "procedures": [
                    {
                      "name": "SubmitWorkItem",
                      "parameters": [
                        { "name": "title", "type": "string", "required": true }
                      ],
                      "steps": [
                        {
                          "name": "save-item",
                          "type": "conceptCreate",
                          "concept": "WorkItem",
                          "data": { "title": "title", "status": "open" }
                        },
                        {
                          "name": "return-item",
                          "type": "return",
                          "value": "result"
                        }
                      ],
                      "returns": { "type": "object", "properties": { "id": { "type": "uuid" } } },
                      "auditPolicy": "write"
                    }
                  ],
                  "panels": [
                    {
                      "name": "WorkQueue",
                      "route": "/work",
                      "title": "Work queue",
                      "dataSources": [
                        { "name": "items", "query": "OpenWorkItems" }
                      ],
                      "layout": { "type": "table", "fields": ["title", "status"] },
                      "fieldBindings": [
                        { "field": "title", "source": "items.title" }
                      ],
                      "actions": [
                        {
                          "name": "submit",
                          "binding": "procedure",
                          "procedure": "SubmitWorkItem",
                          "label": "Submit"
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        assertEquals(1, ast.getQueries().size());
        assertEquals(1, ast.getRuleProfiles().size());
        assertEquals(1, ast.getProcedures().size());
        assertEquals(1, ast.getPanels().size());

        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected semantic validation to accept contract surfaces: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertEquals(1, compiled.getQueries().size());
        assertEquals("OpenWorkItems", compiled.getQueries().get(0).name());
        assertEquals(1, compiled.getProcedures().size());
        assertEquals("SubmitWorkItem", compiled.getProcedures().get(0).name());
        assertEquals(1, compiled.getPanels().size());
        assertEquals("WorkQueue", compiled.getPanels().get(0).name());

        String canonicalJson = CompiledModelCanonicalJson.toJson(compiled);
        JsonNode canonical = MAPPER.readTree(canonicalJson);
        assertTrue(canonical.has("concepts"));
        assertFalse(canonical.has("entities"));
        assertEquals("OpenWorkItems", canonical.get("queries").get(0).get("name").asText());
        assertEquals("interactive", canonical.get("ruleProfiles").get(0).get("name").asText());
        assertEquals("SubmitWorkItem", canonical.get("procedures").get(0).get("name").asText());
        assertEquals("WorkQueue", canonical.get("panels").get(0).get("name").asText());

        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(canonicalJson);
        assertEquals("OpenWorkItems", restored.getQueries().get(0).name());
        assertEquals("SubmitWorkItem", restored.getProcedures().get(0).name());
        assertEquals("WorkQueue", restored.getPanels().get(0).name());
    }

    @Test
    void parsesFirstClassOrchestrationsSurfaceAsSupportedAlias() throws Exception {
        Path modelPath = writeTempModel("""
                {
                  "dslVersion": "1.0.0",
                  "namespace": "close.contract",
                  "version": "1.0.0",
                  "concepts": [
                    {
                      "name": "WorkItem",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ],
                  "events": [
                    { "name": "WorkItemSubmitted", "payload": [{ "name": "id", "type": "uuid" }] }
                  ],
                  "orchestrations": [
                    {
                      "name": "NotifyOnSubmit",
                      "trigger": { "type": "event", "event": "WorkItemSubmitted" },
                      "action": {
                        "type": "scheduleEvent",
                        "event": "WorkItemSubmitted",
                        "delaySeconds": 0,
                        "map": {
                          "id": "$event.id"
                        }
                      }
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        assertEquals(1, ast.getOrchestrationRules().size());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertEquals("NotifyOnSubmit", compiled.getOrchestrationRules().get(0).getName());
    }

    @Test
    void rejectsSupportedSurfaceThatCompilerWouldNotUnderstand() throws Exception {
        Path modelPath = writeTempModel("""
                {
                  "dslVersion": "1.0.0",
                  "namespace": "close.contract",
                  "version": "1.0.0",
                  "concepts": [
                    {
                      "name": "WorkItem",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ],
                  "procedures": [
                    {
                      "name": "MysteryProcedure",
                      "steps": [
                        { "type": "runUnboundedScript" }
                      ]
                    }
                  ]
                }
                """);

        Exception exception = assertThrows(Exception.class, () -> new JsonModelParser().parse(modelPath));
        assertTrue(exception.getMessage().contains("Model schema validation failed")
                        && exception.getMessage().toLowerCase().contains("enum"),
                "Expected unsupported procedure step to fail fast: " + exception.getMessage());
    }

    @Test
    void rejectsLegacyEntitiesContractAlias() throws Exception {
        Path modelPath = writeTempModel("""
                {
                  "dslVersion": "1.0.0",
                  "namespace": "close.contract",
                  "version": "1.0.0",
                  "entities": [
                    {
                      "name": "LegacyWorkItem",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """);

        DeprecationException exception = assertThrows(
                DeprecationException.class,
                () -> new JsonModelParser().parse(modelPath)
        );
        assertEquals(
                "The V1 Contract requires 'concepts'. 'entities' is no longer supported.",
                exception.getMessage()
        );
    }

    private static Path writeTempModel(String json) throws Exception {
        Path modelPath = Files.createTempFile("npdev-close-contract", ".model.json");
        Files.writeString(modelPath, json);
        return modelPath;
    }
}
