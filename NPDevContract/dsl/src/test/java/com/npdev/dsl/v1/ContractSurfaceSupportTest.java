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
                  "dslVersion": "3.0.0",
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
                      ]
                    }
                  ],
                  "flows": [
                    {
                      "name": "SubmitWorkItem",
                      "expose": "ui",
                      "steps": [
                        {
                          "name": "return-item",
                          "type": "return",
                          "value": "$input"
                        }
                      ]
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
        assertEquals(1, ast.getFlows().size());
        assertEquals(1, ast.getPanels().size());

        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected semantic validation to accept contract surfaces: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertEquals(1, compiled.getQueries().size());
        assertEquals("OpenWorkItems", compiled.getQueries().get(0).name());
        assertEquals(1, compiled.getFlows().size());
        assertEquals("SubmitWorkItem", compiled.getFlows().get(0).getName());
        assertEquals(1, compiled.getPanels().size());
        assertEquals("WorkQueue", compiled.getPanels().get(0).name());

        String canonicalJson = CompiledModelCanonicalJson.toJson(compiled);
        JsonNode canonical = MAPPER.readTree(canonicalJson);
        assertTrue(canonical.has("concepts"));
        assertFalse(canonical.has("entities"));
        assertEquals("OpenWorkItems", canonical.get("queries").get(0).get("name").asText());
        assertEquals("SubmitWorkItem", canonical.get("flows").get(0).get("name").asText());
        assertEquals("WorkQueue", canonical.get("panels").get(0).get("name").asText());

        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(canonicalJson);
        assertEquals("OpenWorkItems", restored.getQueries().get(0).name());
        assertEquals("SubmitWorkItem", restored.getFlows().get(0).getName());
        assertEquals("WorkQueue", restored.getPanels().get(0).name());
    }

    @Test
    void rejectsRemovedOrchestrationsSurfaceWithTheFlowReplacement() throws Exception {
        for (String key : new String[] {"orchestrations", "orchestrationRules"}) {
            Path modelPath = writeTempModel("""
                    {
                      "dslVersion": "3.0.0",
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
                      "KEY": []
                    }
                    """.replace("KEY", key));

            java.io.IOException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    java.io.IOException.class, () -> new JsonModelParser().parse(modelPath));
            // The schema refuses the key first; the parser's own refusal (with the flow replacement) is the backstop.
            org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains(key), failure.getMessage());
        }
    }

    @Test
    void rejectsRetiredQueryTracePolicy() throws Exception {
        // REG-301: read by nothing at runtime, so retired from the schema; `npdev migrate dsl-2` strips it.
        for (String declaring : new String[] {
                "\"queries\": [ { \"name\": \"AllItems\", \"concept\": \"WorkItem\", \"tracePolicy\": \"summary\" } ]"}) {
            Path modelPath = writeTempModel("""
                    {
                      "dslVersion": "3.0.0", "namespace": "close.contract", "version": "1.0.0",
                      "concepts": [ { "name": "WorkItem", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
                      %s
                    }
                    """.formatted(declaring));
            Exception exception = assertThrows(Exception.class, () -> new JsonModelParser().parse(modelPath));
            assertTrue(exception.getMessage().contains("Model schema validation failed")
                            && exception.getMessage().contains("tracePolicy"),
                    "Expected the retired tracePolicy to be refused: " + exception.getMessage());
        }
    }

    @Test
    void rejectsRemovedRootProceduresWithTheFlowReplacement() throws Exception {
        // REG-342: every procedure is a flow; the root key is gone from the schema and refused by the parser.
        Path modelPath = writeTempModel("""
                {
                  "dslVersion": "3.0.0", "namespace": "close.contract", "version": "1.0.0",
                  "concepts": [ { "name": "WorkItem", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
                  "procedures": [ { "name": "Noop", "steps": [ { "name": "r", "type": "return", "value": "x" } ] } ]
                }
                """);
        Exception exception = assertThrows(Exception.class, () -> new JsonModelParser().parse(modelPath));
        assertTrue(exception.getMessage().contains("procedures") && exception.getMessage().contains("flow"),
                "Expected a legacy-procedures refusal that names the flow replacement: " + exception.getMessage());
    }

    @Test
    void rejectsRetiredAggregateCollectionVia() throws Exception {
        // REG-301: no runtime class called via() (childField is the link), so retired from the schema;
        // `npdev migrate dsl-2` strips it.
        Path modelPath = writeTempModel("""
                {
                  "dslVersion": "3.0.0", "namespace": "close.contract", "version": "1.0.0",
                  "concepts": [ { "name": "WorkItem", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ],
                    "composition": { "collections": [
                      { "name": "children", "concept": "WorkItem", "childField": "id", "via": "bondChildren" } ] } } ]
                }
                """);
        Exception exception = assertThrows(Exception.class, () -> new JsonModelParser().parse(modelPath));
        assertTrue(exception.getMessage().contains("Model schema validation failed")
                        && exception.getMessage().contains("via"),
                "Expected the retired aggregate collection via to be refused: " + exception.getMessage());
    }

    @Test
    void rejectsRetiredAggregateCollectionMetadata() throws Exception {
        // ENF-1 (2026-10-24): read and served by nothing, declared by no model; `npdev migrate dsl-2` strips it.
        Path modelPath = writeTempModel("""
                {
                  "dslVersion": "3.0.0", "namespace": "close.contract", "version": "1.0.0",
                  "concepts": [ { "name": "WorkItem", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ],
                    "composition": { "metadata": { "purpose": "still fine" }, "collections": [
                      { "name": "children", "concept": "WorkItem", "childField": "id", "metadata": { "note": "retired" } } ] } } ]
                }
                """);
        Exception exception = assertThrows(Exception.class, () -> new JsonModelParser().parse(modelPath));
        assertTrue(exception.getMessage().contains("Model schema validation failed")
                        && exception.getMessage().contains("metadata"),
                "Expected the retired aggregate collection metadata to be refused: " + exception.getMessage());
    }

    @Test
    void rejectsRetiredFlowQueryAndAggregateUid() throws Exception {
        // ENF-1 (2026-10-24): flow/query/aggregate uid were read by nothing (flow state is keyed by name; queries
        // and aggregates keep no state of their own), so retired; `npdev migrate dsl-2` strips them. Concept and
        // field uid stay (rename identity).
        String[] models = {
                """
                { "dslVersion": "3.0.0", "namespace": "close.contract", "version": "1.0.0",
                  "concepts": [ { "name": "WorkItem", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
                  "flows": [ { "name": "DoWork", "uid": "flow0000000001", "concept": "WorkItem", "steps": [ { "name": "r", "type": "return", "value": "$input" } ] } ] }
                """,
                """
                { "dslVersion": "3.0.0", "namespace": "close.contract", "version": "1.0.0",
                  "concepts": [ { "name": "WorkItem", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
                  "queries": [ { "name": "AllWork", "uid": "query000000001", "concept": "WorkItem" } ] }
                """,
                """
                { "dslVersion": "3.0.0", "namespace": "close.contract", "version": "1.0.0",
                  "concepts": [ { "name": "WorkItem", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ],
                    "composition": { "uid": "aggr0000000001" } } ] }
                """
        };
        for (String model : models) {
            Path modelPath = writeTempModel(model);
            Exception exception = assertThrows(Exception.class, () -> new JsonModelParser().parse(modelPath));
            assertTrue(exception.getMessage().contains("Model schema validation failed")
                            && exception.getMessage().contains("uid"),
                    "Expected the retired uid to be refused: " + exception.getMessage());
        }
    }

    @Test
    void rejectsLegacyEntitiesContractAlias() throws Exception {
        Path modelPath = writeTempModel("""
                {
                  "dslVersion": "3.0.0",
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
                "Official DSL models must use root 'concepts'. Root 'entities' is no longer supported.",
                exception.getMessage()
        );
    }

    private static Path writeTempModel(String json) throws Exception {
        Path modelPath = Files.createTempFile("npdev-close-contract", ".model.json");
        Files.writeString(modelPath, json);
        return modelPath;
    }
}
