package com.npdev.dsl.v1.compiled;

import java.util.List;

/**
 * Wave 3 (RC-B1): compiled form of {@link com.npdev.dsl.v1.ast.RoleAst} -- an app-defined role and
 * the platform permission names it MAY hold (a declared ceiling, checked structurally by
 * {@code RoleValidation} at compile time and resolved against the real
 * {@code com.npdev.kernel.auth.Permission} enum at runtime boot).
 */
public record CompiledRole(String name, List<String> grants) {
    public CompiledRole {
        grants = grants == null ? List.of() : List.copyOf(grants);
    }
}
