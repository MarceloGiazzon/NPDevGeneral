package com.finalexec.pluginipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.model.RuntimePluginManifest;
import com.finalexec.npdev.service.PluginExecutionPolicyEvaluator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.finalexec.npdev.service.pluginipc.JavaSourceRuntimeRefManifest;
import com.finalexec.npdev.service.pluginipc.JavaSourceRuntimeRefManifestLoader;
import com.finalexec.npdev.service.pluginipc.ManifestDrivenJavaSourcePluginHandler;
import com.finalexec.npdev.service.pluginipc.PluginIpcChildProcessPool;
import com.finalexec.npdev.service.pluginipc.PluginIpcHostSession;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * SEC-3 / Model B (docs/architecture/PLUGIN_PROCESS_ISOLATION_DESIGN.md): closes step 3's own
 * "real-plugin-classloading" finding -- every {@link PluginIpcChildProcessPoolTest} invocation
 * names a real, COMPILED TEST handler class by FQCN; none of it proves a fungible pooled worker
 * can run an actual GENERATED plugin, because until now nothing but compiled bytecode
 * (RuntimeApiEmitter.javaSourceProvidersSource's {@code new <mainClass>()} expression) knew a
 * plugin:java-source mount's FQCN. This test resolves that FQCN from the new data manifest
 * (JavaSourceRuntimeRefManifest, npdev/plugin-runtime/java-source-runtime-refs.json) instead, and
 * runs the REAL {@code auditLog} capability shipped in NPDevSamples/dsl-conformance-max
 * (com.npdev.samples.dslconformance.audit.AuditLogCapability#record) through the pool -- the same
 * plugin the design doc's own section 6 step 1 prototyped against. The pool's {@code handlerClassName}
 * still has to name something implementing {@link com.npdev.kernel.ports.CapabilityAdapter} with a
 * {@code (PluginIpcCallbackClient)} constructor (PluginIpcChildProcessMain's own reflection
 * contract) -- {@link ManifestDrivenJavaSourcePluginHandler} is that bridge, resolving the manifest
 * itself at invoke time rather than the raw plugin POJO being usable directly.
 *
 * <p>This test file is shared across whichever sample {@code run-runtimehost-gate.ps1} currently
 * has assembled (its default, {@code simple-contact-intake}, has no java-source plugin at all), so
 * it self-skips via {@link org.junit.jupiter.api.Assumptions} when the manifest carries no
 * {@code auditLogJavaSourceHandler} entry, and only exercises real coverage when generated against
 * {@code dsl-conformance-max}.</p>
 */
class PluginIpcChildProcessPoolRealPluginTest {

    private static final String RUNTIME_REF = "auditLogJavaSourceHandler";

    @Test
    void aPooledWorkerClassloadsAndInvokesTheRealGeneratedAuditLogPlugin() throws Exception {
        JavaSourceRuntimeRefManifest manifest = new JavaSourceRuntimeRefManifestLoader(new ObjectMapper()).load();
        Optional<JavaSourceRuntimeRefManifest.Entry> entry = manifest.entryForRuntimeRef(RUNTIME_REF);
        assumeTrue(
                entry.isPresent(),
                "No '" + RUNTIME_REF + "' entry in java-source-runtime-refs.json -- the currently assembled "
                        + "sample app is not dsl-conformance-max, so this real-plugin proof has nothing to run "
                        + "against. Regenerate against dsl-conformance-max to exercise it."
        );

        RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution =
                new RuntimePluginAdapterRegistry.RegisteredAdapterContribution(
                        entry.get().pluginId(), "1.0.0", entry.get().capability(), "record",
                        entry.get().adapterId(), "auditLog.record", "class", entry.get().runtimeRef()
                );
        PluginIpcHostSession hostSession = new PluginIpcHostSession(
                registryFor(contribution),
                new PluginExecutionPolicyEvaluator(null, "dev", "", "", "", ""),
                call -> {
                    throw new AssertionError("AuditLogCapability#record makes no host callback: " + call);
                }
        );
        CapabilityCall call = new CapabilityCall(
                entry.get().capability(), entry.get().capabilityType(), entry.get().adapterId(), "record",
                List.of(Map.of("tenantId", "acme", "action", "PROBE"))
        );

        try (PluginIpcChildProcessPool pool = new PluginIpcChildProcessPool(1, 5, Duration.ofMinutes(10))) {
            CapabilityResult result = pool.invoke(
                    hostSession, contribution, call, Map.of(), ManifestDrivenJavaSourcePluginHandler.class.getName()
            );

            assertTrue(result.ok(), () -> "expected success invoking the real plugin, got: " + result);
            @SuppressWarnings("unchecked")
            Map<String, Object> value = (Map<String, Object>) result.value();
            assertTrue(value.containsKey("sequence"), () -> "expected AuditLogCapability#record's own return shape, got: " + value);
            assertTrue(value.containsKey("recordedAt"), () -> "expected AuditLogCapability#record's own return shape, got: " + value);
        }
    }

    private static RuntimePluginAdapterRegistry registryFor(
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution
    ) {
        RuntimePluginManifest manifest = new RuntimePluginManifest(
                "test-manifest.json", "1",
                List.of(new RuntimePluginManifest.PluginContribution(
                        contribution.pluginId(), "Audit Log Java Source Plugin", contribution.pluginVersion(), true,
                        List.of(new RuntimePluginManifest.AdapterContribution(
                                contribution.capability(), contribution.operation(), contribution.adapterId(),
                                contribution.bindingKey(),
                                new RuntimePluginManifest.ImplementationRef(contribution.implementationKind(), contribution.runtimeRef())
                        ))
                ))
        );
        return new RuntimePluginAdapterRegistry(manifest);
    }
}
