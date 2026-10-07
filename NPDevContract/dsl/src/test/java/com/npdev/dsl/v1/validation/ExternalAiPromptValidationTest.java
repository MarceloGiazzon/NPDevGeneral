package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledExternalAi;
import com.npdev.dsl.v1.compiled.CompiledExternalAiPrompt;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P4 (G3): externalAi.prompts / externalAi.limits and the flow-side externalAi.generate call. */
class ExternalAiPromptValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String GOOD_PROMPT = """
            { "name": "PaintMosaic", "vendor": "gemini", "template": "Paint {{description}} using {{palette.ids}}",
              "image": "photo", "maxOutputTokens": 512,
              "outputSchema": { "type": "object", "required": ["cells"],
                                "properties": { "cells": { "type": "array" } } } }
            """;

    private static ModelAst parse(String externalAiJson, String flowArg0) throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.externalai", "version": "1.0",
              "concepts": [
                { "name": "Mosaic", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ],
              "propertyScopes": [ { "name": "user", "from": "$user.id" }, { "name": "tenant" } ],
              "properties": [
                { "name": "aiCalls", "type": "int", "default": 3, "settableAt": ["tenant", "user"] },
                { "name": "aiBudget", "type": "string", "default": "1.50", "settableAt": ["tenant"] },
                { "name": "aiFlag", "type": "boolean", "default": false, "settableAt": ["tenant"] }
              ],
              "capabilities": [ { "name": "externalAi", "type": "ExternalAiCapability", "operations": ["generate"] } ],
              "bindings": [ { "capability": "externalAi", "adapter": "externalAi" } ],
              "flows": [ { "name": "PaintFlow", "input": { "concept": "Mosaic", "mode": "query" }, "steps": [
                { "name": "ask", "type": "capabilityCall", "capability": "externalAi", "operation": "generate",
                  "args": [ "%s", "$input" ], "output": "$answer" },
                { "name": "done", "type": "return", "value": "$answer" } ] } ],
              "externalAi": %s
            }
            """.formatted(flowArg0, externalAiJson);
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    private static List<String> aiErrors(String externalAiJson, String flowArg0) throws Exception {
        return new SemanticValidator().validate(parse(externalAiJson, flowArg0)).stream()
                .filter(error -> error.contains("externalAi"))
                .toList();
    }

    @Test
    void wellFormedPromptLimitsAndFlowCallPass() throws Exception {
        List<String> errors = aiErrors("""
                { "egress": "apiEnabled", "vendors": ["gemini"], "prompts": [ %s ],
                  "limits": { "callsPerUserPerDay": "aiCalls", "monthlyCostCapUsd": "aiBudget" } }
                """.formatted(GOOD_PROMPT), "PaintMosaic");
        assertEquals(List.of(), errors);
    }

    @Test
    void promptsUnderDeniedEgressAreRejected() throws Exception {
        List<String> errors = aiErrors("""
                { "egress": "denied", "vendors": ["gemini"], "prompts": [ %s ] }
                """.formatted(GOOD_PROMPT), "PaintMosaic");
        assertTrue(errors.stream().anyMatch(e -> e.contains("egress is 'denied'")), errors.toString());
    }

    @Test
    void vendorOutsideTheAllowListIsRejected() throws Exception {
        List<String> errors = aiErrors("""
                { "egress": "apiEnabled", "vendors": ["openai"], "prompts": [ %s ] }
                """.formatted(GOOD_PROMPT), "PaintMosaic");
        assertTrue(errors.stream().anyMatch(e -> e.contains("vendor 'gemini' is not in externalAi.vendors")),
                errors.toString());
    }

    @Test
    void badPlaceholderUnbalancedBracesAndSchemaWithoutTypeAreRejected() throws Exception {
        List<String> errors = aiErrors("""
                { "egress": "apiEnabled", "vendors": ["gemini"], "prompts": [
                  { "name": "Bad", "vendor": "gemini", "template": "Hi {{ not a path }} and {{oops",
                    "outputSchema": { "properties": {} } } ] }
                """, "Bad");
        assertTrue(errors.stream().anyMatch(e -> e.contains("is not a field path")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("unbalanced")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("top-level 'type'")), errors.toString());
    }

    @Test
    void duplicatePromptNamesAreRejected() throws Exception {
        List<String> errors = aiErrors("""
                { "egress": "apiEnabled", "vendors": ["gemini"], "prompts": [ %s, %s ] }
                """.formatted(GOOD_PROMPT, GOOD_PROMPT), "PaintMosaic");
        assertTrue(errors.stream().anyMatch(e -> e.contains("duplicate prompt name")), errors.toString());
    }

    @Test
    void limitsMustNameDeclaredPropertiesOfTheRightType() throws Exception {
        List<String> errors = aiErrors("""
                { "egress": "apiEnabled", "vendors": ["gemini"], "prompts": [ %s ],
                  "limits": { "callsPerUserPerDay": "aiBudget", "monthlyCostCapUsd": "nope" } }
                """.formatted(GOOD_PROMPT), "PaintMosaic");
        assertTrue(errors.stream().anyMatch(e -> e.contains("callsPerUserPerDay names property 'aiBudget' of type")),
                errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("'nope' which is not declared")), errors.toString());
    }

    @Test
    void flowCallMustNameADeclaredPromptLiterally() throws Exception {
        String externalAi = """
                { "egress": "apiEnabled", "vendors": ["gemini"], "prompts": [ %s ] }
                """.formatted(GOOD_PROMPT);
        assertTrue(aiErrors(externalAi, "PaintMosiac").stream()
                .anyMatch(e -> e.contains("names prompt 'PaintMosiac' which is not declared")));
        assertTrue(aiErrors(externalAi, "$promptName").stream()
                .anyMatch(e -> e.contains("needs the prompt name as a literal arg")));
    }

    @Test
    void aProcedureCallNamingAnUndeclaredPromptIsRejected() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.externalai", "version": "1.0",
              "concepts": [ { "name": "Mosaic", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
              "capabilities": [ { "name": "externalAi", "type": "ExternalAiCapability", "operations": ["generate"] } ],
              "bindings": [ { "capability": "externalAi", "adapter": "externalAi" } ],
              "procedures": [ { "name": "PaintProc", "steps": [
                { "name": "ask", "type": "capabilityCall", "capability": "externalAi", "operation": "generate",
                  "args": { "prompt": "PaintMosiac", "input": "$input" }, "target": "answer" },
                { "name": "done", "type": "return", "value": "$answer" } ] } ],
              "externalAi": { "egress": "apiEnabled", "vendors": ["gemini"], "prompts": [ %s ] }
            }
            """.formatted(GOOD_PROMPT);
        List<String> errors = new SemanticValidator().validate(new JsonModelParser().parse(MAPPER.readTree(json)));
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("Procedure PaintProc step ask")
                && e.contains("names prompt 'PaintMosiac'")), errors.toString());
    }

    @Test
    void promptsAndLimitsSurviveTheCanonicalJsonRoundTrip() throws Exception {
        ModelAst ast = parse("""
                { "egress": "apiEnabled", "vendors": ["gemini"], "prompts": [ %s ],
                  "limits": { "callsPerUserPerDay": "aiCalls", "monthlyCostCapUsd": "aiBudget" } }
                """.formatted(GOOD_PROMPT), "PaintMosaic");
        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledModel reread = CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(compiled));

        CompiledExternalAi externalAi = reread.getExternalAi();
        assertEquals("aiCalls", externalAi.getCallsPerUserPerDayProperty());
        assertEquals("aiBudget", externalAi.getMonthlyCostCapUsdProperty());
        CompiledExternalAiPrompt prompt = externalAi.findPrompt("PaintMosaic").orElseThrow();
        assertEquals("gemini", prompt.vendor());
        assertEquals("Paint {{description}} using {{palette.ids}}", prompt.template());
        assertEquals("photo", prompt.image());
        assertEquals(512, prompt.maxOutputTokens());
        assertEquals(MAPPER.readTree("{\"type\":\"object\",\"required\":[\"cells\"],"
                        + "\"properties\":{\"cells\":{\"type\":\"array\"}}}"),
                MAPPER.readTree(prompt.outputSchemaJson()));
        assertEquals(compiled.getExternalAi().getPrompts(), externalAi.getPrompts());
    }
}
