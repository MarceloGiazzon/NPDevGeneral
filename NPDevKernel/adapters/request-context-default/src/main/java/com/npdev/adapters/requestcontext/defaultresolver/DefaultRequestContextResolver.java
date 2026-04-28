package com.npdev.adapters.requestcontext.defaultresolver;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.RequestContextResolver;

import java.util.Map;

/**
 * Default resolver for tenant and actor headers.
 * Security fields such as roles/tags must come from authenticated claims,
 * not client-controlled headers.
 */
public final class DefaultRequestContextResolver implements RequestContextResolver {
    @Override
    public ExecutionContext resolve(String tenantIdHeader, String actorIdHeader, Map<String, String> headers) {
        // Intentionally ignore header-driven roles/tags to avoid spoofable identity inputs.
        return ExecutionContext.of(tenantIdHeader, actorIdHeader);
    }
}
