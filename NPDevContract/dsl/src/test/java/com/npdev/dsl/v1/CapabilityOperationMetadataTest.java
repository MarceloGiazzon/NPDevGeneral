package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledCapabilityOperation;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W4.1 (NPDEV_ROADMAP_2026-09-12): {@code capabilityOperation} previously had no way to declare
 * what it can fail with ({@code errors}), whether it mutates anything ({@code sideEffects}), or who
 * may call it ({@code auth}) -- so neither validation nor AI tooling could answer those questions
 * from the model alone. Proves the full chain a new capabilityOperation field must survive
 * (JsonModelParser -> ModelCompiler -> CompiledModelCanonicalJson writer -> reader), the same twin-
 * pair discipline {@code DslCapabilityPolicyTest} already applies to {@code policy}.
 */
class CapabilityOperationMetadataTest {

    private static final String MODEL_WITH_OPERATION_METADATA = """
            {
              "namespace":"demo",
              "dslVersion":"1.0.0",
              "version":"v1",
              "concepts":[
                {
                  "name":"WidgetOrder",
                  "fields":[
                    {"name":"id","type":"uuid","id":true},
                    {"name":"sku","type":"string","required":true}
                  ]
                }
              ],
              "capabilities":[
                {
                  "name":"persistence",
                  "type":"PersistenceCapability",
                  "operations":[
                    {
                      "name":"save",
                      "sideEffects":"writes",
                      "errors":[
                        {"name":"DuplicateSku","classification":"CONTRACT","description":"sku already exists"},
                        {"name":"StoreUnavailable","classification":"transient"}
                      ],
                      "auth":{"roles":["order-writer"],"scopes":["orders:write"]}
                    }
                  ]
                }
              ],
              "bindings":[
                {"capability":"persistence","adapter":"inmemory"}
              ]
            }
            """;

    @Test
    void compilesAndRoundTripsOperationErrorsSideEffectsAndAuth() throws Exception {
        ModelAst ast = parse(MODEL_WITH_OPERATION_METADATA);

        List<String> validationErrors = new SemanticValidator().validate(ast);
        assertTrue(validationErrors.isEmpty(), "Expected no semantic errors but got: " + validationErrors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertOperationMetadata(operationNamed(compiled, "save"));

        String json = CompiledModelCanonicalJson.toJson(compiled);
        CompiledModel roundTripped = CompiledModelCanonicalJsonReader.fromJson(json);
        assertOperationMetadata(operationNamed(roundTripped, "save"));
    }

    @Test
    void rejectsUnknownSideEffectsValueAtSchemaLayer() {
        IOException error = assertThrows(IOException.class, () -> parse(MODEL_WITH_OPERATION_METADATA
                .replace("\"sideEffects\":\"writes\"", "\"sideEffects\":\"mutates\"")));
        assertTrue(error.getMessage().contains("Model schema validation failed"));
        assertTrue(error.getMessage().contains("sideEffects"));
    }

    @Test
    void rejectsUnknownErrorClassificationAtSchemaLayer() {
        IOException error = assertThrows(IOException.class, () -> parse(MODEL_WITH_OPERATION_METADATA
                .replace("\"classification\":\"CONTRACT\"", "\"classification\":\"WHATEVER\"")));
        assertTrue(error.getMessage().contains("Model schema validation failed"));
    }

    @Test
    void operationWithNoMetadataCompilesWithEmptyDefaults() throws Exception {
        ModelAst ast = parse("""
                {
                  "namespace":"demo",
                  "dslVersion":"1.0.0",
                  "version":"v1",
                  "concepts":[
                    {"name":"Widget","fields":[{"name":"id","type":"uuid","id":true}]}
                  ],
                  "capabilities":[
                    {"name":"persistence","type":"PersistenceCapability","operations":["save"]}
                  ],
                  "bindings":[
                    {"capability":"persistence","adapter":"inmemory"}
                  ]
                }
                """);
        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledCapabilityOperation operation = operationNamed(compiled, "save");
        assertTrue(operation.getErrors().isEmpty());
        assertEquals(null, operation.getSideEffects());
        assertEquals(null, operation.getAuth());
    }

    private static void assertOperationMetadata(CompiledCapabilityOperation operation) {
        assertEquals("writes", operation.getSideEffects());
        assertEquals(2, operation.getErrors().size());
        assertEquals("DuplicateSku", operation.getErrors().get(0).getName());
        assertEquals("CONTRACT", operation.getErrors().get(0).getClassification());
        assertEquals("sku already exists", operation.getErrors().get(0).getDescription());
        assertEquals("StoreUnavailable", operation.getErrors().get(1).getName());
        assertEquals("transient", operation.getErrors().get(1).getClassification());
        assertEquals(List.of("order-writer"), operation.getAuth().getRoles());
        assertEquals(List.of("orders:write"), operation.getAuth().getScopes());
    }

    private static CompiledCapabilityOperation operationNamed(CompiledModel model, String name) {
        return model.getCapabilities().stream()
                .flatMap(capability -> capability.getOperations().stream())
                .filter(operation -> name.equals(operation.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("operation '" + name + "' not found"));
    }

    private static ModelAst parse(String json) throws Exception {
        Path model = Files.createTempFile("npdev-cap-operation-metadata-", ".json");
        Files.writeString(model, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(model);
    }
}
