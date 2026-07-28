package com.npdev.dsl.v1.resolution;

import com.npdev.dsl.v1.ast.EventAst;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.StepAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelResolverSpecializationTest {

    @Test
    void resolvesValidSpecializationGoldenModel() throws Exception {
        ModelAst source = parseResource("specialization/valid-specialization.json");

        ResolvedModel resolved = new ModelResolver().resolve(source);

        assertNotNull(resolved.modelAst());
        assertFalse(resolved.modelAst().getConcepts().isEmpty());
        assertTrue(resolved.deterministicHashSha256().matches("^[a-f0-9]{64}$"));

        FlowAst flow = resolved.modelAst().getFlows().stream()
                .filter(candidate -> "SubmitMedicalInvoice".equals(candidate.getName()))
                .findFirst()
                .orElseThrow();

        List<String> stepNames = flow.getSteps().stream()
                .map(StepAst::getName)
                .toList();
        assertEquals(List.of("pre-check", "emit-medical", "return-base"), stepNames);

        EventAst event = resolved.modelAst().getEvents().stream()
                .filter(candidate -> "MedicalInvoiceCreated".equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals("2.0", event.getVersion());
    }

    @Test
    void rejectsInvalidSpecializationGoldenModelWithVersionRequired() throws Exception {
        ModelAst source = parseResource("specialization/invalid-specialization.json");

        ModelResolutionException exception = assertThrows(
                ModelResolutionException.class,
                () -> new ModelResolver().resolve(source)
        );

        assertEquals(ResolutionDiagnosticCode.VERSION_REQUIRED, exception.getCode());
        assertTrue(exception.getMessage().startsWith("VERSION_REQUIRED:"));
    }

    @Test
    void emitsStableDiagnosticCodes() throws Exception {
        assertDiagnosticCode(
                """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "ChildEntity",
                      "specializes": "MissingEntity",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """,
                ResolutionDiagnosticCode.BASE_NOT_FOUND
        );

        assertDiagnosticCode(
                """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "BaseEntity",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "code", "type": "string", "required": true }
                      ]
                    },
                    {
                      "name": "DerivedEntity",
                      "specializes": "BaseEntity",
                      "fields": [
                        { "name": "code", "type": "long", "required": true }
                      ]
                    }
                  ]
                }
                """,
                ResolutionDiagnosticCode.ILLEGAL_OVERRIDE
        );

        assertDiagnosticCode(
                """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "DummyEntity",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ],
                  "capabilities": [
                    { "name": "mail", "type": "NotificationCapability", "operations": [] },
                    { "name": "mail", "type": "NotificationCapability", "operations": [] }
                  ]
                }
                """,
                ResolutionDiagnosticCode.CONFLICT_DUPLICATE_MEMBER
        );
    }

    @Test
    void deterministicHashMatchesForEquivalentModels() throws Exception {
        ResolvedModel resolvedA = new ModelResolver().resolve(parseJson(equivalentModelA()));
        ResolvedModel resolvedB = new ModelResolver().resolve(parseJson(equivalentModelB()));

        assertEquals(resolvedA.canonicalJson(), resolvedB.canonicalJson());
        assertEquals(resolvedA.deterministicHashSha256(), resolvedB.deterministicHashSha256());
    }

    private static void assertDiagnosticCode(String json, ResolutionDiagnosticCode expectedCode) throws Exception {
        ModelAst source = parseJson(json);
        ModelResolutionException exception = assertThrows(
                ModelResolutionException.class,
                () -> new ModelResolver().resolve(source)
        );
        assertEquals(expectedCode, exception.getCode());
        assertTrue(exception.getMessage().startsWith(expectedCode.name() + ":"));
    }

    private static ModelAst parseResource(String resourcePath) throws Exception {
        InputStream stream = ModelResolverSpecializationTest.class.getClassLoader().getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IllegalStateException("Test resource not found: " + resourcePath);
        }
        try (InputStream inputStream = stream) {
            String json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return parseJson(json);
        }
    }

    private static ModelAst parseJson(String json) throws Exception {
        Path temp = Files.createTempFile("npdev-model-resolver-", ".json");
        Files.writeString(temp, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(temp);
    }

    private static String equivalentModelA() {
        return """
                {
                  "namespace": "determinism",
                  "dslVersion": "1.0.0",
                  "version": "v2",
                  "concepts": [
                    {
                      "name": "Invoice",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "code", "type": "string", "required": true }
                      ]
                    },
                    {
                      "name": "MedicalInvoice",
                      "specializes": "Invoice",
                      "fields": [
                        { "name": "doctorId", "type": "uuid", "required": true }
                      ]
                    }
                  ],
                  "capabilities": [
                    {
                      "name": "persistence",
                      "type": "PersistenceCapability",
                      "operations": [
                        { "name": "save", "input": { "type": "object", "properties": { "entity": { "type": "object", "properties": {} } }, "required": ["entity"] }, "output": { "type": "object", "properties": { "entity": { "type": "object", "properties": {} } }, "required": ["entity"] } }
                      ]
                    },
                    {
                      "name": "medicalPersistence",
                      "specializes": "persistence",
                      "operations": [
                        { "name": "saveMedical", "input": { "type": "object", "properties": { "entity": { "type": "object", "properties": {} } }, "required": ["entity"] }, "output": { "type": "object", "properties": { "entity": { "type": "object", "properties": {} } }, "required": ["entity"] } }
                      ]
                    }
                  ],
                  "bindings": [
                    { "capability": "medicalPersistence", "adapter": "inproc" },
                    { "capability": "persistence", "adapter": "inproc" }
                  ],
                  "events": [
                    {
                      "name": "InvoiceCreated",
                      "version": "1.0",
                      "payload": [
                        { "name": "code", "type": "string" }
                      ]
                    },
                    {
                      "name": "MedicalInvoiceCreated",
                      "specializes": "InvoiceCreated",
                      "version": "2.0",
                      "payload": [
                        { "name": "code", "type": "string" },
                        { "name": "doctorId", "type": "uuid" }
                      ]
                    }
                  ],
                  "flows": [
                    {
                      "name": "SubmitInvoice",
                      "concept": "Invoice",
                      "steps": [
                        { "name": "pre-check", "type": "invariantCheck", "scope": "Invoice" },
                        { "name": "return-base", "type": "return", "value": "$input" }
                      ]
                    },
                    {
                      "name": "SubmitMedicalInvoice",
                      "specializes": "SubmitInvoice",
                      "hooks": [
                        {
                          "position": "before",
                          "targetStep": "return-base",
                          "steps": [
                            {
                              "name": "emit-medical",
                              "type": "emitEvent",
                              "event": "MedicalInvoiceCreated",
                              "payload": "$input"
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;
    }

    private static String equivalentModelB() {
        return """
                {
                  "namespace": "determinism",
                  "dslVersion": "1.0.0",
                  "version": "v2",
                  "flows": [
                    {
                      "name": "SubmitMedicalInvoice",
                      "specializes": "SubmitInvoice",
                      "hooks": [
                        {
                          "position": "before",
                          "targetStep": "return-base",
                          "steps": [
                            {
                              "name": "emit-medical",
                              "type": "emitEvent",
                              "event": "MedicalInvoiceCreated",
                              "payload": "$input"
                            }
                          ]
                        }
                      ]
                    },
                    {
                      "name": "SubmitInvoice",
                      "concept": "Invoice",
                      "steps": [
                        { "name": "pre-check", "type": "invariantCheck", "scope": "Invoice" },
                        { "name": "return-base", "type": "return", "value": "$input" }
                      ]
                    }
                  ],
                  "events": [
                    {
                      "name": "MedicalInvoiceCreated",
                      "specializes": "InvoiceCreated",
                      "version": "2.0",
                      "payload": [
                        { "name": "doctorId", "type": "uuid" },
                        { "name": "code", "type": "string" }
                      ]
                    },
                    {
                      "name": "InvoiceCreated",
                      "version": "1.0",
                      "payload": [
                        { "name": "code", "type": "string" }
                      ]
                    }
                  ],
                  "bindings": [
                    { "capability": "persistence", "adapter": "inproc" },
                    { "capability": "medicalPersistence", "adapter": "inproc" }
                  ],
                  "capabilities": [
                    {
                      "name": "medicalPersistence",
                      "specializes": "persistence",
                      "operations": [
                        { "name": "saveMedical", "input": { "type": "object", "properties": { "entity": { "type": "object", "properties": {} } }, "required": ["entity"] }, "output": { "type": "object", "properties": { "entity": { "type": "object", "properties": {} } }, "required": ["entity"] } }
                      ]
                    },
                    {
                      "name": "persistence",
                      "type": "PersistenceCapability",
                      "operations": [
                        { "name": "save", "input": { "type": "object", "properties": { "entity": { "type": "object", "properties": {} } }, "required": ["entity"] }, "output": { "type": "object", "properties": { "entity": { "type": "object", "properties": {} } }, "required": ["entity"] } }
                      ]
                    }
                  ],
                  "concepts": [
                    {
                      "name": "MedicalInvoice",
                      "specializes": "Invoice",
                      "fields": [
                        { "name": "doctorId", "type": "uuid", "required": true }
                      ]
                    },
                    {
                      "name": "Invoice",
                      "fields": [
                        { "name": "code", "type": "string", "required": true },
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """;
    }
}



