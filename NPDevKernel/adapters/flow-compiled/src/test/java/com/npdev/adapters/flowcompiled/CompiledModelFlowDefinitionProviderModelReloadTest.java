package com.npdev.adapters.flowcompiled;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.kernel.FlowDefinition;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-237 (D1 Phase 2b): proves {@link CompiledModelFlowDefinitionProvider#reload(CompiledModel)}
 * swaps the internal snapshot in place on the SAME instance -- a subsequent {@code findFlow} call
 * observes a live model reload without the provider being reconstructed. The constructor's
 * one-shot behavior (used unchanged by CliRuntimeFactory / ModelBackedKernelRuntimeFactory) is
 * covered by {@link CompiledModelFlowDefinitionProviderTest} and intentionally not re-asserted
 * here.
 */
class CompiledModelFlowDefinitionProviderModelReloadTest {

    @Test
    void findFlowObservesLiveReloadWithoutReconstructingTheProvider() throws Exception {
        CompiledModel withoutOnboard = compile("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name":"id", "type":"uuid", "id":true, "required":true }
                      ]
                    }
                  ],
                  "flows": [
                    {
                      "name": "CreateUser",
                      "input": { "concept":"User", "mode":"create" },
                      "steps": [
                        { "type":"return", "value":"$input" }
                      ]
                    }
                  ]
                }
                """);

        CompiledModelFlowDefinitionProvider provider = new CompiledModelFlowDefinitionProvider(withoutOnboard);

        Optional<FlowDefinition> beforeReload = provider.findFlow("onboard");
        assertTrue(beforeReload.isEmpty(), "no flow named 'onboard' should exist before reload");

        CompiledModel withOnboard = compile("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name":"id", "type":"uuid", "id":true, "required":true },
                        { "name":"email", "type":"string", "required":true }
                      ]
                    }
                  ],
                  "capabilities": [
                    { "name":"persistence", "type":"PersistenceCapability", "operations":["save"] }
                  ],
                  "bindings": [
                    { "capability":"persistence", "adapter":"inmemory" }
                  ],
                  "flows": [
                    {
                      "name": "onboard",
                      "input": { "concept":"User", "mode":"create" },
                      "steps": [
                        { "name":"save", "type":"capabilityCall", "capability":"persistence", "operation":"save", "args":["$input"], "output":"$saved" },
                        { "name":"ret", "type":"return", "value":"$saved" }
                      ]
                    }
                  ]
                }
                """);

        provider.reload(withOnboard);

        Optional<FlowDefinition> afterReload = provider.findFlow("onboard");
        assertTrue(afterReload.isPresent(), "flow 'onboard' must be found on the SAME provider instance after reload(...)");
        assertEquals(2, afterReload.get().getSteps().size());
    }

    private static CompiledModel compile(String json) throws Exception {
        ModelAst ast = parse(json);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no semantic errors, got: " + errors);
        return new ModelCompiler().compile(ast);
    }

    private static ModelAst parse(String json) throws Exception {
        Path modelFile = Files.createTempFile("npdev-compiled-provider-reload-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(modelFile);
    }
}
