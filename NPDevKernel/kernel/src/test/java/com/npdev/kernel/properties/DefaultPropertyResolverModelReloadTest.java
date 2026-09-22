package com.npdev.kernel.properties;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledProperty;
import com.npdev.dsl.v1.compiled.CompiledPropertyScope;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateways;
import com.npdev.kernel.ports.AuditLogStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves {@link DefaultPropertyResolver}'s live-model-reload fix: a {@code CompiledModel} swap
 * behind its {@code Supplier} (mirroring {@code ModelHolder.swap(...)} in production) must be
 * observed WITHOUT restarting the resolver, and the pre-swap cached {@link PropertyExplanation}
 * must not be served afterward.
 *
 * <p>The single test below deliberately exercises BOTH halves of the fix in one resolver instance,
 * with the SAME {@link ExecutionContext}/property key (so it would hit the exact same cache key if
 * {@code version} were not bumped on reload):
 * <ol>
 *   <li>{@code explain()} against the first model resolves {@code "theme"} to its {@code "light"}
 *       default and populates the cache -- this alone would pass even with the OLD, un-fixed
 *       resolver (no reload has happened yet).</li>
 *   <li>The supplier is swapped to a second model where {@code "theme"}'s default is {@code "dark"}.
 *       A second {@code explain()} call, same key, must return {@code "dark"}. Without the
 *       {@code compiledModel} field replaced by a live {@code liveModel()} read, this would still
 *       see the first model (property not found or old default). Without the cache-invalidation half
 *       (bumping {@code version}/clearing {@code cache} on a detected reload), this would still
 *       return the stale cached {@code "light"} explanation even if the model read itself were
 *       fixed -- so a regression in either half fails this one assertion.</li>
 * </ol>
 */
class DefaultPropertyResolverModelReloadTest {

    private static final CompiledPropertyScope TENANT_SCOPE = new CompiledPropertyScope("tenant", null);
    private static final ExecutionContext CTX = ExecutionContext.of("t1", "actor-1");

    @Test
    void explainObservesAHotModelReloadAndInvalidatesThePreReloadCacheEntry() {
        CompiledModel lightModel = modelWithThemeDefault("light");
        CompiledModel darkModel = modelWithThemeDefault("dark");

        AtomicReference<CompiledModel> currentModel = new AtomicReference<>(lightModel);
        PropertyResolver resolver = new DefaultPropertyResolver(
                ConceptGateways.inMemory(), AuditLogStore.noop(), currentModel::get);

        PropertyExplanation before = resolver.explain("theme", CTX);
        assertEquals("light", before.value(), "first read must resolve the declared default of the initial model");

        // Hot-reload: swap the live model WITHOUT constructing a new resolver -- exactly what a
        // ModelHolder.swap(...) driven reload looks like from this class's point of view.
        currentModel.set(darkModel);

        PropertyExplanation after = resolver.explain("theme", CTX);
        assertEquals("dark", after.value(),
                "post-reload explain() with the SAME context/key must see the new model's default, "
                        + "not a value baked into a field at construction time, and must NOT be served "
                        + "from the pre-reload cache entry for that same key");
    }

    private static CompiledModel modelWithThemeDefault(String defaultValue) {
        CompiledProperty theme = new CompiledProperty("theme", "string", defaultValue, List.of("tenant"), null, false);
        return new CompiledModel(
                "wms.props", "1.0.0", "1.0",
                Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(),
                List.of(TENANT_SCOPE),
                List.of(theme)
        );
    }
}
