package com.npdev.dsl.v1.compiled;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wave 3 (RC-B1): compiled form of {@link com.npdev.dsl.v1.ast.RoleAst} -- an app-defined role and
 * the platform permission names it MAY hold (a declared ceiling, checked structurally by
 * {@code RoleValidation} at compile time and resolved against the real
 * {@code com.npdev.kernel.auth.Permission} enum at runtime boot).
 */
public record CompiledRole(String name, List<String> grants, Map<String, List<String>> concepts, CompiledOrigin origin) {
    /** Operations a role may be granted per concept under {@code concepts}; {@code read} also covers list. */
    public static final List<String> CONCEPT_OPERATIONS = List.of("read", "create", "update", "delete");

    public CompiledRole {
        grants = grants == null ? List.of() : List.copyOf(grants);
        Map<String, List<String>> copied = new LinkedHashMap<>();
        if (concepts != null) {
            concepts.forEach((concept, operations) ->
                    copied.put(concept, operations == null ? List.of() : List.copyOf(operations)));
        }
        concepts = Collections.unmodifiableMap(copied);
    }

    /** Pre-roles-and-users constructor -- no concept grants. */
    public CompiledRole(String name, List<String> grants, CompiledOrigin origin) {
        this(name, grants, Map.of(), origin);
    }

    /** Pre-PACK-2 convenience constructor -- origin defaults to null (not pack-contributed). */
    public CompiledRole(String name, List<String> grants) {
        this(name, grants, Map.of(), null);
    }
}
