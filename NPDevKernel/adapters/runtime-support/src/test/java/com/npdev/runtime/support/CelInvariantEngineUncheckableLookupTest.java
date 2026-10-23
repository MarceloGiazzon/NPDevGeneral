package com.npdev.runtime.support;

import com.npdev.kernel.FlowStepDefinition;
import com.npdev.kernel.ports.InvariantEngine;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-283 (b, c): an engine built without a lookup used to ANSWER a unique / conflict / scope rule
 * ("no duplicate", "no conflict", "not found") without looking anything up. It must now report the
 * rule as INVARIANT_UNCHECKABLE, naming the missing lookup.
 */
class CelInvariantEngineUncheckableLookupTest {

    @Test
    void uniqueRuleWithNoLookupIsUncheckableNotAPass() {
        CelInvariantEngine engine = new CelInvariantEngine(Map.of(
                "User", new CelInvariantEngine.EntityRules(Set.of(), Set.of("email"))));

        InvariantEngine.Violation violation = single(engine, "User", Map.of("email", "a@b.c"), "unique(email)");

        assertEquals("INVARIANT_UNCHECKABLE", violation.code());
        assertEquals("unique", violation.details().get("missingLookup"));
    }

    @Test
    void conflictRuleWithNoLookupIsUncheckableNotAPass() {
        String ref = "!conflicts(providerId, scheduledAt, durationMinutes, id)";
        CelInvariantEngine engine = new CelInvariantEngine(Map.of(
                "Appointment", new CelInvariantEngine.EntityRules(Set.of(), Set.of(), List.of(ref))));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "a-1");
        payload.put("providerId", "p-1");
        payload.put("scheduledAt", "2026-03-10T10:45:00");
        payload.put("durationMinutes", 30);

        InvariantEngine.Violation violation = single(engine, "Appointment", payload, ref);

        assertEquals("INVARIANT_UNCHECKABLE", violation.code());
        assertEquals("conflict", violation.details().get("missingLookup"));
    }

    @Test
    void scopeRuleWithNoLookupIsUncheckableNotADataFailure() {
        String ref = "scope.exists(\"Patient\", \"id\", patientId)";
        CelInvariantEngine engine = new CelInvariantEngine(Map.of(
                "Appointment", new CelInvariantEngine.EntityRules(Set.of(), Set.of(), List.of(ref))));

        InvariantEngine.Violation violation = single(engine, "Appointment", Map.of("patientId", "p-1"), ref);

        assertEquals("INVARIANT_UNCHECKABLE", violation.code());
        assertEquals("scope", violation.details().get("missingLookup"));
    }

    @Test
    void withLookupsBindsTheSameRulesToRealLookups() {
        CelInvariantEngine unwired = new CelInvariantEngine(Map.of(
                "User", new CelInvariantEngine.EntityRules(Set.of(), Set.of("email"))));
        CelInvariantEngine wired = unwired.withLookups(new CelInvariantEngine.Lookups(
                (entity, field, value, payload) -> "taken@b.c".equals(value), null, null, null));

        assertEquals("INVARIANT_FAIL", single(wired, "User", Map.of("email", "taken@b.c"), "unique(email)").code());
        assertTrue(evaluate(wired, "User", Map.of("email", "free@b.c"), "unique(email)").isEmpty());
    }

    @Test
    void unknownEntityIsAViolationOnTheEntityPayloadOverloadToo() {
        CelInvariantEngine engine = new CelInvariantEngine(Map.of());

        List<String> violations = engine.evaluate("Nope", Map.of());

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("Unknown concept"), violations.get(0));
    }

    static InvariantEngine.Violation single(CelInvariantEngine engine, String concept, Map<String, Object> payload, String ref) {
        List<InvariantEngine.Violation> violations = evaluate(engine, concept, payload, ref);
        assertEquals(1, violations.size(), "violations: " + violations);
        return violations.get(0);
    }

    private static List<InvariantEngine.Violation> evaluate(
            InvariantEngine engine, String concept, Map<String, Object> payload, String ref) {
        return engine.evaluate(new InvariantEngine.InvariantEvaluationRequest(
                concept,
                payload,
                List.of(ref),
                new InvariantEngine.EvaluationMetadata("Flow", "check", 0, FlowStepDefinition.InvariantCheckpoint.PRE, null),
                Map.of()
        )).violations();
    }
}
