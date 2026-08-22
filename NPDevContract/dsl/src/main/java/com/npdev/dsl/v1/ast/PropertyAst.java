package com.npdev.dsl.v1.ast;

import java.util.List;
import java.util.Map;

/**
 * MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 6 (RC-A1, {@code MOVE11_RUNTIME_CONFIGURATION_PLAN}
 * Part A.1): a declared, typed, scoped runtime property -- the unit the scoped-property cascade
 * (A2 storage, A3 resolver, A4 expressions, A5 admin UI) resolves per key, never as part of a
 * blob. {@code type} is a closed set ({@code string}/{@code int}/{@code boolean}/{@code enum}/
 * {@code date}) -- no objects, no arrays, checked by {@code PropertyValidation} at compile time
 * alongside {@code default}'s shape and every name in {@code settableAt} against the model's
 * declared {@link PropertyScopeAst} list.
 *
 * <p>{@code securityRelevant} is a real flag with teeth, not documentation: {@code PropertyValidation}
 * forbids {@code $prop.<name>} for a security-relevant property inside {@code access.read}/
 * {@code access.write} (A4's hard rule -- an administrator-changeable value must never become a
 * runtime-mutable authorization rule, which would bypass the authoring contract's review guarantee
 * for every OTHER permission change).
 */
public record PropertyAst(
        String name,
        String type,
        Object defaultValue,
        List<String> settableAt,
        String label,
        boolean securityRelevant,
        Map<String, String> labelLocales
) {
    public PropertyAst {
        settableAt = settableAt == null ? List.of() : List.copyOf(settableAt);
        labelLocales = (labelLocales == null || labelLocales.isEmpty()) ? Map.of() : Map.copyOf(labelLocales);
    }
}
