package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.RuntimeInvariantEngineFactory;
import com.npdev.runtime.support.crud.uniqueness.UniqueValueLookup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D1 (Phase 1 of the server-driven-UI structural rewrite, REG-208 convention). Before this fix,
 * {@code GeneratedCrudRuntimeSupport} took a raw {@code CompiledModel} and cached it in a final
 * field, so a live model reload (B28's {@code ModelHolder.swap()}) was never observed here -- a
 * reloaded model's new concept was invisible to the generated CRUD REST surface without a restart.
 * This test proves the fix the way the boundary's own text says was missing: construct the class
 * once, swap the underlying model, and confirm the SAME instance sees the new concept immediately.
 */
class GeneratedCrudRuntimeSupportModelReloadTest {

    @Test
    void aConceptAddedAfterConstructionIsVisibleWithoutRebuildingTheInstance() {
        AtomicReference<CompiledModel> currentModel = new AtomicReference<>(
                new CompiledModel("demo", "1.0.0", "v1", Map.of())
        );
        GeneratedCrudRuntimeSupport support = new GeneratedCrudRuntimeSupport(
                currentModel::get,
                kernelRunner(),
                null,
                null,
                null,
                null,
                new SystemRuntimeClock(),
                new InMemoryOrchestrationExecutionRegistry(),
                noopRuntimeInvariantEngineFactory(),
                AuditLogStore.noop(),
                PermissionEvaluator.allowAll(),
                IdempotencyStore.noop()
        );

        // Before the swap: "Widget" does not exist in the model at all.
        List<String> before = support.validateEntity("Widget", Map.of(), noopUniqueLookup());
        assertTrue(
                before.contains("Unknown entity for runtime validation: Widget"),
                "a concept absent from the ORIGINAL model must report unknown_entity: " + before
        );

        // Live swap -- the SAME support instance, no rebuild, no restart.
        CompiledConcept widget = new CompiledConcept("Widget", "Widget", "widgets", List.of());
        currentModel.set(new CompiledModel("demo", "1.0.1", "v1", Map.of("Widget", widget)));

        // After the swap: the newly-added concept must be visible immediately.
        List<String> after = support.validateEntity("Widget", Map.of(), noopUniqueLookup());
        assertFalse(
                after.contains("Unknown entity for runtime validation: Widget"),
                "a concept added by a LIVE model reload must be visible without reconstructing "
                        + "GeneratedCrudRuntimeSupport: " + after
        );
    }

    private static UniqueValueLookup noopUniqueLookup() {
        return (entityName, fieldName, value, excludeId, payload) -> false;
    }

    private static RuntimeInvariantEngineFactory noopRuntimeInvariantEngineFactory() {
        return (uniqueValueLookup, conflictLookup) -> new InvariantEngine() {
            @Override
            public List<String> evaluate(String entityName, Object payload) {
                return List.of();
            }
        };
    }

    private static KernelRunner kernelRunner() {
        return new KernelRunner(
                (EventBus) event -> {
                },
                new InvariantEngine() {
                    @Override
                    public List<String> evaluate(String entityName, Object payload) {
                        return List.of();
                    }
                }
        );
    }
}
