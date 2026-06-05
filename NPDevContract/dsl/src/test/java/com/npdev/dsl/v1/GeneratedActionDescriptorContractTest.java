package com.npdev.dsl.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledCapabilityCall;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledGeneratedActionDescriptorSpec;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GeneratedActionDescriptorContractTest {
    @TempDir
    Path tempDir;

    @Test
    void explicitProcedureActionDescriptorParsesCompilesAndRoundTrips() throws Exception {
        CompiledModel compiled = compile("""
                {
                  "namespace": "contract.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0.0",
                  "concepts": [{ "name": "Account", "fields": [{ "name": "id", "type": "string", "id": true }] }],
                  "procedures": [
                    {
                      "name": "CreateUser",
                      "steps": [{ "type": "return" }],
                      "metadata": {
                        "sideEffectConcept": "LegacyShouldNotWin",
                        "eventNameOnSuccess": "generated.action.legacy.completed"
                      },
                      "actionDescriptor": {
                        "actionName": "RegisterAccount",
                        "affectedConcepts": ["Account"],
                        "sideEffectConcept": "Account",
                        "eventNameOnSuccess": "generated.action.register-account.completed",
                        "auditResourceType": "ACCOUNT",
                        "idempotencyPolicy": "record",
                        "tracePolicy": "record",
                        "correlationPolicy": "claim"
                      }
                    }
                  ]
                }
                """);

        CompiledGeneratedActionDescriptorSpec descriptor = compiled.getProcedures().get(0).actionDescriptor();
        assertNotNull(descriptor);
        assertTrue(descriptor.explicit());
        assertEquals("RegisterAccount", descriptor.actionName());
        assertEquals("Account", descriptor.sideEffectConcept());
        assertEquals("generated.action.register-account.completed", descriptor.eventNameOnSuccess());
        assertEquals("ACCOUNT", descriptor.auditResourceType());
        assertEquals("record", descriptor.idempotencyPolicy());
        assertEquals("record", descriptor.tracePolicy());
        assertEquals("claim", descriptor.correlationPolicy());

        CompiledModel roundTripped = CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(compiled));
        CompiledGeneratedActionDescriptorSpec readBack = roundTripped.getProcedures().get(0).actionDescriptor();
        assertNotNull(readBack);
        assertTrue(readBack.explicit());
        assertEquals("RegisterAccount", readBack.actionName());
        assertEquals("Account", readBack.sideEffectConcept());
    }

    @Test
    void oldProcedureWithoutActionDescriptorStillCompilesThroughLegacyCompatibility() throws Exception {
        CompiledModel compiled = compile("""
                {
                  "namespace": "contract.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0.0",
                  "concepts": [{ "name": "Account", "fields": [{ "name": "id", "type": "string", "id": true }] }],
                  "procedures": [
                    {
                      "name": "CreateUser",
                      "steps": [{ "type": "return" }],
                      "metadata": {
                        "sideEffectConcept": "User",
                        "eventNameOnSuccess": "generated.action.create-user.completed"
                      }
                    }
                  ]
                }
                """);

        CompiledGeneratedActionDescriptorSpec descriptor = compiled.getProcedures().get(0).actionDescriptor();
        assertNotNull(descriptor);
        assertFalse(descriptor.explicit());
        assertEquals("CreateUser", descriptor.actionName());
        assertEquals("User", descriptor.sideEffectConcept());
        assertEquals("generated.action.create-user.completed", descriptor.eventNameOnSuccess());
    }

    @Test
    void explicitDescriptorWithoutSideEffectConceptDoesNotInferFromProcedureName() throws Exception {
        CompiledModel compiled = compile("""
                {
                  "namespace": "contract.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0.0",
                  "concepts": [{ "name": "Account", "fields": [{ "name": "id", "type": "string", "id": true }] }],
                  "procedures": [
                    {
                      "name": "CreateUser",
                      "steps": [{ "type": "return" }],
                      "actionDescriptor": {
                        "actionName": "CreateUser",
                        "eventNameOnSuccess": "generated.action.create-user.completed"
                      }
                    }
                  ]
                }
                """);

        CompiledGeneratedActionDescriptorSpec descriptor = compiled.getProcedures().get(0).actionDescriptor();
        assertNotNull(descriptor);
        assertTrue(descriptor.explicit());
        assertEquals(null, descriptor.sideEffectConcept());
        assertTrue(descriptor.affectedConcepts().isEmpty());
    }

    @Test
    void unknownProcedureFieldsStillFollowSchemaRejectionPolicy() {
        assertThrows(Exception.class, () -> compile("""
                {
                  "namespace": "contract.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0.0",
                  "concepts": [{ "name": "Account", "fields": [{ "name": "id", "type": "string", "id": true }] }],
                  "procedures": [
                    {
                      "name": "CreateUser",
                      "steps": [{ "type": "return" }],
                      "unknownActionDescriptorField": true
                    }
                  ]
                }
                """));
    }

    @Test
    void generatedActionFlowStartEndpointParsesCompilesAndRoundTrips() throws Exception {
        CompiledModel compiled = compile("""
                {
                  "namespace": "contract.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0.0",
                  "concepts": [{ "name": "Account", "fields": [{ "name": "id", "type": "string", "id": true }] }],
                  "procedures": [
                    {
                      "name": "RegisterAccount",
                      "steps": [{ "type": "return" }],
                      "actionDescriptor": {
                        "actionName": "RegisterAccount",
                        "affectedConcepts": ["Account"],
                        "sideEffectConcept": "Account",
                        "eventNameOnSuccess": "generated.action.register-account.completed"
                      }
                    }
                  ],
                  "flows": [
                    {
                      "name": "RegisterAccountFlow",
                      "concept": "Account",
                      "startEndpoint": true,
                      "steps": [
                        {
                          "name": "runGeneratedAction",
                          "type": "generatedAction",
                          "actionName": "RegisterAccount",
                          "args": ["input"],
                          "input": "input",
                          "output": "actionResult",
                          "policy": { "idempotencyKeyField": "input.idempotencyKey" }
                        }
                      ]
                    }
                  ]
                }
                """);

        assertEquals(1, compiled.getFlows().size());
        CompiledFlow flow = compiled.getFlows().get(0);
        assertTrue(flow.isStartEndpoint());
        assertEquals("RegisterAccountFlow", flow.getName());
        CompiledFlowStep step = flow.getSteps().get(0);
        assertEquals("generatedAction", step.getType());
        assertEquals("RegisterAccount", step.getGeneratedActionName());
        CompiledCapabilityCall call = step.getCapabilityCall();
        assertNotNull(call);
        assertEquals("generated.action.RegisterAccount", call.getCapabilityName());
        assertEquals("GeneratedActionCapability", call.getCapabilityType());
        assertEquals("generated-action", call.getAdapterId());
        assertEquals("run", call.getOperation());
        assertEquals("input.idempotencyKey", call.getExecutionPolicy().getIdempotencyKeyField());

        CompiledModel roundTripped = CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(compiled));
        CompiledFlow readBack = roundTripped.getFlows().get(0);
        assertTrue(readBack.isStartEndpoint());
        assertEquals("RegisterAccount", readBack.getSteps().get(0).getGeneratedActionName());
        assertEquals("generated-action", readBack.getSteps().get(0).getCapabilityCall().getAdapterId());
    }

    private CompiledModel compile(String json) throws Exception {
        Path model = tempDir.resolve("model-" + Math.abs(json.hashCode()) + ".json");
        Files.writeString(model, json, StandardCharsets.UTF_8);
        ModelAst ast = new JsonModelParser().parse(model);
        return new ModelCompiler().compile(ast);
    }
}
