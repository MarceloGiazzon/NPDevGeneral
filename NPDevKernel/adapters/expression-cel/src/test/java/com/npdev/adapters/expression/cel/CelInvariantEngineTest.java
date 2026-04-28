package com.npdev.adapters.expression.cel;

import com.npdev.dsl.v1.compiled.CompiledEntity;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CelInvariantEngineTest {

    @Test
    void evaluatesRequiredFieldsFromCompiledModel() {
        CelInvariantEngine engine = CelInvariantEngine.fromCompiledModel(userModel());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "u-1");
        payload.put("email", " ");

        List<String> violations = engine.evaluate("User", payload);

        assertEquals(2, violations.size());
        assertTrue(violations.stream().anyMatch(v -> v.contains("required field 'name'")));
        assertTrue(violations.stream().anyMatch(v -> v.contains("required field 'email'")));
    }

    @Test
    void evaluatesUniqueViaCallback() {
        CelInvariantEngine engine = CelInvariantEngine.fromCompiledModel(
                userModel(),
                (entity, field, value, payload) ->
                        "User".equals(entity) && "email".equals(field) && "taken@acme.com".equals(value)
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "u-2");
        payload.put("name", "Ana");
        payload.put("email", "taken@acme.com");

        List<String> violations = engine.evaluate("User", payload);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("unique constraint violated"));
    }

    @Test
    void returnsNoViolationsForValidPayload() {
        CelInvariantEngine engine = CelInvariantEngine.fromCompiledModel(
                userModel(),
                (entity, field, value, payload) -> false
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "u-3");
        payload.put("name", "Leo");
        payload.put("email", "leo@acme.com");

        List<String> violations = engine.evaluate("User", payload);

        assertTrue(violations.isEmpty());
    }

    @Test
    void evaluatesExpressionInvariantComparisonsAndRegex() {
        Map<String, CelInvariantEngine.EntityRules> rules = new LinkedHashMap<>();
        rules.put("User", new CelInvariantEngine.EntityRules(
                java.util.Set.of(),
                java.util.Set.of(),
                List.of("age > 18", "email.matches(\".+@.+\")")
        ));
        CelInvariantEngine engine = new CelInvariantEngine(rules);

        Map<String, Object> badPayload = new LinkedHashMap<>();
        badPayload.put("age", 17);
        badPayload.put("email", "invalid");
        List<String> badViolations = engine.evaluate("User", badPayload);
        assertEquals(2, badViolations.size());
        assertTrue(badViolations.stream().anyMatch(v -> v.contains("age > 18")));
        assertTrue(badViolations.stream().anyMatch(v -> v.contains("email.matches")));

        Map<String, Object> goodPayload = new LinkedHashMap<>();
        goodPayload.put("age", 21);
        goodPayload.put("email", "ok@npdev.local");
        List<String> goodViolations = engine.evaluate("User", goodPayload);
        assertTrue(goodViolations.isEmpty());
    }

    @Test
    void evaluatesExpressionInvariantUniqueByForArrayItems() {
        Map<String, CelInvariantEngine.EntityRules> rules = new LinkedHashMap<>();
        rules.put("Patient", new CelInvariantEngine.EntityRules(
                java.util.Set.of(),
                java.util.Set.of(),
                List.of("allergies.uniqueBy(allergen)")
        ));
        CelInvariantEngine engine = new CelInvariantEngine(rules);

        Map<String, Object> duplicatePayload = new LinkedHashMap<>();
        duplicatePayload.put("allergies", List.of(
                Map.of("allergen", "Peanuts"),
                Map.of("allergen", "Peanuts")
        ));
        List<String> duplicateViolations = engine.evaluate("Patient", duplicatePayload);
        assertEquals(1, duplicateViolations.size());
        assertTrue(duplicateViolations.get(0).contains("allergies.uniqueBy(allergen)"));

        Map<String, Object> distinctPayload = new LinkedHashMap<>();
        distinctPayload.put("allergies", List.of(
                Map.of("allergen", "Peanuts"),
                Map.of("allergen", "Latex")
        ));
        List<String> distinctViolations = engine.evaluate("Patient", distinctPayload);
        assertTrue(distinctViolations.isEmpty());
    }

    @Test
    void evaluatesExpressionInvariantAllForArrayItems() {
        Map<String, CelInvariantEngine.EntityRules> rules = new LinkedHashMap<>();
        rules.put("Patient", new CelInvariantEngine.EntityRules(
                java.util.Set.of(),
                java.util.Set.of(),
                List.of("allergies.all(a => a.allergen != null)")
        ));
        CelInvariantEngine engine = new CelInvariantEngine(rules);

        Map<String, Object> invalidPayload = new LinkedHashMap<>();
        invalidPayload.put("allergies", List.of(
                Map.of("allergen", "Peanuts"),
                Map.of("reaction", "Rash")
        ));
        List<String> invalidViolations = engine.evaluate("Patient", invalidPayload);
        assertEquals(1, invalidViolations.size());
        assertTrue(invalidViolations.get(0).contains("allergies.all"));

        Map<String, Object> validPayload = new LinkedHashMap<>();
        validPayload.put("allergies", List.of(
                Map.of("allergen", "Peanuts"),
                Map.of("allergen", "Latex")
        ));
        List<String> validViolations = engine.evaluate("Patient", validPayload);
        assertTrue(validViolations.isEmpty());
    }

    @Test
    void evaluatesExpressionInvariantExistsForArrayItems() {
        Map<String, CelInvariantEngine.EntityRules> rules = new LinkedHashMap<>();
        rules.put("Patient", new CelInvariantEngine.EntityRules(
                java.util.Set.of(),
                java.util.Set.of(),
                List.of("allergies.exists(a => a.severity == \"Severe\")")
        ));
        CelInvariantEngine engine = new CelInvariantEngine(rules);

        Map<String, Object> invalidPayload = new LinkedHashMap<>();
        invalidPayload.put("allergies", List.of(
                Map.of("allergen", "Peanuts", "severity", "Mild"),
                Map.of("allergen", "Latex", "severity", "Moderate")
        ));
        List<String> invalidViolations = engine.evaluate("Patient", invalidPayload);
        assertEquals(1, invalidViolations.size());
        assertTrue(invalidViolations.get(0).contains("allergies.exists"));

        Map<String, Object> validPayload = new LinkedHashMap<>();
        validPayload.put("allergies", List.of(
                Map.of("allergen", "Peanuts", "severity", "Severe")
        ));
        List<String> validViolations = engine.evaluate("Patient", validPayload);
        assertTrue(validViolations.isEmpty());
    }

    @Test
    void evaluatesNestedPathComparisonExpression() {
        Map<String, CelInvariantEngine.EntityRules> rules = new LinkedHashMap<>();
        rules.put("Patient", new CelInvariantEngine.EntityRules(
                java.util.Set.of(),
                java.util.Set.of(),
                List.of("emergencyContact.phone != null")
        ));
        CelInvariantEngine engine = new CelInvariantEngine(rules);

        Map<String, Object> invalidPayload = new LinkedHashMap<>();
        invalidPayload.put("emergencyContact", Map.of("name", "Jane"));
        List<String> invalidViolations = engine.evaluate("Patient", invalidPayload);
        assertEquals(1, invalidViolations.size());

        Map<String, Object> validPayload = new LinkedHashMap<>();
        validPayload.put("emergencyContact", Map.of("name", "Jane", "phone", "555-0101"));
        List<String> validViolations = engine.evaluate("Patient", validPayload);
        assertTrue(validViolations.isEmpty());
    }

    @Test
    void evaluateRequestIncludesFieldPathForUniqueByViolation() {
        Map<String, CelInvariantEngine.EntityRules> rules = new LinkedHashMap<>();
        rules.put("Patient", new CelInvariantEngine.EntityRules(
                java.util.Set.of(),
                java.util.Set.of(),
                List.of("allergies.uniqueBy(allergen)")
        ));
        CelInvariantEngine engine = new CelInvariantEngine(rules);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("allergies", List.of(
                Map.of("allergen", "Peanuts"),
                Map.of("allergen", "Peanuts")
        ));

        var result = engine.evaluate(new com.npdev.kernel.ports.InvariantEngine.InvariantEvaluationRequest(
                "Patient",
                payload,
                List.of("allergies.uniqueBy(allergen)"),
                new com.npdev.kernel.ports.InvariantEngine.EvaluationMetadata(
                        "CreatePatient",
                        "validate-patient",
                        0,
                        com.npdev.kernel.FlowStepDefinition.InvariantCheckpoint.PRE,
                        "corr-44"
                ),
                Map.of()
        ));

        assertEquals(1, result.violations().size());
        assertEquals(
                "allergies[*].allergen",
                result.violations().get(0).details().get("fieldPath")
        );
    }

    @Test
    void evaluatesOverlapsProviderExpressionWithNegation() {
        Map<String, CelInvariantEngine.EntityRules> rules = new LinkedHashMap<>();
        rules.put("Appointment", new CelInvariantEngine.EntityRules(
                java.util.Set.of(),
                java.util.Set.of(),
                List.of("!conflicts(providerId, scheduledAt, durationMinutes, id)")
        ));
        CelInvariantEngine engine = new CelInvariantEngine(
                rules,
                (entity, field, value, payload) -> false,
                (resourceField, resourceId, scheduledAtField, scheduledAt, durationField, durationMinutes, excludeId, payload) -> true
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "a-1");
        payload.put("providerId", "p-1");
        payload.put("scheduledAt", "2026-03-10T10:45:00");
        payload.put("durationMinutes", 30);

        var result = engine.evaluate(new com.npdev.kernel.ports.InvariantEngine.InvariantEvaluationRequest(
                "Appointment",
                payload,
                List.of("!conflicts(providerId, scheduledAt, durationMinutes, id)"),
                new com.npdev.kernel.ports.InvariantEngine.EvaluationMetadata(
                        "CreateAppointment",
                        "validate-appointment",
                        0,
                        com.npdev.kernel.FlowStepDefinition.InvariantCheckpoint.PRE,
                        "corr-55"
                ),
                Map.of()
        ));

        assertEquals(1, result.violations().size());
        assertTrue(result.violations().get(0).message().contains("Resource is already reserved"));
        assertEquals("providerId", result.violations().get(0).details().get("fieldPath"));
    }

    @Test
    void overlapsProviderExpressionPassesWhenNoOverlap() {
        Map<String, CelInvariantEngine.EntityRules> rules = new LinkedHashMap<>();
        rules.put("Appointment", new CelInvariantEngine.EntityRules(
                java.util.Set.of(),
                java.util.Set.of(),
                List.of("!conflicts(providerId, scheduledAt, durationMinutes, id)")
        ));
        CelInvariantEngine engine = new CelInvariantEngine(
                rules,
                (entity, field, value, payload) -> false,
                (resourceField, resourceId, scheduledAtField, scheduledAt, durationField, durationMinutes, excludeId, payload) -> false
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "a-2");
        payload.put("providerId", "p-1");
        payload.put("scheduledAt", "2026-03-10T11:15:00");
        payload.put("durationMinutes", 30);

        List<String> violations = engine.evaluate("Appointment", payload);
        assertTrue(violations.isEmpty());
    }

    @Test
    void evaluatesOverlapsRoomExpressionWithNegation() {
        Map<String, CelInvariantEngine.EntityRules> rules = new LinkedHashMap<>();
        rules.put("Appointment", new CelInvariantEngine.EntityRules(
                java.util.Set.of(),
                java.util.Set.of(),
                List.of("!conflicts(roomId, scheduledAt, durationMinutes, id)")
        ));
        CelInvariantEngine engine = new CelInvariantEngine(
                rules,
                (entity, field, value, payload) -> false,
                (resourceField, resourceId, scheduledAtField, scheduledAt, durationField, durationMinutes, excludeId, payload) -> true
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "a-room-1");
        payload.put("roomId", "r-1");
        payload.put("scheduledAt", "2026-03-11T10:45:00");
        payload.put("durationMinutes", 30);

        var result = engine.evaluate(new com.npdev.kernel.ports.InvariantEngine.InvariantEvaluationRequest(
                "Appointment",
                payload,
                List.of("!conflicts(roomId, scheduledAt, durationMinutes, id)"),
                new com.npdev.kernel.ports.InvariantEngine.EvaluationMetadata(
                        "CreateAppointment",
                        "validate-appointment",
                        0,
                        com.npdev.kernel.FlowStepDefinition.InvariantCheckpoint.PRE,
                        "corr-56"
                ),
                Map.of()
        ));

        assertEquals(1, result.violations().size());
        assertTrue(result.violations().get(0).message().contains("Resource is already reserved"));
        assertEquals("roomId", result.violations().get(0).details().get("fieldPath"));
    }

    @Test
    void overlapsRoomExpressionPassesWhenNoOverlap() {
        Map<String, CelInvariantEngine.EntityRules> rules = new LinkedHashMap<>();
        rules.put("Appointment", new CelInvariantEngine.EntityRules(
                java.util.Set.of(),
                java.util.Set.of(),
                List.of("!conflicts(roomId, scheduledAt, durationMinutes, id)")
        ));
        CelInvariantEngine engine = new CelInvariantEngine(
                rules,
                (entity, field, value, payload) -> false,
                (resourceField, resourceId, scheduledAtField, scheduledAt, durationField, durationMinutes, excludeId, payload) -> false
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "a-room-2");
        payload.put("roomId", "r-1");
        payload.put("scheduledAt", "2026-03-11T11:00:00");
        payload.put("durationMinutes", 30);

        List<String> violations = engine.evaluate("Appointment", payload);
        assertTrue(violations.isEmpty());
    }

    @Test
    void scopeExistsExpressionFailsWithDeterministicDetails() {
        Map<String, CelInvariantEngine.EntityRules> rules = new LinkedHashMap<>();
        rules.put("Appointment", new CelInvariantEngine.EntityRules(
                java.util.Set.of(),
                java.util.Set.of(),
                List.of("scope.exists(\"Patient\", \"id\", patientId)")
        ));
        CelInvariantEngine engine = new CelInvariantEngine(
                rules,
                (entity, field, value, payload) -> false,
                (resourceField, resourceId, scheduledAtField, scheduledAt, durationField, durationMinutes, excludeId, payload) -> false,
                (conceptName, fieldPath, expectedValue, state, payload) -> false
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patientId", "p-missing");

        var result = engine.evaluate(new com.npdev.kernel.ports.InvariantEngine.InvariantEvaluationRequest(
                "Appointment",
                payload,
                List.of("scope.exists(\"Patient\", \"id\", patientId)"),
                new com.npdev.kernel.ports.InvariantEngine.EvaluationMetadata(
                        "CreateAppointment",
                        "validate-appointment",
                        0,
                        com.npdev.kernel.FlowStepDefinition.InvariantCheckpoint.PRE,
                        "corr-57"
                ),
                Map.of("tenantId", "clinic-a")
        ));

        assertEquals(1, result.violations().size());
        assertEquals("scope.exists(\"Patient\", \"id\", patientId)", result.violations().get(0).invariantRef());
        assertEquals("patientId", result.violations().get(0).details().get("fieldPath"));
        assertEquals("Patient", result.violations().get(0).details().get("scopeConcept"));
        assertEquals("id", result.violations().get(0).details().get("scopeFieldPath"));
        assertEquals("patientId", result.violations().get(0).details().get("scopeValuePath"));
    }

    @Test
    void scopeExistsExpressionPassesWhenControlledProviderFindsMatch() {
        Map<String, CelInvariantEngine.EntityRules> rules = new LinkedHashMap<>();
        rules.put("Appointment", new CelInvariantEngine.EntityRules(
                java.util.Set.of(),
                java.util.Set.of(),
                List.of("scope.exists(\"Patient\", \"id\", patientId)")
        ));
        CelInvariantEngine engine = new CelInvariantEngine(
                rules,
                (entity, field, value, payload) -> false,
                (resourceField, resourceId, scheduledAtField, scheduledAt, durationField, durationMinutes, excludeId, payload) -> false,
                (conceptName, fieldPath, expectedValue, state, payload) ->
                        "Patient".equals(conceptName)
                                && "id".equals(fieldPath)
                                && "p-1".equals(expectedValue)
                                && "clinic-a".equals(state.get("tenantId"))
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patientId", "p-1");

        var result = engine.evaluate(new com.npdev.kernel.ports.InvariantEngine.InvariantEvaluationRequest(
                "Appointment",
                payload,
                List.of("scope.exists(\"Patient\", \"id\", patientId)"),
                new com.npdev.kernel.ports.InvariantEngine.EvaluationMetadata(
                        "CreateAppointment",
                        "validate-appointment",
                        0,
                        com.npdev.kernel.FlowStepDefinition.InvariantCheckpoint.PRE,
                        "corr-58"
                ),
                Map.of("tenantId", "clinic-a")
        ));

        assertTrue(result.violations().isEmpty());
    }

    @Test
    void evaluatesOnlyRequestedInvariantRefs() {
        CelInvariantEngine engine = CelInvariantEngine.fromCompiledModel(
                userModel(),
                (entity, field, value, payload) ->
                        "User".equals(entity) && "email".equals(field) && "taken@acme.com".equals(value)
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "u-10");
        payload.put("name", "Ana");
        payload.put("email", "taken@acme.com");

        var result = engine.evaluate(new com.npdev.kernel.ports.InvariantEngine.InvariantEvaluationRequest(
                "User",
                payload,
                List.of("required(name)", "unique(email)"),
                new com.npdev.kernel.ports.InvariantEngine.EvaluationMetadata(
                        "CreateUser",
                        "validate-user",
                        0,
                        com.npdev.kernel.FlowStepDefinition.InvariantCheckpoint.PRE,
                        "corr-1"
                ),
                Map.of()
        ));

        assertEquals(1, result.violations().size());
        assertEquals("unique(email)", result.violations().get(0).invariantRef());
    }

    @Test
    void evaluateListContextReturnsPerRefViolationForOnlyFailingInvariant() {
        CelInvariantEngine engine = CelInvariantEngine.fromCompiledModel(
                userModel(),
                (entity, field, value, payload) ->
                        "User".equals(entity) && "email".equals(field) && "taken@acme.com".equals(value)
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "u-11");
        payload.put("name", "Ana");
        payload.put("email", "taken@acme.com");

        List<com.npdev.kernel.ports.InvariantEngine.Violation> violations = engine.evaluate(
                List.of("required(name)", "unique(email)"),
                new com.npdev.kernel.ports.InvariantEngine.EvaluationContext(
                        "CreateUser",
                        "User",
                        com.npdev.kernel.FlowStepDefinition.InvariantCheckpoint.PRE,
                        payload,
                        Map.of()
                )
        );

        assertEquals(1, violations.size());
        assertEquals("INVARIANT_FAIL", violations.get(0).code());
        assertEquals("unique(email)", violations.get(0).invariantRef());
        assertEquals("User", violations.get(0).conceptName());
        assertEquals("CreateUser", violations.get(0).flowName());
    }

    @Test
    void unknownInvariantRefReturnsUnknownRefViolationCode() {
        CelInvariantEngine engine = CelInvariantEngine.fromCompiledModel(userModel());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "u-12");
        payload.put("name", "Ana");
        payload.put("email", "ana@acme.com");

        var result = engine.evaluate(new com.npdev.kernel.ports.InvariantEngine.InvariantEvaluationRequest(
                "User",
                payload,
                List.of("UnknownInvariant"),
                new com.npdev.kernel.ports.InvariantEngine.EvaluationMetadata(
                        "CreateUser",
                        "validate-user",
                        0,
                        com.npdev.kernel.FlowStepDefinition.InvariantCheckpoint.PRE,
                        "corr-3"
                ),
                Map.of()
        ));

        assertEquals(1, result.violations().size());
        assertEquals("INVARIANT_UNKNOWN_REF", result.violations().get(0).code());
        assertEquals("UnknownInvariant", result.violations().get(0).invariantRef());
    }

    @Test
    void evaluateRequestReturnsViolationsForMissingRequiredFields() {
        CelInvariantEngine engine = CelInvariantEngine.fromCompiledModel(userModel());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "u-5");

        var result = engine.evaluate(new com.npdev.kernel.ports.InvariantEngine.InvariantEvaluationRequest(
                "User",
                payload,
                List.of("required(name)", "required(email)"),
                new com.npdev.kernel.ports.InvariantEngine.EvaluationMetadata(
                        "CreateUser",
                        "validate-user",
                        0,
                        com.npdev.kernel.FlowStepDefinition.InvariantCheckpoint.PRE,
                        "corr-2"
                ),
                Map.of()
        ));

        assertEquals(2, result.violations().size());
        assertTrue(result.violations().stream().allMatch(v -> v.invariantRef().startsWith("required(")));
    }

    private static CompiledModel userModel() {
        List<CompiledField> fields = List.of(
                new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                new CompiledField("name", "string", "String", false, true, false),
                new CompiledField("email", "string", "String", false, true, true)
        );

        CompiledEntity user = new CompiledEntity("User", "User", "users", fields);
        Map<String, CompiledEntity> entities = new LinkedHashMap<>();
        entities.put("User", user);

        return new CompiledModel("demo", "v1", entities);
    }
}
