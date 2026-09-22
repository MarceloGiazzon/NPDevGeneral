package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-208 (B28 lift): before this fix, {@code CelInvariantEngine.fromCompiledModel} built its rule
 * set ONCE at bean-construction time with no in-place rebuild, so a live model reload (B28's
 * {@code ModelHolder.swap()}) adding a new invariant was invisible without an app restart. This
 * test proves {@link ReloadableInvariantEngine} closes that gap the way
 * {@code GeneratedCrudRuntimeSupportModelReloadTest} proves it for the CRUD runtime support: build
 * the wrapper once, swap the underlying model via {@link ReloadableInvariantEngine#setModel}, and
 * confirm the SAME instance observes the new invariant immediately.
 */
class ReloadableInvariantEngineModelReloadTest {

    @Test
    void anInvariantAddedAfterConstructionIsEnforcedWithoutRebuildingTheInstance() {
        CompiledConcept widgetWithoutInvariants = new CompiledConcept("Widget", "Widget", "widgets", List.of());
        ReloadableInvariantEngine engine = new ReloadableInvariantEngine(
                new CompiledModel("demo", "1.0.0", "v1", Map.of("Widget", widgetWithoutInvariants))
        );

        // Before the reload: "Widget" has no invariants at all, so an empty payload is valid.
        List<String> before = engine.evaluate("Widget", Map.of());
        assertTrue(before.isEmpty(), "a concept with no declared invariants must report no violations: " + before);

        // Live reload -- the SAME engine instance, no rebuild, no restart.
        CompiledConcept widgetWithRequiredName = new CompiledConcept(
                "Widget",
                "Widget",
                "widgets",
                List.of(),
                List.of(),
                List.of(new CompiledInvariant("required(name)", "required", "name", null))
        );
        engine.setModel(new CompiledModel("demo", "1.0.1", "v1", Map.of("Widget", widgetWithRequiredName)));

        // After the reload: the newly-added required(name) invariant must be enforced immediately.
        List<String> after = engine.evaluate("Widget", Map.of());
        assertFalse(after.isEmpty(), "an invariant added by a LIVE model reload must be enforced without "
                + "reconstructing ReloadableInvariantEngine: " + after);
        assertTrue(
                after.stream().anyMatch(v -> v.contains("required field 'name'")),
                "violation must name the missing 'name' field: " + after
        );
    }
}
