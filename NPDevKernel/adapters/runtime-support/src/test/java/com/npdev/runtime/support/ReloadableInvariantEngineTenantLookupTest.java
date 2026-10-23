package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.FlowStepDefinition;
import com.npdev.kernel.ports.InvariantEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-283 (b): the flow engine's invariant engine binds its lookups to the tenant in the flow's own
 * state, and refuses (INVARIANT_UNCHECKABLE) when the state names none -- never a guessed tenant.
 */
class ReloadableInvariantEngineTenantLookupTest {

    @Test
    void uniqueLookupSearchesTheTenantNamedInFlowState() {
        List<String> searchedTenants = new ArrayList<>();
        ReloadableInvariantEngine engine = new ReloadableInvariantEngine(userModel(), (tenantId, conceptName) ->
                new CelInvariantEngine.Lookups((entity, field, value, payload) -> {
                    searchedTenants.add(tenantId);
                    return "acme".equals(tenantId) && "taken@b.c".equals(value);
                }, null, null, null));

        List<InvariantEngine.Violation> acme = evaluate(engine, Map.of("tenantId", "acme"));
        List<InvariantEngine.Violation> other = evaluate(engine, Map.of("tenantId", "other"));

        assertEquals(List.of("acme", "other"), searchedTenants);
        assertEquals(1, acme.size(), "acme already has the email: " + acme);
        assertEquals("INVARIANT_FAIL", acme.get(0).code());
        assertTrue(other.isEmpty(), "another tenant's row must not collide: " + other);
    }

    @Test
    void flowStateWithoutATenantIsUncheckableNotAPass() {
        ReloadableInvariantEngine engine = new ReloadableInvariantEngine(userModel(), (tenantId, conceptName) -> {
            throw new AssertionError("no lookup may be built without a tenant");
        });

        List<InvariantEngine.Violation> violations = evaluate(engine, Map.of());

        assertEquals(1, violations.size(), "violations: " + violations);
        assertEquals("INVARIANT_UNCHECKABLE", violations.get(0).code());
        assertTrue(violations.get(0).message().contains(ReloadableInvariantEngine.NO_TENANT_REASON),
                violations.get(0).message());
    }

    private static List<InvariantEngine.Violation> evaluate(ReloadableInvariantEngine engine, Map<String, Object> state) {
        return engine.evaluate(new InvariantEngine.InvariantEvaluationRequest(
                "User",
                Map.of("email", "taken@b.c"),
                List.of("EmailUnique"),
                new InvariantEngine.EvaluationMetadata("Register", "check", 0, FlowStepDefinition.InvariantCheckpoint.PRE, null),
                state
        )).violations();
    }

    private static CompiledModel userModel() {
        CompiledConcept user = new CompiledConcept(
                "User", "User", "users",
                List.of(new CompiledField("email", "string", "String", false, true, false)),
                List.of(),
                List.of(new CompiledInvariant("EmailUnique", "unique", "email", null))
        );
        return new CompiledModel("demo", "v1", Map.of("User", user));
    }
}
