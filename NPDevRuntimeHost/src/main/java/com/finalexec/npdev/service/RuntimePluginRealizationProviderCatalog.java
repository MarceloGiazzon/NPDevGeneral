package com.finalexec.npdev.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class RuntimePluginRealizationProviderCatalog {

    private final Map<String, RuntimePluginRealizationProvider> providersByRuntimeRef;

    public RuntimePluginRealizationProviderCatalog(
            List<RuntimePluginRealizationProvider> realizationProviders
    ) {
        Objects.requireNonNull(realizationProviders, "realizationProviders");
        this.providersByRuntimeRef = realizationProviders.stream()
                .collect(Collectors.toUnmodifiableMap(
                        provider -> normalize(provider.runtimeRef()),
                        Function.identity(),
                        (left, right) -> {
                            throw new IllegalStateException("Duplicate runtimeRef realization provider: " + left.runtimeRef());
                        }
                ));
    }

    public RuntimePluginRealizationProvider requireProvider(String runtimeRef) {
        String normalizedRuntimeRef = normalize(runtimeRef);
        RuntimePluginRealizationProvider provider = providersByRuntimeRef.get(normalizedRuntimeRef);
        if (provider == null) {
            throw new IllegalStateException("Unknown runtimeRef '%s'".formatted(runtimeRef));
        }
        return provider;
    }

    public Object realize(String runtimeRef) {
        return requireProvider(runtimeRef).realize();
    }

    public Summary toSummary() {
        return new Summary(
                "runtimeref-provider-catalog",
                providersByRuntimeRef.keySet().stream()
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList()
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record Summary(
            String boundaryKind,
            List<String> runtimeRefs
    ) {

        public Summary {
            boundaryKind = Objects.requireNonNull(boundaryKind, "boundaryKind").trim().toLowerCase(Locale.ROOT);
            runtimeRefs = List.copyOf(Objects.requireNonNull(runtimeRefs, "runtimeRefs"));
        }
    }
}
