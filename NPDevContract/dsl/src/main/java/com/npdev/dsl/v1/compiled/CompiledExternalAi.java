package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** ADR-0009: app-level external-AI delegation settings, compiled from {@code ExternalAiAst}. */
public final class CompiledExternalAi {
    private final String egress;
    private final List<String> vendors;
    private final List<CompiledExternalAiPrompt> prompts;
    private final String callsPerUserPerDayProperty;
    private final String monthlyCostCapUsdProperty;

    public CompiledExternalAi(String egress, List<String> vendors) {
        this(egress, vendors, List.of(), null, null);
    }

    public CompiledExternalAi(String egress, List<String> vendors, List<CompiledExternalAiPrompt> prompts,
                              String callsPerUserPerDayProperty, String monthlyCostCapUsdProperty) {
        this.egress = egress == null || egress.isBlank() ? "denied" : egress;
        this.vendors = vendors == null ? List.of() : new ArrayList<>(vendors);
        this.prompts = prompts == null ? List.of() : new ArrayList<>(prompts);
        this.callsPerUserPerDayProperty = callsPerUserPerDayProperty;
        this.monthlyCostCapUsdProperty = monthlyCostCapUsdProperty;
    }

    public String getEgress() { return egress; }
    public List<String> getVendors() { return Collections.unmodifiableList(vendors); }
    public List<CompiledExternalAiPrompt> getPrompts() { return Collections.unmodifiableList(prompts); }
    public String getCallsPerUserPerDayProperty() { return callsPerUserPerDayProperty; }
    public String getMonthlyCostCapUsdProperty() { return monthlyCostCapUsdProperty; }

    public Optional<CompiledExternalAiPrompt> findPrompt(String name) {
        return prompts.stream().filter(prompt -> prompt.name().equals(name)).findFirst();
    }
}
