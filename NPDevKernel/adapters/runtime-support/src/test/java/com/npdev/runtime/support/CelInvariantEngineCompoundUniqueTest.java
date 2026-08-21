package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LIFT-UNIQUE-P3: compound (multi-field) unique invariant runtime enforcement. */
class CelInvariantEngineCompoundUniqueTest {

    @Test
    void compoundUniqueViolationIsReportedWithBothFields() {
        CelInvariantEngine engine = CelInvariantEngine.fromCompiledModel(
                membershipModel(),
                (entity, field, value, payload) -> false,
                null,
                null,
                (entity, fields, values, payload) ->
                        "Membership".equals(entity)
                                && fields.equals(List.of("orgId", "email"))
                                && values.equals(List.of("acme", "taken@acme.com"))
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "m-1");
        payload.put("orgId", "acme");
        payload.put("email", "taken@acme.com");

        List<String> violations = engine.evaluate("Membership", payload);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("unique constraint violated for fields (orgId, email)"), violations.get(0));
    }

    @Test
    void compoundUniqueAllowsSameEmailInDifferentOrg() {
        CelInvariantEngine engine = CelInvariantEngine.fromCompiledModel(
                membershipModel(),
                (entity, field, value, payload) -> false,
                null,
                null,
                (entity, fields, values, payload) ->
                        fields.equals(List.of("orgId", "email")) && values.equals(List.of("acme", "taken@acme.com"))
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "m-2");
        payload.put("orgId", "other-org");
        payload.put("email", "taken@acme.com");

        List<String> violations = engine.evaluate("Membership", payload);

        assertTrue(violations.isEmpty(), "different org should not collide: " + violations);
    }

    @Test
    void compoundUniqueSkipsCheckWhenAFieldIsMissing() {
        CelInvariantEngine engine = CelInvariantEngine.fromCompiledModel(
                membershipModel(),
                (entity, field, value, payload) -> false,
                null,
                null,
                (entity, fields, values, payload) -> true // would always violate if actually invoked
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "m-3");
        payload.put("orgId", "acme");
        // email intentionally absent

        List<String> violations = engine.evaluate("Membership", payload);

        assertTrue(violations.isEmpty(), "missing field in the group should skip the compound check: " + violations);
    }

    private static CompiledModel membershipModel() {
        List<CompiledField> fields = List.of(
                new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                new CompiledField("orgId", "string", "String", false, true, false),
                new CompiledField("email", "string", "String", false, true, false)
        );
        CompiledConcept membership = new CompiledConcept(
                "Membership", "Membership", "memberships",
                fields,
                List.of(),
                List.of(new CompiledInvariant(
                        "unique(orgId,email)", "unique", "orgId", null, List.of("orgId", "email")
                ))
        );
        Map<String, CompiledConcept> entities = new LinkedHashMap<>();
        entities.put("Membership", membership);
        return new CompiledModel("demo", "v1", entities);
    }
}
