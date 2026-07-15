package com.finalexec.config;

import com.npdev.generated.runtime.config.RuntimeApiKeyAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

/**
 * LNCH-8: {@code /actuator/metrics} and {@code /actuator/prometheus} expose internal counts,
 * tenant tags, and capability/flow names -- unlike {@code /actuator/health} (which must stay
 * unauthenticated for Docker/orchestrator healthchecks with no credentials, per LNCH-7), these
 * must not be publicly reachable. Reuses the SAME SUPERUSER claim
 * {@link com.finalexec.controlpanel.SuperUserCredentialAuthFilter} already resolves from the
 * {@code X-Super-User-Key} header (registered at a lower filter order so it runs first) rather
 * than inventing a second gating mechanism -- see docs/LAUNCH_READINESS_GAPS.md's own warning
 * not to repeat the workspace::Preference built-in-pack gating bug (a prior latent bug where a
 * built-in pack's admin-only data ended up reachable without the gate actually being enforced).
 */
public class ActuatorAdminGuardFilter extends OncePerRequestFilter {

    private static final String REQUIRED_ROLE = "SUPERUSER";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Object claims = request.getAttribute(RuntimeApiKeyAuthFilter.CLAIMS_ATTRIBUTE);
        if (!(claims instanceof Map<?, ?> claimsMap) || !hasSuperuserRole(claimsMap.get("roles"))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"actuator_metrics_requires_super_user_key\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean hasSuperuserRole(Object roles) {
        return roles instanceof Collection<?> collection && collection.contains(REQUIRED_ROLE);
    }
}
