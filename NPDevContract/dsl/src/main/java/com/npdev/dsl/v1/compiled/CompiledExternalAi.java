package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** ADR-0009: app-level external-AI delegation settings, compiled from {@code ExternalAiAst}. */
public final class CompiledExternalAi {
    private final String egress;
    private final List<String> vendors;

    public CompiledExternalAi(String egress, List<String> vendors) {
        this.egress = egress == null || egress.isBlank() ? "denied" : egress;
        this.vendors = vendors == null ? List.of() : new ArrayList<>(vendors);
    }

    public String getEgress() { return egress; }
    public List<String> getVendors() { return Collections.unmodifiableList(vendors); }
}
