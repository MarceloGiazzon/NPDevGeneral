package com.npdev.dsl.v1.ast;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 3 (RC-B1, {@code MOVE11_RUNTIME_CONFIGURATION_PLAN}
 * Part B.1): an app-defined role and the platform permissions it MAY hold -- a declared CEILING,
 * not an assignment (RC-B3, deferred, is what lets an administrator move within this ceiling
 * without a rebuild).
 *
 * <p>{@code grants} is a list of {@code com.npdev.kernel.auth.Permission} enum-constant names as
 * plain strings. The DSL module has no dependency on the kernel module (kernel depends on dsl,
 * never the reverse), so {@link #PLATFORM_PERMISSIONS} is a hand-kept copy of that enum's
 * constants, pinned to it by {@code PlatformPermissionMirrorTest} (which reads the kernel source
 * and fails on any drift). Validation rejects any other name, so a typo or a
 * {@code "workorder:create"}-style grant fails {@code npdev validate model} instead of crashing
 * {@code Permission.valueOf} at app boot. Matching is case-insensitive, as it is in the kernel.
 */
public record RoleAst(String name, List<String> grants, Map<String, List<String>> concepts, OriginAst origin) {
    /** Operations a role may be granted per concept under {@code concepts}; {@code read} also covers list. */
    public static final List<String> CONCEPT_OPERATIONS = List.of("read", "create", "update", "delete");

    /**
     * Mirror of {@code com.npdev.kernel.auth.Permission}, in declaration order -- the only names a
     * {@code grants} entry may hold. Keep in lockstep with the kernel enum ({@code
     * PlatformPermissionMirrorTest} fails otherwise).
     */
    public static final List<String> PLATFORM_PERMISSIONS = List.of(
            "EXECUTE_FLOW",
            "READ_FLOW_DEFINITIONS",
            "READ_EXECUTIONS",
            "RESUME_EXECUTIONS",
            "PUBLISH_EVENTS",
            "READ_TRACES",
            "READ_AUDIT",
            "READ_ADMIN_HEALTH",
            "READ_FAILURES",
            "READ_STUCK",
            "READ_EVENTS");

    public RoleAst {
        grants = grants == null ? List.of() : List.copyOf(grants);
        Map<String, List<String>> copied = new LinkedHashMap<>();
        if (concepts != null) {
            concepts.forEach((concept, operations) ->
                    copied.put(concept, operations == null ? List.of() : List.copyOf(operations)));
        }
        concepts = Collections.unmodifiableMap(copied);
    }

    /** Pre-roles-and-users constructor -- no concept grants. */
    public RoleAst(String name, List<String> grants, OriginAst origin) {
        this(name, grants, Map.of(), origin);
    }

    /** Pre-PACK-2 convenience constructor -- origin defaults to null (not pack-contributed). */
    public RoleAst(String name, List<String> grants) {
        this(name, grants, Map.of(), null);
    }
}
