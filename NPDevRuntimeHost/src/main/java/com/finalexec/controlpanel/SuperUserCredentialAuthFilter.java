package com.finalexec.controlpanel;

import com.finalexec.npdev.service.CredentialRegistryService;
import com.npdev.generated.runtime.config.RuntimeApiKeyAuthFilter;
import com.npdev.kernel.ports.ApiKeyCredentialResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the ControlPanel's own {@code X-Super-User-Key} header into request claims, completely
 * independent of an app's business {@code auth.mode} (apiKey / jwt / none). Deliberately a separate
 * header and mechanism from the business {@code X-Api-Key} path
 * ({@code com.npdev.generated.runtime.config.RuntimeApiKeyAuthFilter}): that filter is only wired
 * when {@code auth.mode=apikey}, and it carries a well-known default {@code dev-key}/{@code api-dev}
 * mapping that must not accidentally become live just because this filter is unconditionally
 * registered. This filter never consults that static mapping -- only
 * {@link CredentialRegistryService#resolve}, which only ever returns a hit for a credential that was
 * actually issued (hash-at-rest, shown once).
 *
 * <p>No-op whenever the header is absent, so it never interferes with the JWT/apiKey filters or with
 * requests that don't concern the ControlPanel at all. When the header IS present but doesn't
 * resolve to a valid SUPERUSER credential, this filter rejects immediately with a clear
 * {@code invalid_super_user_key} error rather than silently falling through -- letting the request
 * continue down the chain in that case previously surfaced the JWT filter's unrelated
 * {@code missing_bearer_token} error instead, which is meaningless to someone who just typed a
 * Super User key.</p>
 */
public class SuperUserCredentialAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Super-User-Key";
    /**
     * REG-22: request attribute set ONLY after a presented {@code X-Super-User-Key} resolves live to
     * an ACTIVE SUPERUSER credential. Downstream role gates that must be reachable exclusively via the
     * super-key path (e.g. {@code ActuatorAdminGuardFilter}) key on this marker rather than on a
     * SUPERUSER role in the claims, which a business JWT could also carry and which is not re-checked
     * for revocation at filter level.
     */
    public static final String SUPER_USER_AUTHENTICATED_ATTRIBUTE = "npdev.auth.superuser.authenticated";
    private static final String REQUIRED_ROLE = "SUPERUSER";

    private final CredentialRegistryService credentialRegistryService;

    public SuperUserCredentialAuthFilter(CredentialRegistryService credentialRegistryService) {
        this.credentialRegistryService = credentialRegistryService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return normalize(request.getHeader(HEADER_NAME)) == null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String key = normalize(request.getHeader(HEADER_NAME));
        Optional<ApiKeyCredentialResolver.Principal> resolved = credentialRegistryService.resolve(key);
        if (resolved.isEmpty() || !resolved.get().roles().contains(REQUIRED_ROLE)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("""
                {"error":"super_user_auth_failed","boundaryId":"B17","message":"Super-user key was rejected. Read the key from SUPER_USER_KEY.txt in the app's working directory."}""");
            return;
        }
        ApiKeyCredentialResolver.Principal principal = resolved.get();
        request.setAttribute(RuntimeApiKeyAuthFilter.CLAIMS_ATTRIBUTE, Map.of(
                "tenant_id", principal.tenantId(),
                "actor_id", principal.actorId(),
                "roles", principal.roles()
        ));
        // REG-22: mark that SUPERUSER on this request came from a live-validated super-key, not a
        // (possibly stale/revoked) SUPERUSER role carried in some other credential's claims.
        request.setAttribute(SUPER_USER_AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);
        filterChain.doFilter(request, response);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
