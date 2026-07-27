package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ADR-0009: app-level external-AI delegation settings. {@code egress} defaults to {@code "denied"}
 * -- a model that declares no {@code externalAi} block at all keeps that same default (see
 * {@link ModelAst#getExternalAi()}, which returns {@code null} in that case; this class only
 * exists once the author has actually written an {@code externalAi} object).
 */
public final class ExternalAiAst {
    private final String egress;
    private final List<String> vendors;

    public ExternalAiAst(String egress, List<String> vendors) {
        this.egress = egress == null || egress.isBlank() ? "denied" : egress;
        this.vendors = vendors == null ? List.of() : new ArrayList<>(vendors);
    }

    public String getEgress() { return egress; }
    /** Vendor ids (e.g. "openai") this app has opted into -- required non-empty once egress != denied. */
    public List<String> getVendors() { return Collections.unmodifiableList(vendors); }
}
