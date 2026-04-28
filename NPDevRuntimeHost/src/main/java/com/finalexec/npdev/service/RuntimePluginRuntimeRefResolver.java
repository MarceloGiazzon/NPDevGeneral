package com.finalexec.npdev.service;

import com.npdev.kernel.ports.CapabilityAdapter;

import java.util.Locale;
import java.util.Objects;

public final class RuntimePluginRuntimeRefResolver {

    private final RuntimePluginRealizationProviderCatalog providerCatalog;

    public RuntimePluginRuntimeRefResolver(
            java.util.List<RuntimePluginRealizationProvider> realizationProviders
    ) {
        this(new RuntimePluginRealizationProviderCatalog(realizationProviders));
    }

    public RuntimePluginRuntimeRefResolver(
            RuntimePluginRealizationProviderCatalog providerCatalog
    ) {
        this.providerCatalog = Objects.requireNonNull(providerCatalog, "providerCatalog");
    }

    public Object resolve(RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        if (!"runtimeref".equals(normalize(contribution.implementationKind()))) {
            throw new IllegalStateException(
                    "Unsupported plugin implementation kind '%s' for adapter '%s'"
                            .formatted(contribution.implementationKind(), contribution.adapterId())
            );
        }

        Object handler;
        try {
            handler = providerCatalog.realize(contribution.runtimeRef());
        } catch (IllegalStateException exception) {
            throw new IllegalStateException(
                    "Unknown runtimeRef '%s' for adapter '%s'"
                            .formatted(contribution.runtimeRef(), contribution.adapterId()),
                    exception
            );
        }
        validateResolvedHandler(handler, contribution);
        return handler;
    }

    public RuntimePluginRealizationProviderCatalog.Summary providerBoundarySummary() {
        return providerCatalog.toSummary();
    }

    private static void validateResolvedHandler(
            Object handler,
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution
    ) {
        if (!(handler instanceof CapabilityAdapter capabilityAdapter)) {
            return;
        }
        if (!normalize(capabilityAdapter.capability()).equals(normalize(contribution.capability()))) {
            throw new IllegalStateException(
                    "Resolved runtimeRef '%s' produced capability '%s' but manifest declares '%s'"
                            .formatted(contribution.runtimeRef(), capabilityAdapter.capability(), contribution.capability())
            );
        }
        if (!normalize(capabilityAdapter.adapterId()).equals(normalize(contribution.adapterId()))) {
            throw new IllegalStateException(
                    "Resolved runtimeRef '%s' produced adapterId '%s' but manifest declares '%s'"
                            .formatted(contribution.runtimeRef(), capabilityAdapter.adapterId(), contribution.adapterId())
            );
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
