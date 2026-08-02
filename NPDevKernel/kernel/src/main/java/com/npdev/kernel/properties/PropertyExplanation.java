package com.npdev.kernel.properties;

import java.util.List;

/**
 * RC-A3's required {@code explain()} shape (see {@code __OutsideRepo/move13-helpers/
 * rc-a3-cascade-vectors.json}'s {@code explainContract}): names the winning scope and every
 * less-specific scope it overrode, in cascade order, ending with the model's declared default.
 */
public record PropertyExplanation(String key, Object value, ScopeRef source, List<OverriddenScope> overrode) {

    public PropertyExplanation {
        overrode = overrode == null ? List.of() : List.copyOf(overrode);
    }

    /**
     * The scope that won. {@code scopeId} is {@code null} when {@code scopeType} is {@code "default"}
     * (the model's declared default, not a stored row -- distinguishes "nothing stored anywhere" from
     * an explicit-null row winning with the same {@code null} value, per vector 10 vs. vector 7).
     */
    public record ScopeRef(String scopeType, String scopeId) {
        public static final ScopeRef DEFAULT = new ScopeRef("default", null);
    }

    /** A less-specific scope the winner overrode. {@code scopeId} is {@code null} for the default entry. */
    public record OverriddenScope(String scopeType, String scopeId, Object value) {
    }
}
