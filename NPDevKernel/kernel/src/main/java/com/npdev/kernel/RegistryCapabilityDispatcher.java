package com.npdev.kernel;

import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.CapabilityAdapter;


import java.lang.reflect.InvocationTargetException;
import com.npdev.kernel.ports.TenantScope;
import com.npdev.kernel.ports.TenantScopedPersistenceCapabilityContract;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Default capability dispatcher backed by CapabilityRegistry.
 * Adapters stay outside the kernel; dispatch is done by capability contract name + operation.
 */
public final class RegistryCapabilityDispatcher implements CapabilityDispatcher {

    private static final Logger LOG = Logger.getLogger(RegistryCapabilityDispatcher.class.getName());

    private final CapabilityRegistry registry;

    public RegistryCapabilityDispatcher(CapabilityRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        CapabilityCall effectiveCall = call;
        try {
            validateCallContract(effectiveCall);
        } catch (RuntimeException exception) {
            return CapabilityResult.failure(
                    "CAPABILITY_CONTRACT_VIOLATION",
                    exception.getMessage() == null ? "Capability contract validation failed" : exception.getMessage(),
                    CapabilityErrorKind.CONTRACT,
                    Map.of(
                            "capability", effectiveCall.capability(),
                            "capabilityType", effectiveCall.capabilityType(),
                            "operation", effectiveCall.operation()
                    )
            );
        }

        String adapterId = effectiveCall.adapterId();
        if (adapterId == null || adapterId.isBlank()) {
            return CapabilityResult.failure(
                    "CAPABILITY_BINDING_MISSING",
                    "Capability binding not found for capability '" + effectiveCall.capability() + "' and adapter '<missing>'",
                    CapabilityErrorKind.NOT_FOUND,
                    Map.of(
                            "capability", effectiveCall.capability(),
                            "operation", effectiveCall.operation(),
                            "adapterId", "<missing>"
                    )
            );
        }

        Object adapter;
        try {
            adapter = registry.resolve(effectiveCall.capability(), adapterId, Object.class);
            String defaultAdapterId = registry.debugDefaultAdapterId(effectiveCall.capability());
            Map<String, String> available = registry.debugAdaptersFor(effectiveCall.capability());
            LOG.info(String.format("NPDEV-DISPATCH :: cap=%s op=%s requestedAdapterId=%s defaultAdapterId=%s chosenAdapterClass=%s availableAdapters=%s argsCount=%s ctxKeys=%s", effectiveCall.capability(), effectiveCall.operation(), adapterId, defaultAdapterId, adapter == null ? null : adapter.getClass().getName(), available, effectiveCall.args() == null ? 0 : effectiveCall.args().size(), contextState == null ? 0 : contextState.keySet().size()));


            // NPDev dispatch enrichment:
            // Many flows call persistence.save(entity), but some adapters (e.g. Postgres) support save(concept, entity)
            // to map to concept-specific tables (e.g. User -> users).
            // We enrich ONLY when the chosen adapter exposes a 2-arg save method, so 1-arg test doubles keep working.
            if ("persistence".equalsIgnoreCase(effectiveCall.capability())
                    && "save".equalsIgnoreCase(effectiveCall.operation())
                    && effectiveCall.args() != null
                    && effectiveCall.args().size() == 1) {

                boolean hasTwoArgSave = false;
                for (Method m : adapter.getClass().getMethods()) {
                    if ("save".equals(m.getName()) && m.getParameterCount() == 2 && !m.getDeclaringClass().equals(Object.class)) {
                        hasTwoArgSave = true;
                        break;
                    }
                }

                if (hasTwoArgSave) {
                    Object concept = contextState == null ? null : contextState.get("_npdevEntityName");
                    if (concept != null && !String.valueOf(concept).isBlank()) {
                        List<Object> enrichedArgs = new ArrayList<>();
                        enrichedArgs.add(concept);
                        enrichedArgs.addAll(effectiveCall.args());
                        effectiveCall = new CapabilityCall(
                                effectiveCall.capability(),
                                effectiveCall.capabilityType(),
                                effectiveCall.adapterId(),
                                effectiveCall.operation(),
                                enrichedArgs,
                                effectiveCall.correlationId(),
                                effectiveCall.idempotencyKey()
                        );
                    }
                }
            }
        } catch (CapabilityBindingNotFoundException exception) {
            return CapabilityResult.failure(
                    "CAPABILITY_BINDING_MISSING",
                    exception.getMessage(),
                    CapabilityErrorKind.NOT_FOUND,
                    Map.of(
                            "capability", effectiveCall.capability(),
                            "operation", effectiveCall.operation(),
                            "adapterId", adapterId
                    )
            );
        } catch (RuntimeException exception) {
            return CapabilityResult.failure(
                    "CAPABILITY_DISPATCH_ERROR",
                    exception.getMessage() == null ? "Capability adapter resolution failed" : exception.getMessage(),
                    CapabilityErrorKind.PERMANENT,
                    Map.of(
                            "capability", effectiveCall.capability(),
                            "operation", effectiveCall.operation(),
                            "adapterId", adapterId,
                            "exceptionType", exception.getClass().getName()
                    )
            );
        }

        if (adapter instanceof CapabilityAdapter capabilityAdapter) {
            try {
                CapabilityResult result = capabilityAdapter.invoke(effectiveCall, contextState);
                if (result == null) {
                    return CapabilityResult.failure(
                            "CAPABILITY_DISPATCH_ERROR",
                            "Capability adapter returned null result for " + effectiveCall.capability() + "." + effectiveCall.operation(),
                            CapabilityErrorKind.PERMANENT,
                            Map.of(
                                    "capability", effectiveCall.capability(),
                                    "operation", effectiveCall.operation(),
                                    "adapterId", adapterId
                            )
                    );
                }
                return result;
            } catch (RuntimeException exception) {
                return CapabilityResult.failure(
                        "CAPABILITY_INVOCATION_FAILED",
                        exception.getMessage() == null
                                ? "Capability invocation failed for " + effectiveCall.capability() + "." + effectiveCall.operation()
                                : exception.getMessage(),
                        classifyInvocationError(exception),
                        Map.of(
                                "capability", effectiveCall.capability(),
                                "operation", effectiveCall.operation(),
                                "adapterId", adapterId,
                                "exceptionType", exception.getClass().getName()
                        )
                );
            }
        }

        // REG-46: an adapter on the tenant-scoped persistence port takes the executing tenant as its
        // first argument, supplied HERE from the flow's authenticated state -- never from the model's
        // declared args, which the author controls. Declared arity is therefore unchanged for every
        // existing model, and the tenant is not author-writable. See
        // TenantScopedPersistenceCapabilityContract for why that distinction is the whole point.
        List<Object> invocationArgs = effectiveCall.args();
        if (adapter instanceof TenantScopedPersistenceCapabilityContract) {
            List<Object> scoped = new ArrayList<>();
            scoped.add(TenantScope.of(tenantOf(contextState)));
            scoped.addAll(effectiveCall.args());
            invocationArgs = scoped;
        }

        Method method;
        try {
            method = resolveOperation(
                    adapter instanceof TenantScopedPersistenceCapabilityContract
                            ? TenantScopedPersistenceCapabilityContract.class
                            : adapter.getClass(),
                    adapter.getClass(),
                    effectiveCall.operation(),
                    invocationArgs.size());
        } catch (RuntimeException exception) {
            return CapabilityResult.failure(
                    "CAPABILITY_CONTRACT_VIOLATION",
                    exception.getMessage() == null ? "Capability operation resolution failed" : exception.getMessage(),
                    CapabilityErrorKind.CONTRACT,
                    Map.of(
                            "capability", effectiveCall.capability(),
                            "operation", effectiveCall.operation(),
                            "adapterId", adapterId
                    )
            );
        }

        try {
            if (method.getParameterCount() == 0) {
                return CapabilityResult.success(method.invoke(adapter));
            }
            return CapabilityResult.success(method.invoke(adapter, invocationArgs.toArray()));
        } catch (InvocationTargetException invocationTargetException) {
            Throwable cause = invocationTargetException.getCause() == null
                    ? invocationTargetException
                    : invocationTargetException.getCause();
            CapabilityErrorKind kind = classifyInvocationError(cause);
            return CapabilityResult.failure(
                    "CAPABILITY_INVOCATION_FAILED",
                    cause.getMessage() == null
                            ? "Capability invocation failed for " + effectiveCall.capability() + "." + effectiveCall.operation()
                            : cause.getMessage(),
                    kind,
                    Map.of(
                            "capability", effectiveCall.capability(),
                            "operation", effectiveCall.operation(),
                            "adapterId", adapterId,
                            "exceptionType", cause.getClass().getName()
                    )
            );
        } catch (ReflectiveOperationException reflectiveOperationException) {
            return CapabilityResult.failure(
                    "CAPABILITY_DISPATCH_ERROR",
                    "Capability invocation failed for " + effectiveCall.capability() + "." + effectiveCall.operation(),
                    CapabilityErrorKind.PERMANENT,
                    Map.of(
                            "capability", effectiveCall.capability(),
                            "operation", effectiveCall.operation(),
                            "adapterId", adapterId,
                            "exceptionType", reflectiveOperationException.getClass().getName()
                    )
            );
        }
    }

    private void validateCallContract(CapabilityCall call) {
        registry.findContract(call.capability(), call.capabilityType())
                .ifPresent(contract -> contract.resolveOperation(call.operation()));
    }

    /**
     * REG-46: the executing tenant, taken from the flow state the kernel stamps with the authenticated
     * ExecutionContext's tenantId. Falls back to "default" for the same reason KernelRunner does --
     * that is the platform's no-tenant sentinel, and an authorization policy already denies under it,
     * so an unstamped call fails closed rather than reading across tenants.
     */
    private static String tenantOf(Map<String, Object> contextState) {
        Object tenant = contextState == null ? null : contextState.get("tenantId");
        String tenantId = tenant == null ? null : String.valueOf(tenant).trim();
        return (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;
    }

    /**
     * @param searchType   where to look for the operation. For a tenant-scoped persistence adapter this
     *                     is the INTERFACE, so the class's same-arity legacy overloads cannot make the
     *                     lookup ambiguous (REG-46).
     * @param reportedType the concrete adapter class, used only in error messages -- naming the
     *                     interface there would send a reader to the wrong file.
     */
    private static Method resolveOperation(Class<?> searchType, Class<?> reportedType, String operation, int argCount) {
        Class<?> type = searchType;
        List<Method> candidates = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(operation)) {
                continue;
            }
            if (method.getDeclaringClass().equals(Object.class)) {
                continue;
            }
            if (method.getParameterCount() == argCount) {
                candidates.add(method);
            }
        }

        if (candidates.isEmpty()) {
            throw new IllegalStateException("Operation not found: " + operation + " on adapter " + reportedType.getName());
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException("Ambiguous capability operation " + operation
                    + " with " + argCount + " arguments on adapter " + reportedType.getName()
                    + ". Use non-overloaded operation names.");
        }
        return candidates.get(0);
    }

    private static CapabilityErrorKind classifyInvocationError(Throwable throwable) {
        // Walk the cause chain: an adapter contract failure (e.g. IllegalArgumentException for a
        // FK/unique integrity violation) is wrapped by reflective/async machinery before it reaches
        // here, and inspecting only the top-level throwable would misread it as PERMANENT
        // (system_exception). Classify by the most specific cause — a thrown integrity violation is a
        // caller CONTRACT failure, not a system error.
        Throwable current = throwable;
        for (int guard = 0; current != null && guard < 16; guard++) {
            if (current instanceof IllegalArgumentException
                    || current instanceof ClassCastException
                    || current instanceof UnsupportedOperationException) {
                return CapabilityErrorKind.CONTRACT;
            }
            String causeMessage = current.getMessage() == null
                    ? ""
                    : current.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (causeMessage.contains("referential integrity")
                    || causeMessage.contains("integrity constraint")
                    || causeMessage.contains("foreign key")
                    || causeMessage.contains("unique index or primary key")
                    || causeMessage.contains("unique constraint")) {
                return CapabilityErrorKind.CONTRACT;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }

        String typeName = throwable.getClass().getName().toLowerCase();
        String message = throwable.getMessage() == null ? "" : throwable.getMessage().toLowerCase();
        if (typeName.contains("auth")
                || typeName.contains("forbidden")
                || message.contains("unauthorized")
                || message.contains("forbidden")
                || message.contains("access denied")) {
            return CapabilityErrorKind.AUTH;
        }
        if (typeName.contains("rate")
                || message.contains("rate limit")
                || message.contains("too many requests")) {
            return CapabilityErrorKind.RATE_LIMIT;
        }
        if (typeName.contains("timeout")
                || message.contains("timeout")) {
            return CapabilityErrorKind.TIMEOUT;
        }
        if (typeName.contains("transient")
                || typeName.contains("temporar")) {
            return CapabilityErrorKind.TRANSIENT;
        }
        return CapabilityErrorKind.PERMANENT;
    }
}
