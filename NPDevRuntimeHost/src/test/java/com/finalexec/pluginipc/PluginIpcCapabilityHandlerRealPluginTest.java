package com.finalexec.pluginipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.model.RuntimePluginManifest;
import com.finalexec.npdev.service.PluginExecutionPolicyEvaluator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.finalexec.npdev.service.pluginipc.JavaSourceRuntimeRefManifest;
import com.finalexec.npdev.service.pluginipc.JavaSourceRuntimeRefManifestLoader;
import com.finalexec.npdev.service.pluginipc.PluginIpcCapabilityHandler;
import com.finalexec.npdev.service.pluginipc.PluginIpcChildProcessPool;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * SEC-5: {@link PluginIpcCapabilityHandler} is what RuntimeApiEmitter's generated
 * GeneratedJavaSourceCapabilityProviders bean now constructs for a real {@code plugin:java-source}
 * mount -- this proves ITS OWN dispatch logic (registry lookup, runtimeRef cross-check, delegation to
 * the pool) against the same real generated plugin {@link PluginIpcChildProcessPoolRealPluginTest}
 * proves the lower-level pool with, since the handler is the actual class production traffic reaches
 * now, not the raw pool.
 */
class PluginIpcCapabilityHandlerRealPluginTest {

    private static final String RUNTIME_REF = "auditLogJavaSourceHandler";

    @Test
    void invokesTheRealGeneratedPluginThroughTheProductionHandler() throws Exception {
        JavaSourceRuntimeRefManifest.Entry entry = requireManifestEntry();

        RuntimePluginAdapterRegistry registry = registryFor(entry);
        PluginExecutionPolicyEvaluator policyEvaluator = new PluginExecutionPolicyEvaluator(null, "dev", "", "", "", "");

        try (PluginIpcChildProcessPool pool = new PluginIpcChildProcessPool(1, 5, Duration.ofMinutes(10))) {
            PluginIpcCapabilityHandler handler = new PluginIpcCapabilityHandler(
                    pool, registry, policyEvaluator, entry.runtimeRef(), entry.capability(), entry.adapterId()
            );

            assertEquals(entry.capability(), handler.capability());
            assertEquals(entry.adapterId(), handler.adapterId());

            CapabilityCall call = new CapabilityCall(
                    entry.capability(), entry.capabilityType(), entry.adapterId(), "record",
                    List.of(Map.of("tenantId", "acme", "action", "PROBE"))
            );
            CapabilityResult result = handler.invoke(call, Map.of());

            assertTrue(result.ok(), () -> "expected success invoking the real plugin, got: " + result);
            @SuppressWarnings("unchecked")
            Map<String, Object> value = (Map<String, Object>) result.value();
            assertTrue(value.containsKey("sequence"), () -> "expected AuditLogCapability#record's own return shape, got: " + value);
        }
    }

    @Test
    void aRuntimeRefMismatchFailsBeforeEverTouchingThePool() throws Exception {
        JavaSourceRuntimeRefManifest.Entry entry = requireManifestEntry();
        RuntimePluginAdapterRegistry registry = registryFor(entry);
        PluginExecutionPolicyEvaluator policyEvaluator = new PluginExecutionPolicyEvaluator(null, "dev", "", "", "", "");

        try (PluginIpcChildProcessPool pool = new PluginIpcChildProcessPool(1, 5, Duration.ofMinutes(10))) {
            // Deliberately bound to a DIFFERENT runtimeRef than what the registry will resolve for this
            // capability/operation/adapterId -- simulates a wiring bug (a generated bean built for the
            // wrong mount), which must fail closed rather than silently invoke a different plugin.
            PluginIpcCapabilityHandler handler = new PluginIpcCapabilityHandler(
                    pool, registry, policyEvaluator, "someOtherRuntimeRef", entry.capability(), entry.adapterId()
            );

            CapabilityCall call = new CapabilityCall(
                    entry.capability(), entry.capabilityType(), entry.adapterId(), "record",
                    List.of(Map.of("tenantId", "acme", "action", "PROBE"))
            );
            CapabilityResult result = handler.invoke(call, Map.of());

            assertFalse(result.ok(), () -> "expected a runtimeRef mismatch failure, got: " + result);
            assertEquals("JAVA_SOURCE_RUNTIME_REF_MISMATCH", result.error().code());
            assertEquals(1, pool.idleWorkerCount(), "the mismatch must be caught before the pool is ever touched");
        }
    }

    private static JavaSourceRuntimeRefManifest.Entry requireManifestEntry() {
        JavaSourceRuntimeRefManifest manifest = new JavaSourceRuntimeRefManifestLoader(new ObjectMapper()).load();
        Optional<JavaSourceRuntimeRefManifest.Entry> entry = manifest.entryForRuntimeRef(RUNTIME_REF);
        assumeTrue(
                entry.isPresent(),
                "No '" + RUNTIME_REF + "' entry in java-source-runtime-refs.json -- the currently assembled "
                        + "sample app is not dsl-conformance-max, so this real-plugin proof has nothing to run "
                        + "against. Regenerate against dsl-conformance-max to exercise it."
        );
        return entry.get();
    }

    private static RuntimePluginAdapterRegistry registryFor(JavaSourceRuntimeRefManifest.Entry entry) {
        RuntimePluginManifest manifest = new RuntimePluginManifest(
                "test-manifest.json", "1",
                List.of(new RuntimePluginManifest.PluginContribution(
                        entry.pluginId(), "Audit Log Java Source Plugin", "1.0.0", true,
                        List.of(new RuntimePluginManifest.AdapterContribution(
                                entry.capability(), "record", entry.adapterId(),
                                entry.capability() + ".record",
                                new RuntimePluginManifest.ImplementationRef("class", entry.runtimeRef())
                        ))
                ))
        );
        return new RuntimePluginAdapterRegistry(manifest);
    }
}
