package com.finalexec.config;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.FlowStepDefinition;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.runtime.support.CelInvariantEngine;
import com.npdev.runtime.support.GeneratedCrudRuntimeSupport;
import com.npdev.runtime.support.ReloadableInvariantEngine;
import com.npdev.runtime.support.SystemRuntimeClock;
import com.npdev.runtime.support.InMemoryOrchestrationExecutionRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-283: the two invariant-engine beans of a generated app.
 * (a) the runtimeInvariantEngineFactory bean passes the compound-unique lookup through -- the port's
 * 4-argument default used to drop it, so a duplicate (orgId, email) pair was never pre-checked.
 * (b) the flow engine's lookups (Lookups.fromConceptStore, what the invariantEngine bean binds) search
 * only the flow's own tenant.
 */
class NpdevCapabilityBindingConfigInvariantLookupsTest {

    @Test
    void crudFactoryBeanRefusesADuplicateCompoundPair() {
        CompiledModel model = model();
        GeneratedCrudRuntimeSupport support = new GeneratedCrudRuntimeSupport(
                () -> model,
                new KernelRunner((EventBus) event -> {}, new InvariantEngine() {
                    @Override
                    public List<String> evaluate(String entityName, Object payload) {
                        return List.of();
                    }
                }),
                null, null, null, null,
                new SystemRuntimeClock(),
                new InMemoryOrchestrationExecutionRegistry(),
                new NpdevCapabilityBindingConfig().runtimeInvariantEngineFactory(new ModelHolder(model)),
                null, null, null
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orgId", "acme");
        payload.put("email", "taken@acme.com");

        List<GeneratedCrudRuntimeSupport.InvariantViolationDetail> violations = support.validateEntityDetailed(
                "Membership",
                payload,
                (entity, field, value, excludeId, rawPayload) -> false,
                (entity, fields, values, excludeId, rawPayload) ->
                        fields.equals(List.of("orgId", "email")) && values.equals(List.of("acme", "taken@acme.com"))
        );

        assertEquals(1, violations.size(), "violations: " + violations);
        assertTrue(violations.get(0).message().contains("orgId, email"), violations.get(0).message());
    }

    @Test
    void flowInvariantBeanSearchesOnlyTheFlowsTenant() {
        CompiledModel model = model();
        InMemoryConceptStore store = new InMemoryConceptStore(model);
        store.save(new ConceptRecord("Membership", "m-1", "acme",
                Map.of("id", "m-1", "orgId", "acme", "email", "taken@acme.com"), null));
        ReloadableInvariantEngine engine = new ReloadableInvariantEngine(
                model, (tenantId, conceptName) -> CelInvariantEngine.Lookups.fromConceptStore(store, tenantId));

        assertEquals("INVARIANT_FAIL", single(evaluate(engine, "acme", "unique(email)")).code());
        assertEquals("INVARIANT_FAIL", single(evaluate(engine, "acme", "unique(orgId,email)")).code());
        assertTrue(evaluate(engine, "other", "unique(email)").isEmpty(), "another tenant's row must not collide");
        assertTrue(evaluate(engine, "other", "unique(orgId,email)").isEmpty(), "another tenant's row must not collide");
    }

    private static InvariantEngine.Violation single(List<InvariantEngine.Violation> violations) {
        assertEquals(1, violations.size(), "violations: " + violations);
        return violations.get(0);
    }

    private static List<InvariantEngine.Violation> evaluate(ReloadableInvariantEngine engine, String tenantId, String ref) {
        return engine.evaluate(new InvariantEngine.InvariantEvaluationRequest(
                "Membership",
                Map.of("orgId", "acme", "email", "taken@acme.com"),
                List.of(ref),
                new InvariantEngine.EvaluationMetadata("Invite", "check", 0, FlowStepDefinition.InvariantCheckpoint.PRE, null),
                Map.of("tenantId", tenantId)
        )).violations();
    }

    private static CompiledModel model() {
        CompiledConcept membership = new CompiledConcept(
                "Membership", "Membership", "memberships",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("orgId", "string", "String", false, true, false),
                        new CompiledField("email", "string", "String", false, true, false)
                ),
                List.of(),
                List.of(
                        new CompiledInvariant("unique(email)", "unique", "email", null),
                        new CompiledInvariant("unique(orgId,email)", "unique", "orgId", null, List.of("orgId", "email"))
                )
        );
        return new CompiledModel("demo", "v1", Map.of("Membership", membership));
    }
}
