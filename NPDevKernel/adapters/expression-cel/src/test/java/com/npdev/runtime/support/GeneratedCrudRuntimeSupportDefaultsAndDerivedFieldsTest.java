package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledEntity;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.InvariantEngine;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GeneratedCrudRuntimeSupportDefaultsAndDerivedFieldsTest {

    @Test
    void buildCreatePayloadAndApplyFieldsHonorDeterministicValueBehaviorOrder() {
        CompiledEntity patient = new CompiledEntity(
                "Patient",
                "Patient",
                "patients",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField(
                                "firstName",
                                "string",
                                "String",
                                false,
                                true,
                                false,
                                List.of(),
                                null,
                                null,
                                null,
                                new CompiledSchema("string", Map.of(), null, List.of(), List.of(), null, null, null, "", null, null, null, null, null),
                                List.of()
                        ),
                        new CompiledField(
                                "lastName",
                                "string",
                                "String",
                                false,
                                true,
                                false,
                                List.of(),
                                null,
                                null,
                                null,
                                new CompiledSchema("string", Map.of(), null, List.of(), List.of(), null, null, null, "", null, null, null, null, null),
                                List.of()
                        ),
                        new CompiledField(
                                "preferredLanguage",
                                "string",
                                "String",
                                false,
                                false,
                                false,
                                List.of(),
                                null,
                                null,
                                null,
                                new CompiledSchema("string", Map.of(), null, List.of(), List.of(), "en-US", null, null, "", null, null, null, null, null),
                                List.of()
                        ),
                        new CompiledField(
                                "reminderLanguage",
                                "string",
                                "String",
                                false,
                                false,
                                false,
                                List.of(),
                                null,
                                null,
                                null,
                                new CompiledSchema("string", Map.of(), null, List.of(), List.of(), null, "preferredLanguage", null, "", null, null, null, null, null),
                                List.of()
                        ),
                        new CompiledField(
                                "chartLabel",
                                "string",
                                "String",
                                false,
                                false,
                                false,
                                List.of(),
                                null,
                                null,
                                null,
                                new CompiledSchema("string", Map.of(), null, List.of(), List.of(), null, null, "concat(lastName, ', ', firstName)", "", null, null, null, null, null),
                                List.of()
                        )
                ),
                List.of(),
                List.of(),
                null
        );

        Map<String, CompiledEntity> entities = new LinkedHashMap<>();
        entities.put(patient.getName(), patient);
        CompiledModel compiledModel = new CompiledModel("demo", "1.0.0", "v1", entities);
        KernelRunner kernelRunner = new KernelRunner((EventBus) event -> {
        }, new InvariantEngine() {
            @Override
            public List<String> evaluate(String entityName, Object payload) {
                return List.of();
            }
        });
        GeneratedCrudRuntimeSupport support = new GeneratedCrudRuntimeSupport(compiledModel, kernelRunner);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("firstName", "Ana");
        input.put("lastName", "Silva");
        input.put("chartLabel", "user supplied");

        Map<String, Object> payload = support.buildCreateInvariantPayload("Patient", input);
        assertEquals("en-US", payload.get("preferredLanguage"));
        assertEquals("en-US", payload.get("reminderLanguage"));
        assertEquals("Silva, Ana", payload.get("chartLabel"));
        UUID generatedId = support.ensureGeneratedId(payload);
        assertNotNull(generatedId);
        assertEquals(generatedId, payload.get("id"));

        PatientRecord record = new PatientRecord();
        support.applyCreateFields("Patient", input, record);
        assertEquals("Ana", record.getFirstName());
        assertEquals("Silva", record.getLastName());
        assertEquals("en-US", record.getPreferredLanguage());
        assertEquals("en-US", record.getReminderLanguage());
        assertEquals("Silva, Ana", record.getChartLabel());
        assertNull(record.getId());
    }

    @Test
    void applyCreateFieldsCoercesNestedJsonPayloadsIntoJsonNodeTargets() {
        CompiledEntity patient = new CompiledEntity(
                "Patient",
                "Patient",
                "patients",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField(
                                "insurance",
                                "object",
                                "com.fasterxml.jackson.databind.JsonNode",
                                false,
                                false,
                                false
                        ),
                        new CompiledField(
                                "allergies",
                                "array",
                                "com.fasterxml.jackson.databind.JsonNode",
                                false,
                                false,
                                false
                        )
                ),
                List.of(),
                List.of(),
                null
        );

        Map<String, CompiledEntity> entities = new LinkedHashMap<>();
        entities.put(patient.getName(), patient);
        CompiledModel compiledModel = new CompiledModel("demo", "1.0.0", "v1", entities);
        KernelRunner kernelRunner = new KernelRunner((EventBus) event -> { }, (entityName, payload) -> List.of());
        GeneratedCrudRuntimeSupport support = new GeneratedCrudRuntimeSupport(compiledModel, kernelRunner);

        NestedJsonRecord record = new NestedJsonRecord();
        support.applyCreateFields("Patient", Map.of(
                "insurance", Map.of("payerName", "Blue Shield"),
                "allergies", List.of(Map.of("allergen", "Latex"))
        ), record);

        assertEquals("Blue Shield", record.getInsurance().path("payerName").asText());
        assertEquals("Latex", record.getAllergies().path(0).path("allergen").asText());
    }

    @Test
    void applyCreateFieldsCoercesUuidAndReferenceStringsIntoUuidTargets() {
        CompiledEntity staffMember = new CompiledEntity(
                "StaffMember",
                "StaffMember",
                "staffmembers",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("tenantId", "reference", "java.util.UUID", false, true, false),
                        new CompiledField("fullName", "string", "String", false, true, false)
                ),
                List.of(),
                List.of(),
                null
        );

        Map<String, CompiledEntity> entities = new LinkedHashMap<>();
        entities.put(staffMember.getName(), staffMember);
        CompiledModel compiledModel = new CompiledModel("demo", "1.0.0", "v1", entities);
        KernelRunner kernelRunner = new KernelRunner((EventBus) event -> { }, (entityName, payload) -> List.of());
        GeneratedCrudRuntimeSupport support = new GeneratedCrudRuntimeSupport(compiledModel, kernelRunner);

        UUID tenantId = UUID.randomUUID();
        ReferenceRecord record = new ReferenceRecord();
        support.applyCreateFields("StaffMember", Map.of(
                "tenantId", tenantId.toString(),
                "fullName", "Maria Pizza"
        ), record);

        assertEquals(tenantId, record.getTenantId());
        assertEquals("Maria Pizza", record.getFullName());
    }

    private static final class PatientRecord {
        private UUID id;
        private String firstName;
        private String lastName;
        private String preferredLanguage;
        private String reminderLanguage;
        private String chartLabel;

        public UUID getId() {
            return id;
        }

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public void setLastName(String lastName) {
            this.lastName = lastName;
        }

        public String getPreferredLanguage() {
            return preferredLanguage;
        }

        public void setPreferredLanguage(String preferredLanguage) {
            this.preferredLanguage = preferredLanguage;
        }

        public String getReminderLanguage() {
            return reminderLanguage;
        }

        public void setReminderLanguage(String reminderLanguage) {
            this.reminderLanguage = reminderLanguage;
        }

        public String getChartLabel() {
            return chartLabel;
        }

        public void setChartLabel(String chartLabel) {
            this.chartLabel = chartLabel;
        }
    }

    private static final class NestedJsonRecord {
        private JsonNode insurance;
        private JsonNode allergies;

        public JsonNode getInsurance() {
            return insurance;
        }

        public void setInsurance(JsonNode insurance) {
            this.insurance = insurance;
        }

        public JsonNode getAllergies() {
            return allergies;
        }

        public void setAllergies(JsonNode allergies) {
            this.allergies = allergies;
        }
    }

    private static final class ReferenceRecord {
        private UUID tenantId;
        private String fullName;

        public UUID getTenantId() {
            return tenantId;
        }

        public void setTenantId(UUID tenantId) {
            this.tenantId = tenantId;
        }

        public String getFullName() {
            return fullName;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }
    }
}
