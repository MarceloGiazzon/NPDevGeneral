package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ADR-0009: app-level external-AI delegation settings. {@code egress} defaults to {@code "denied"}
 * -- a model that declares no {@code externalAi} block at all keeps that same default (see
 * {@link ModelAst#getExternalAi()}, which returns {@code null} in that case; this class only
 * exists once the author has actually written an {@code externalAi} object).
 *
 * <p>P4 (G3): {@code prompts} are the named prompts flows may run; {@code callsPerUserPerDayProperty}
 * and {@code monthlyCostCapUsdProperty} name declared {@code properties[]} keys whose cascade-resolved
 * values bound them (absent = unbounded on that axis).</p>
 */
public final class ExternalAiAst {
    private final String egress;
    private final List<String> vendors;
    private final List<ExternalAiPromptAst> prompts;
    private final String callsPerUserPerDayProperty;
    private final String monthlyCostCapUsdProperty;

    public ExternalAiAst(String egress, List<String> vendors) {
        this(egress, vendors, List.of(), null, null);
    }

    public ExternalAiAst(String egress, List<String> vendors, List<ExternalAiPromptAst> prompts,
                         String callsPerUserPerDayProperty, String monthlyCostCapUsdProperty) {
        this.egress = egress == null || egress.isBlank() ? "denied" : egress;
        this.vendors = vendors == null ? List.of() : new ArrayList<>(vendors);
        this.prompts = prompts == null ? List.of() : new ArrayList<>(prompts);
        this.callsPerUserPerDayProperty = callsPerUserPerDayProperty;
        this.monthlyCostCapUsdProperty = monthlyCostCapUsdProperty;
    }

    public String getEgress() { return egress; }
    /** Vendor ids (e.g. "openai") this app has opted into -- required non-empty once egress != denied. */
    public List<String> getVendors() { return Collections.unmodifiableList(vendors); }
    public List<ExternalAiPromptAst> getPrompts() { return Collections.unmodifiableList(prompts); }
    public String getCallsPerUserPerDayProperty() { return callsPerUserPerDayProperty; }
    public String getMonthlyCostCapUsdProperty() { return monthlyCostCapUsdProperty; }
}
