package com.finalexec.npdev.service.pluginipc;

import com.finalexec.npdev.service.PluginExecutionPolicyEvaluator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;

import java.util.Map;
import java.util.Objects;

/**
 * SEC-5: production bridge from a {@code plugin:java-source} mount's resolved capability adapter to
 * {@link PluginIpcChildProcessPool} -- the OS-process-isolated replacement for {@code
 * ArtifactLocalJavaSourceCapabilityHandler}'s in-process {@code new <MainClass>()} dispatch. {@code
 * TimeBoundedPluginExecutionEngine.invokeHandler()} already branches on {@code instanceof
 * CapabilityAdapter} first, so this class slots into that existing dispatch unchanged --
 * SandboxedCapabilityAdapter's own soft wall-clock timeout keeps applying on top of the pool's real
 * OS-level containment, not instead of it.
 *
 * <p>One instance per {@code plugin:java-source} mount (constructed by the generated {@code
 * RuntimePluginRealizationProvider} bean, see {@code RuntimeApiEmitter.javaSourceProvidersSource}),
 * sharing the single app-wide {@link PluginIpcChildProcessPool}.</p>
 */
public final class PluginIpcCapabilityHandler implements CapabilityAdapter {

    private final PluginIpcChildProcessPool pool;
    private final RuntimePluginAdapterRegistry registry;
    private final PluginExecutionPolicyEvaluator policyEvaluator;
    private final String runtimeRef;
    private final String capability;
    private final String adapterId;

    public PluginIpcCapabilityHandler(
            PluginIpcChildProcessPool pool,
            RuntimePluginAdapterRegistry registry,
            PluginExecutionPolicyEvaluator policyEvaluator,
            String runtimeRef,
            String capability,
            String adapterId
    ) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.policyEvaluator = Objects.requireNonNull(policyEvaluator, "policyEvaluator");
        this.runtimeRef = Objects.requireNonNull(runtimeRef, "runtimeRef");
        this.capability = Objects.requireNonNull(capability, "capability");
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
    }

    @Override
    public String adapterId() {
        return adapterId;
    }

    @Override
    public String capability() {
        return capability;
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution =
                registry.requireContribution(call.capability(), call.operation(), call.adapterId());
        if (!runtimeRef.equals(contribution.runtimeRef())) {
            // Defence in depth, mirroring RuntimePluginRuntimeRefResolver's own resolved-handler
            // cross-check: this handler was generated for ONE specific runtimeRef; a mismatch here
            // means the registry resolved a different mount than the one this instance was built for.
            return CapabilityResult.failure(
                    "JAVA_SOURCE_RUNTIME_REF_MISMATCH",
                    "PluginIpcCapabilityHandler bound to runtimeRef '" + runtimeRef
                            + "' but the registry resolved '" + contribution.runtimeRef() + "'",
                    CapabilityErrorKind.PERMANENT,
                    Map.of("capability", call.capability(), "operation", call.operation())
            );
        }

        PluginIpcHostSession hostSession = new PluginIpcHostSession(registry, policyEvaluator, this::rejectCallback);
        try {
            return pool.invoke(
                    hostSession, contribution, call, contextState, ManifestDrivenJavaSourcePluginHandler.class.getName()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return CapabilityResult.failure(
                    "PLUGIN_EXECUTION_INTERRUPTED",
                    "Plugin IPC pool checkout was interrupted",
                    CapabilityErrorKind.TRANSIENT,
                    Map.of("capability", call.capability(), "operation", call.operation())
            );
        }
    }

    /**
     * {@code plugin:java-source} plugins have never been able to call back into the host: {@code
     * ArtifactLocalJavaSourceCapabilityHandler}'s in-process dispatch constructs a plugin with a bare
     * no-arg constructor, giving it no handle to reach any other capability through. This IPC path
     * preserves that exact boundary rather than silently adding a new capability -- a plugin issuing a
     * callback frame gets a clear, honest rejection instead of an unsupported dispatch.
     */
    private CapabilityResult rejectCallback(CapabilityCall call) {
        return CapabilityResult.failure(
                "JAVA_SOURCE_CALLBACK_NOT_SUPPORTED",
                "plugin:java-source plugins cannot call back into the host: " + call.capability() + "." + call.operation(),
                CapabilityErrorKind.PERMANENT,
                Map.of("capability", call.capability(), "operation", call.operation())
        );
    }
}
