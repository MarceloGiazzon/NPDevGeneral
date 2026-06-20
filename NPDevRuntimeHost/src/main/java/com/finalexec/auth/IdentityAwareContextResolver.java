package com.finalexec.auth;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.AuthenticatedContextResolver;
import com.npdev.runtime.support.IdentityRoleLookup;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Makes the built-in identity pack (identity_users / identity_roles / identity_user_roles)
 * load-bearing for authorization, instead of trusting the roles encoded in the api-key / JWT
 * principal blindly.
 *
 * <p>Resolution is <b>supplement-with-fallback</b>, deliberately non-breaking: the wrapped delegate
 * resolves the base {@link ExecutionContext} (tenant + actor + claim-roles) exactly as before; then,
 * IF a matching active identity user with role assignments exists for that (tenant, actor), the
 * persisted roles become authoritative and replace the claim-roles. When the identity tables are
 * absent ({@code internal.tables=false}) or hold no matching user, the claim-roles stand — so apps
 * that don't use the identity pack are unaffected, and the very first ADMIN (who has no identity row
 * yet) can still bootstrap the identity data via the claim-role fallback.</p>
 */
public final class IdentityAwareContextResolver implements AuthenticatedContextResolver {

    private final AuthenticatedContextResolver delegate;
    private final ObjectProvider<DataSource> dataSourceProvider;

    public IdentityAwareContextResolver(
            AuthenticatedContextResolver delegate,
            ObjectProvider<DataSource> dataSourceProvider
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.dataSourceProvider = Objects.requireNonNull(dataSourceProvider, "dataSourceProvider");
    }

    @Override
    public ExecutionContext resolveFromPrincipal(Map<String, Object> claims, Map<String, String> headers) {
        ExecutionContext base = delegate.resolveFromPrincipal(claims, headers);
        Set<String> identityRoles = IdentityRoleLookup.rolesFor(
                dataSourceProvider.getIfAvailable(), base.tenantId(), base.actorId());
        return identityRoles.isEmpty() ? base : base.withRoles(identityRoles);
    }
}
