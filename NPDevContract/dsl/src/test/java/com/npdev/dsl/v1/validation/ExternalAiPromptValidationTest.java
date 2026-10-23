package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledExternalAi;
import com.npdev.dsl.v1.compiled.CompiledExternalAiPrompt;
import com.npdev.dsl.v1.compiled.CompiledExternalAiProvider;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void aVendorTheCatalogueDoesNotKnowIsRejectedNamingTheKnownOnes() throws Exception {
        List<String> errors = aiErrors("""
                { "egress": "apiEnabled", "vendors": ["gemini", "acme-llm"], "prompts": [ %s ] }
                """.formatted(GOOD_PROMPT), "PaintMosaic");
        assertTrue(errors.stream().anyMatch(e -> e.contains("externalAi.vendors: 'acme-llm' is not an app vendor")
                && e.contains("groq") && e.contains("ollama")), errors.toString());
        assertEquals(1, errors.size(), errors.toString());
    }

    @Test
    void freeAndLocalCatalogueVendorsAreAccepted() throws Exception {
        List<String> errors = aiErrors("""
                { "egress": "apiEnabled", "vendors": ["Groq", "cerebras", "openrouter", "mistral", "ollama"],
                  "prompts": [ %s ] }
                """.formatted(GOOD_PROMPT.replace("\"gemini\"", "\"groq\"")), "PaintMosaic");
        assertEquals(List.of(), errors);
    }

    @Test
    void aPromptOnALocalLlmServerIsValidButWarnsNamingThePromptAndProvider() throws Exception {
        ValidationResult result = new SemanticValidator().validateWithWarnings(parse("""
                { "egress": "apiEnabled", "vendors": ["ollama"], "prompts": [ %s ] }
                """.formatted(GOOD_PROMPT.replace("\"gemini\"", "\"ollama\"")), "PaintMosaic"));

        assertEquals(List.of(), result.getErrors().stream().filter(e -> e.contains("externalAi")).toList());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("externalAi.prompts['PaintMosaic']")
                && w.contains("'ollama'") && w.contains("localhost")), result.getWarnings().toString());
    }

    @Test
    void aCloudVendorDoesNotWarn() throws Exception {
        ValidationResult result = new SemanticValidator().validateWithWarnings(parse("""
                { "egress": "apiEnabled", "vendors": ["gemini"], "prompts": [ %s ] }
                """.formatted(GOOD_PROMPT), "PaintMosaic"));

        assertTrue(result.getWarnings().stream().noneMatch(w -> w.contains("local LLM server")),
                result.getWarnings().toString());
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

    /** AIP-3 (3.4a): a catalogue profile instead of vendor + model, and the prompt's effort over the profile's. */
    @Test
    void aProfileCompilesToItsVendorModelAndEffortAndSurvivesTheRoundTrip() throws Exception {
        String byProfile = GOOD_PROMPT.replace("\"vendor\": \"gemini\"", "\"profile\": \"claude\"");
        ModelAst ast = parse("""
                { "egress": "apiEnabled", "vendors": ["anthropic"], "prompts": [ %s,
                  %s ] }
                """.formatted(byProfile, byProfile.replace("PaintMosaic", "QuickMosaic")
                        .replace("\"profile\": \"claude\"", "\"profile\": \"claude\", \"effort\": \"low\"")),
                "PaintMosaic");
        assertEquals(List.of(), new SemanticValidator().validate(ast).stream()
                .filter(error -> error.contains("externalAi")).toList());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledExternalAi reread = CompiledModelCanonicalJsonReader.fromJson(
                CompiledModelCanonicalJson.toJson(compiled)).getExternalAi();
        CompiledExternalAiPrompt paint = reread.findPrompt("PaintMosaic").orElseThrow();
        assertEquals(List.of("anthropic", "claude-opus-5-5", "high", "claude"),
                List.of(paint.vendor(), paint.model(), paint.effort(), paint.profile()));
        assertEquals("low", reread.findPrompt("QuickMosaic").orElseThrow().effort());
        assertEquals(compiled.getExternalAi().getPrompts(), reread.getPrompts());
    }

    @Test
    void profileErrorsNameTheProblem() throws Exception {
        String both = GOOD_PROMPT.replace("\"vendor\": \"gemini\"", "\"vendor\": \"gemini\", \"profile\": \"fast\"");
        String withModel = GOOD_PROMPT.replace("\"vendor\": \"gemini\"", "\"profile\": \"fast\", \"model\": \"x\"");
        String unknown = GOOD_PROMPT.replace("\"vendor\": \"gemini\"", "\"profile\": \"turbo\"");
        String notListed = GOOD_PROMPT.replace("\"vendor\": \"gemini\"", "\"profile\": \"fast-alt\"");
        String badEffort = GOOD_PROMPT.replace("\"vendor\": \"gemini\"", "\"vendor\": \"gemini\", \"effort\": \"max\"");
        String neither = GOOD_PROMPT.replace("\"vendor\": \"gemini\",", "");
        List<String> errors = new java.util.ArrayList<>();
        for (String prompt : List.of(both, withModel, unknown, notListed, neither)) {
            errors.addAll(aiErrors("""
                    { "egress": "apiEnabled", "vendors": ["gemini"], "prompts": [ %s ] }
                    """.formatted(prompt), "PaintMosaic"));
        }
        String all = String.join("\n", errors);
        assertTrue(all.contains("give vendor or profile, not both"), all);
        assertTrue(all.contains("model cannot be set with a profile"), all);
        assertTrue(all.contains("profile 'turbo' is not in the NPDev AI catalogue") && all.contains("fast"), all);
        assertTrue(all.contains("vendor 'groq' (from profile 'fast-alt') is not in externalAi.vendors"), all);
        // A bad effort never gets this far: the schema's enum names the allowed values.
        Exception schema = assertThrows(Exception.class, () -> parse("""
                { "egress": "apiEnabled", "vendors": ["gemini"], "prompts": [ %s ] }
                """.formatted(badEffort), "PaintMosaic"));
        assertTrue(schema.getMessage().contains("effort") && schema.getMessage().contains("medium"), schema.getMessage());
        assertTrue(all.contains("vendor or profile is required"), all);
    }

    private static final String OWN_PROVIDER = """
            { "id": "acme-gw", "protocol": "openai-chat", "baseUrl": "https://ai.acme.example/v1/",
              "defaultModel": "acme-large", "price": { "in": 0.5, "out": 1.25 } }
            """;

    /** AIP-3 (3.6, D4): the app's own endpoint is a vendor once declared and listed, and round-trips. */
    @Test
    void anAppsOwnProviderIsAnAppVendorAndSurvivesTheRoundTrip() throws Exception {
        String prompt = GOOD_PROMPT.replace("\"vendor\": \"gemini\"", "\"vendor\": \"acme-gw\"");
        ModelAst ast = parse("""
                { "egress": "apiEnabled", "vendors": ["acme-gw"], "providers": [ %s ], "prompts": [ %s ] }
                """.formatted(OWN_PROVIDER, prompt), "PaintMosaic");
        assertEquals(List.of(), new SemanticValidator().validate(ast).stream()
                .filter(error -> error.contains("externalAi")).toList());

        CompiledModel reread = CompiledModelCanonicalJsonReader.fromJson(
                CompiledModelCanonicalJson.toJson(new ModelCompiler().compile(ast)));
        CompiledExternalAiProvider provider = reread.getExternalAi().getProviders().get(0);
        assertEquals("acme-gw", provider.id());
        assertEquals("acme-gw", provider.name());
        assertEquals("https://ai.acme.example/v1", provider.baseUrl(), "trailing slash dropped");
        assertTrue(provider.needsKey());
        assertEquals("NPDEV_EXTERNALAI_ACME_GW_API_KEY", provider.keyEnvVar());
        assertEquals("acme-large", provider.defaultModel());
        assertEquals(0, new java.math.BigDecimal("1.25").compareTo(provider.outputUsdPerMTok()));
    }

    @Test
    void anOwnProviderMustNotShadowTheCatalogueAndMustBeSafeToSendAKeyTo() throws Exception {
        List<String> errors = aiErrors("""
                { "egress": "apiEnabled", "vendors": ["gemini"], "providers": [
                  { "id": "gemini", "protocol": "openai-chat", "baseUrl": "https://evil.example", "defaultModel": "m" },
                  { "id": "deepseek", "protocol": "openai-chat", "baseUrl": "https://evil.example", "defaultModel": "m" },
                  { "id": "plain", "protocol": "openai-chat", "baseUrl": "http://ai.example.com/v1", "defaultModel": "m" },
                  { "id": "query", "protocol": "openai-chat", "baseUrl": "https://ai.example.com/v1?k=1", "defaultModel": "m" },
                  { "id": "local", "protocol": "openai-chat", "baseUrl": "http://localhost:8080/v1", "defaultModel": "m" },
                  { "id": "local", "protocol": "openai-chat", "baseUrl": "http://127.0.0.1/v1", "defaultModel": "m" } ] }
                """, "PaintMosaic");
        String all = String.join("\n", errors);
        assertTrue(all.contains("'gemini': the id is a provider the NPDev AI catalogue ships"), all);
        assertTrue(all.contains("'deepseek': the id is a provider"), "a non-app-vendor id is still shipped: " + all);
        assertTrue(all.contains("'plain': baseUrl 'http://ai.example.com/v1' must use https"), all);
        assertTrue(all.contains("'query': baseUrl") && all.contains("query"), all);
        assertTrue(all.contains("'local': duplicate provider id"), all);
        assertEquals(5, errors.stream().filter(e -> e.startsWith("externalAi.providers")).count(),
                "http to loopback is allowed: " + all);
    }

    @Test
    void anUndeclaredVendorStillFailsAndALoopbackOwnProviderWarns() throws Exception {
        assertTrue(aiErrors("""
                { "egress": "apiEnabled", "vendors": ["acme-gw"] }
                """, "PaintMosaic").stream().anyMatch(e -> e.contains("nor one of this model's externalAi.providers")));

        String prompt = GOOD_PROMPT.replace("\"vendor\": \"gemini\"", "\"vendor\": \"box\"");
        ModelAst ast = parse("""
                { "egress": "apiEnabled", "vendors": ["box"], "providers": [ { "id": "box", "protocol": "openai-chat",
                  "baseUrl": "http://localhost:8000/v1", "needsKey": false, "defaultModel": "m" } ], "prompts": [ %s ] }
                """.formatted(prompt), "PaintMosaic");
        List<String> warnings = new SemanticValidator().validateWithWarnings(ast).getWarnings();
        assertTrue(warnings.stream().anyMatch(w -> w.contains("'box', a local LLM server at http://localhost:8000/v1")),
                warnings.toString());
        CompiledExternalAiProvider box = new ModelCompiler().compile(ast).getExternalAi().getProviders().get(0);
        assertEquals(null, box.keyEnvVar());
    }
}
