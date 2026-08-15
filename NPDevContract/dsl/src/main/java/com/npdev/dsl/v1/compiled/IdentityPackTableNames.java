package com.npdev.dsl.v1.compiled;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * REG-170/REG-177: the built-in identity pack's four concept table names, resolved from the
 * compiled model rather than hardcoded -- the generator's schema-realization SQL creates these
 * under pack-versioned names (e.g. {@code identity_v1_users}), not the pre-versioning literals
 * ({@code identity_users}, {@code identity_roles}, {@code identity_user_roles},
 * {@code identity_user_role_permissions}) several call sites used to hardcode.
 *
 * <p>Lives in {@code NPDevContract/dsl} (not {@code NPDevRuntimeHost}) because every caller needing
 * this resolution already depends on {@link CompiledModel} from here, and two of the callers
 * (REG-177: {@code IdentityRoleLookup}, {@code IdentityPermissionOverrideLookup}) are
 * {@code NPDevKernel} adapters that cannot depend on {@code NPDevRuntimeHost} (the dependency runs
 * the other way) -- a single shared type here is the only home reachable from both RuntimeHost
 * controllers and kernel adapters without a wrong-direction module dependency.
 *
 * <p>Resolves ONCE per caller (a Spring-managed bean resolves this in its own constructor, from its
 * already-injected {@code CompiledModel}, and passes the result to every method that needs a table
 * name) rather than each call site hardcoding a second, independently-drifting literal. Throws
 * {@code IllegalStateException} if a concept is missing -- unreachable for any caller actually gated
 * on the identity pack being composed, so a hard failure here indicates a genuine platform
 * inconsistency, not a normal runtime condition (the policy REG-160/REG-170 already established).
 */
public record IdentityPackTableNames(
        String usersTable,
        String rolesTable,
        String userRolesTable,
        String userRolePermissionsTable
) {
    private static final Logger LOG = Logger.getLogger(IdentityPackTableNames.class.getName());

    public static IdentityPackTableNames resolve(CompiledModel compiledModel) {
        return new IdentityPackTableNames(
                resolveTable(compiledModel, "identity::User"),
                resolveTable(compiledModel, "identity::Role"),
                resolveTable(compiledModel, "identity::UserRole"),
                resolveTable(compiledModel, "identity::UserRolePermission"));
    }

    /**
     * REG-177: the graceful counterpart to {@link #resolve}, for a caller that must tolerate the
     * identity pack being genuinely absent as a normal, valid state -- e.g. {@code
     * GeneratedCrudRuntimeSupport}/{@code IdentityAwareContextResolver}, which run for EVERY app
     * request regardless of whether that app composes the identity pack at all ({@code
     * internal.tables=false} is a supported configuration, not an error). {@link #resolve} stays
     * throwing for callers that are only ever active when the identity pack is already guaranteed
     * present (jwt-auth-mode-gated controllers, SUPERUSER-only ControlPanel tenant-admin actions) --
     * changing its contract would silently swallow a genuine platform inconsistency for those.
     *
     * <p>The two empty-return paths mean different things and must stay distinguishable in the
     * logs even though both look the same to the caller: {@code identity::User} missing entirely
     * is a normal, silent no-op (this app simply never composed the identity pack -- REG-39 does
     * not apply, there is no fault to report). {@code identity::User} present but ANOTHER concept
     * (Role/UserRole/UserRolePermission) missing or table-name-less is a genuinely inconsistent,
     * partially-composed identity pack -- an infrastructure fault that must not silently resolve to
     * "no restriction" indistinguishably from "feature never used" (REG-39's rule, the security-
     * pattern sweep's own swallowed-security-exception check caught this not being logged).</p>
     */
    public static Optional<IdentityPackTableNames> tryResolve(CompiledModel compiledModel) {
        if (compiledModel.findConcept("identity::User").isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(resolve(compiledModel));
        } catch (IllegalStateException incomplete) {
            LOG.severe("IdentityPackTableNames.tryResolve: identity::User is composed but the identity pack "
                    + "is otherwise incomplete (a Role/UserRole/UserRolePermission concept is missing or has "
                    + "no usable table name) -- treating as absent rather than failing the caller, but this is "
                    + "a genuine platform inconsistency, not a normal 'identity pack unused' state: "
                    + incomplete.getMessage());
            return Optional.empty();
        }
    }

    private static String resolveTable(CompiledModel compiledModel, String conceptName) {
        return compiledModel.findConcept(conceptName)
                .map(CompiledConcept::getTableName)
                .filter(name -> name != null && !name.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "IdentityPackTableNames: compiled model has no usable table name for concept '"
                                + conceptName + "' -- every caller of this class is only active when the "
                                + "identity pack is composed, so this should be unreachable"));
    }
}
