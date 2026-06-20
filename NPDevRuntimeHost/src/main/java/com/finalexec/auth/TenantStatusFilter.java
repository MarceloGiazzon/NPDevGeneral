package com.finalexec.auth;

import com.finalexec.npdev.service.TenantRegistryService;
import com.npdev.generated.runtime.config.RuntimeApiKeyAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Single per-request chokepoint that gives tenant "disable" real teeth: a request whose
 * authenticated principal belongs to an explicitly DISABLED tenant is rejected with 403, regardless
 * of which endpoint (generated CRUD, admin controllers, flow execution) it targets. Runs after the
 * authentication filter has set the claims attribute. Requests with no tenant claim (unauthenticated
 * or auth-disabled) pass through untouched; the registry is fail-open for everything except an
 * explicit DISABLED status (see {@link TenantRegistryService#isActive}).
 */
public final class TenantStatusFilter extends OncePerRequestFilter {

    private final TenantRegistryService tenantRegistryService;

    public TenantStatusFilter(TenantRegistryService tenantRegistryService) {
        this.tenantRegistryService = tenantRegistryService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String tenantId = tenantFromClaims(request.getAttribute(RuntimeApiKeyAuthFilter.CLAIMS_ATTRIBUTE));
        if (tenantId != null && !tenantRegistryService.isActive(tenantId)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"tenant_disabled\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String tenantFromClaims(Object rawClaims) {
        if (!(rawClaims instanceof Map<?, ?> claims)) {
            return null;
        }
        Object tenant = claims.get("tenant_id");
        if (tenant == null) {
            return null;
        }
        String value = String.valueOf(tenant).trim();
        return value.isBlank() ? null : value;
    }
}
