package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.RuntimeInvariantEngineFactory;
import com.npdev.runtime.support.crud.uniqueness.UniqueValueLookup;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * REG-240: {@code identityTables} used to be resolved ONCE at construction into a {@code final}
     * field -- REG-235 converted this class's 7 LOOKUP sites to a live {@code Supplier} but left
     * this constructor-time derivation untouched. An app that booted without {@code identity::User}
     * kept an empty role set forever, even after a reload composed the identity pack.
     */
    @Test
    void identityTablesComposedByAReloadAreHonored() throws Exception {
        AtomicReference<CompiledModel> currentModel = new AtomicReference<>(
                new CompiledModel("demo", "1.0.0", "v1", Map.of())
        );
        GeneratedCrudRuntimeSupport support = new GeneratedCrudRuntimeSupport(currentModel::get, kernelRunner());

        assertTrue(identityTables(support).isEmpty(),
                "a model that never composed the identity pack must resolve to empty, not throw");

        currentModel.set(identityPackModel("identity_v1_users"));

        Optional<IdentityPackTableNames> afterReload = identityTables(support);
        assertTrue(afterReload.isPresent(),
                "an identity pack composed by a LIVE reload must be visible without reconstructing "
                        + "GeneratedCrudRuntimeSupport");
        assertEquals("identity_v1_users", afterReload.get().usersTable());
    }

    /**
     * REG-240's second failure mode: this class kept querying the OLD identity table name forever
     * while every other {@code IdentityPackTableNames.tryResolve} caller in RuntimeHost re-resolves
     * per request and uses the NEW one -- a silent split brain between login and CRUD authorization.
     */
    @Test
    void anIdentityTableRenameIsFollowed() throws Exception {
        AtomicReference<CompiledModel> currentModel =
                new AtomicReference<>(identityPackModel("identity_v1_users"));
        GeneratedCrudRuntimeSupport support = new GeneratedCrudRuntimeSupport(currentModel::get, kernelRunner());

        assertEquals("identity_v1_users", identityTables(support).orElseThrow().usersTable());

        currentModel.set(identityPackModel("identity_v2_users"));

        assertEquals("identity_v2_users", identityTables(support).orElseThrow().usersTable(),
                "a renamed identity table must be followed immediately, not read from a table name "
                        + "cached at construction");
    }

    private static Optional<IdentityPackTableNames> identityTables(GeneratedCrudRuntimeSupport support)
            throws Exception {
        Method method = GeneratedCrudRuntimeSupport.class.getDeclaredMethod("identityTables");
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Optional<IdentityPackTableNames> result = (Optional<IdentityPackTableNames>) method.invoke(support);
        return result;
    }

    private static CompiledModel identityPackModel(String usersTable) {
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put("identity::User", new CompiledConcept("identity::User", "User", usersTable, List.of()));
        concepts.put("identity::Role",
                new CompiledConcept("identity::Role", "Role", "identity_v1_roles", List.of()));
        concepts.put("identity::UserRole",
                new CompiledConcept("identity::UserRole", "UserRole", "identity_v1_user_roles", List.of()));
        concepts.put("identity::UserRolePermission", new CompiledConcept(
                "identity::UserRolePermission", "UserRolePermission",
                "identity_v1_user_role_permissions", List.of()));
        return new CompiledModel("demo", "1.0.1", "v1", concepts);
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
