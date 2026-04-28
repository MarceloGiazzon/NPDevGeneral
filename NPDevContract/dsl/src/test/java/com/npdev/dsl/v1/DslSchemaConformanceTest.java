package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DslSchemaConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void schemaAcceptsGoldenModelFixture() throws Exception {
        JsonSchema schema = schema();
        JsonNode model = MAPPER.readTree(Files.readString(resolveGoldenModel()));
        Set<ValidationMessage> violations = schema.validate(model);
        assertTrue(violations.isEmpty(), "Expected schema-valid golden model, got: " + violations);
    }

    @Test
    void schemaAcceptsCustomCapabilitiesAlias() throws Exception {
        JsonSchema schema = schema();
        JsonNode model = MAPPER.readTree("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "Appointment",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ],
                  "customCapabilities": [
                    {
                      "name": "insurancePortal",
                      "type": "ExternalCapability",
                      "operations": [
                        {
                          "name": "submitPreauth",
                          "input": { "conceptRef": "Appointment" },
                          "output": { "schemaRef": "InsurancePreauthResult" }
                        }
                      ]
                    }
                  ]
                }
                """);
        Set<ValidationMessage> violations = schema.validate(model);
        assertTrue(violations.isEmpty(), "Expected schema-valid customCapabilities alias, got: " + violations);
    }

    @Test
    void schemaAcceptsDomainTypesAndFieldReferences() throws Exception {
        JsonSchema schema = schema();
        JsonNode model = MAPPER.readTree("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "domainTypes": [
                    {
                      "name": "MRN",
                      "baseType": "string",
                      "validation": {
                        "type": "string",
                        "minLength": 8,
                        "maxLength": 12,
                        "regex": "^[A-Z0-9-]+$"
                      },
                      "normalization": ["trim", "uppercase"],
                      "format": "medical-record-number",
                      "examples": ["MRN-000123"],
                      "ui": {
                        "label": "Medical record number",
                        "widget": "text"
                      }
                    }
                  ],
                  "concepts": [
                    {
                      "name": "Patient",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "mrn", "type": "string", "domainType": "MRN" }
                      ]
                    }
                  ]
                }
                """);
        Set<ValidationMessage> violations = schema.validate(model);
        assertTrue(violations.isEmpty(), "Expected schema-valid domainTypes support, got: " + violations);
    }

    @Test
    void schemaAcceptsEnrichedEnumMetadataObjects() throws Exception {
        JsonSchema schema = schema();
        JsonNode model = MAPPER.readTree("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "Appointment",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "status",
                          "type": "enum",
                          "enumValues": [
                            {
                              "value": "Scheduled",
                              "label": "Scheduled",
                              "order": 10,
                              "group": "Active",
                              "default": true,
                              "badge": "info",
                              "description": "Initial state"
                            },
                            {
                              "value": "Completed",
                              "label": "Completed",
                              "order": 20,
                              "group": "Terminal",
                              "badgeHint": "success"
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);
        Set<ValidationMessage> violations = schema.validate(model);
        assertTrue(violations.isEmpty(), "Expected schema-valid enriched enum metadata, got: " + violations);
    }

    @Test
    void schemaAcceptsEnrichedReferenceDefinitions() throws Exception {
        JsonSchema schema = schema();
        JsonNode model = MAPPER.readTree("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "Patient",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "mrn", "type": "string" },
                        { "name": "lastName", "type": "string" }
                      ]
                    },
                    {
                      "name": "Appointment",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "patientId",
                          "type": "reference",
                          "required": true,
                          "reference": {
                            "target": "Patient",
                            "displayField": "lastName",
                            "displayTemplate": "{{lastName}}, {{firstName}} ({{mrn}})",
                            "lookupFields": ["mrn", "lastName"],
                            "pickerColumns": ["mrn", "lastName"],
                            "previewFields": ["mrn", "lastName"],
                            "previewCardTemplate": "{{lastName}} | {{mrn}}",
                            "defaultFilter": "recent-patients",
                            "inlineCreate": "allow"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);
        Set<ValidationMessage> violations = schema.validate(model);
        assertTrue(violations.isEmpty(), "Expected schema-valid enriched reference metadata, got: " + violations);
    }

    @Test
    void schemaAcceptsNestedObjectAndRepeatedSectionDefinitions() throws Exception {
        JsonSchema schema = schema();
        JsonNode model = MAPPER.readTree("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "Patient",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "emergencyContact",
                          "type": "object",
                          "properties": {
                            "name": { "type": "string" },
                            "phone": { "type": "string" }
                          },
                          "required": ["name", "phone"]
                        },
                        {
                          "name": "allergies",
                          "type": "array",
                          "minItems": 0,
                          "maxItems": 20,
                          "itemIdentityField": "code",
                          "duplicationPolicy": "deny",
                          "items": {
                            "type": "object",
                            "required": ["code", "substance"],
                            "properties": {
                              "code": { "type": "string" },
                              "substance": { "type": "string" },
                              "active": { "type": "boolean" }
                            }
                          }
                        }
                      ]
                    }
                  ]
                }
                """);
        Set<ValidationMessage> violations = schema.validate(model);
        assertTrue(violations.isEmpty(), "Expected schema-valid nested object and repeated section metadata, got: " + violations);
    }

    @Test
    void schemaAcceptsDefaultsDynamicDefaultsAndDerivedExpressions() throws Exception {
        JsonSchema schema = schema();
        JsonNode model = MAPPER.readTree("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "Patient",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "preferredLanguage", "type": "string", "default": "en-US" },
                        { "name": "reminderLanguage", "type": "string", "defaultExpression": "preferredLanguage" },
                        { "name": "chartLabel", "type": "string", "derivedExpression": "concat(lastName, ', ', firstName)" },
                        { "name": "lastName", "type": "string" },
                        { "name": "firstName", "type": "string" }
                      ]
                    }
                  ]
                }
                """);
        Set<ValidationMessage> violations = schema.validate(model);
        assertTrue(violations.isEmpty(), "Expected schema-valid defaults/derived metadata, got: " + violations);
    }

    @Test
    void schemaAcceptsConceptAndFieldPresentationMetadata() throws Exception {
        JsonSchema schema = schema();
        JsonNode model = MAPPER.readTree("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "Patient",
                      "ui": {
                        "label": "Patient",
                        "shortLabel": "Pt",
                        "description": "Patient profile",
                        "group": "Clinical operations",
                        "section": "Registration",
                        "order": 10,
                        "examples": ["New patient intake"]
                      },
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "firstName",
                          "type": "string",
                          "ui": {
                            "label": "First name",
                            "shortLabel": "First",
                            "description": "Given name",
                            "helpText": "Use the patient's preferred given name",
                            "placeholder": "Marina",
                            "group": "Identity",
                            "section": "Registration",
                            "order": 20,
                            "advanced": false,
                            "deprecated": false,
                            "examples": ["Marina"],
                            "widget": "text"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);
        Set<ValidationMessage> violations = schema.validate(model);
        assertTrue(violations.isEmpty(), "Expected schema-valid presentation metadata, got: " + violations);
    }

    @Test
    void schemaAcceptsInteractionMetadata() throws Exception {
        JsonSchema schema = schema();
        JsonNode model = MAPPER.readTree("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "Patient",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "status", "type": "enum", "enumValues": ["Draft", "Active"] },
                        {
                          "name": "providerId",
                          "type": "reference",
                          "reference": { "target": "Provider" },
                          "ui": {
                            "label": "Provider",
                            "visibleWhen": "status == 'Active'",
                            "enabledWhen": "status != 'Draft'",
                            "readonlyWhen": "status == 'Draft'",
                            "requiredWhen": "status == 'Active'",
                            "pickerType": "search-dialog",
                            "allowInlineCreate": false,
                            "searchFields": ["fullName"],
                            "filterPreset": "available-providers"
                          }
                        }
                      ]
                    },
                    {
                      "name": "Provider",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "fullName", "type": "string" }
                      ]
                    }
                  ]
                }
                """);
        Set<ValidationMessage> violations = schema.validate(model);
        assertTrue(violations.isEmpty(), "Expected schema-valid interaction metadata, got: " + violations);
    }

    @Test
    void schemaAcceptsLayoutMetadata() throws Exception {
        JsonSchema schema = schema();
        JsonNode model = MAPPER.readTree("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "Appointment",
                      "ui": {
                        "label": "Appointment",
                        "formColumns": 2,
                        "displayMode": "standard",
                        "defaultSort": "-scheduledAt",
                        "defaultGroup": "status"
                      },
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "status", "type": "enum", "enumValues": ["Scheduled", "Completed"], "ui": { "label": "Status" } },
                        {
                          "name": "scheduledAt",
                          "type": "datetime",
                          "ui": {
                            "label": "Scheduled at",
                            "tab": "Overview",
                            "column": 1,
                            "columnSpan": 1,
                            "width": "md",
                            "summaryCard": true,
                            "listColumn": true,
                            "listColumnOrder": 10
                          }
                        }
                      ]
                    }
                  ]
                }
                """);
        Set<ValidationMessage> violations = schema.validate(model);
        assertTrue(violations.isEmpty(), "Expected schema-valid layout metadata, got: " + violations);
    }

    @Test
    void schemaAcceptsActionMetadata() throws Exception {
        JsonSchema schema = schema();
        JsonNode model = MAPPER.readTree("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "Appointment",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "status", "type": "enum", "required": true, "enumValues": ["Scheduled", "Completed", "Cancelled"] }
                      ],
                      "lifecycle": {
                        "statusField": "status",
                        "transitions": [
                          {
                            "from": "Scheduled",
                            "to": "Cancelled",
                            "actionLabel": "Cancel",
                            "action": {
                              "label": "Cancel appointment",
                              "confirmationText": "Cancel this appointment?",
                              "successMessage": "Appointment cancelled.",
                              "failureHint": "Only scheduled appointments can be cancelled.",
                              "dangerLevel": "high",
                              "visibleWhen": "status == 'Scheduled'",
                              "permissionHint": "appointments.cancel",
                              "inputFormHint": "appointment-cancel"
                            }
                          }
                        ]
                      }
                    }
                  ],
                  "events": [
                    {
                      "name": "AppointmentCompleted",
                      "payload": [
                        { "name": "appointmentId", "type": "uuid" },
                        { "name": "status", "type": "string" }
                      ]
                    }
                  ],
                  "orchestrationRules": [
                    {
                      "name": "NotifyCompletion",
                      "trigger": { "type": "event", "event": "AppointmentCompleted" },
                      "actions": [
                        {
                          "type": "scheduleEvent",
                          "event": "AppointmentCompleted",
                          "delayMinutes": 5,
                          "action": {
                            "label": "Queue completion notification",
                            "successMessage": "Notification queued.",
                            "dangerLevel": "low",
                            "permissionHint": "notifications.send",
                            "inputFormHint": "notification-send"
                          },
                          "map": {
                            "appointmentId": "$event.appointmentId"
                          }
                        }
                      ]
                    }
                  ],
                  "flows": [
                    {
                      "name": "CreateAppointment",
                      "input": { "concept": "Appointment", "mode": "create" },
                      "action": {
                        "label": "Create appointment",
                        "confirmationText": "Create this appointment?",
                        "successMessage": "Appointment created.",
                        "failureHint": "Provide the required fields.",
                        "dangerLevel": "low",
                        "permissionHint": "appointments.create",
                        "inputFormHint": "appointment-create"
                      },
                      "steps": [
                        {
                          "name": "save",
                          "type": "createConcept",
                          "scope": "Appointment",
                          "input": "$input",
                          "out": "$saved",
                          "action": {
                            "label": "Persist appointment",
                            "successMessage": "Appointment persisted.",
                            "dangerLevel": "low",
                            "permissionHint": "appointments.create",
                            "inputFormHint": "appointment-create"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);
        Set<ValidationMessage> violations = schema.validate(model);
        assertTrue(violations.isEmpty(), "Expected schema-valid action metadata, got: " + violations);
    }

    @Test
    void schemaRejectsUnknownRootProperties() throws Exception {
        JsonSchema schema = schema();
        JsonNode model = MAPPER.readTree("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "unexpectedRootKey": "boom",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """);
        Set<ValidationMessage> violations = schema.validate(model);
        assertFalse(violations.isEmpty(), "Expected schema to reject unknown root key");
    }

    @Test
    void schemaRejectsMissingDslVersion() throws Exception {
        JsonSchema schema = schema();
        JsonNode model = MAPPER.readTree("""
                {
                  "namespace": "demo",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """);
        Set<ValidationMessage> violations = schema.validate(model);
        assertFalse(violations.isEmpty(), "Expected schema to reject model missing dslVersion");
    }

    private static JsonSchema schema() throws Exception {
        String schemaJson = Files.readString(resolveSchemaPath());
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        return factory.getSchema(schemaJson);
    }

    private static Path resolveSchemaPath() {
        List<Path> candidates = List.of(
                Path.of("resources", "Schemas", "model-1.0.0.schema.json"),
                Path.of("src", "main", "resources", "schema", "model.schema.json"),
                Path.of("Project", "GPT", "dsl", "src", "main", "resources", "schema", "model.schema.json")
        );
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("Unable to resolve DSL schema file path.");
    }

    private static Path resolveGoldenModel() {
        List<Path> candidates = List.of(
                Path.of("..", "tests", "golden", "phase0", "model.json"),
                Path.of("Project", "GPT", "tests", "golden", "phase0", "model.json")
        );
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("Unable to resolve golden model fixture path.");
    }
}

