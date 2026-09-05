package com.finalexec.npdev.service.pluginipc;

import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B30/SEC-9: the pure, framework-boundary-free half of building the isolated dispatch path's
 * {@code HandlerMapping} -- extracted out of {@code PluginControllerSecurityConfig} (app tree) so it
 * can be unit-tested here. {@code PluginControllerSecurityConfig} owns only the part that genuinely
 * needs {@code RuntimeContextService} (building each mount's {@code MinimumRoleInterceptor}); this
 * class owns the urlMap/mapping construction, which needs nothing generated-app-specific.
 *
 * <p>{@code NPDevRuntimeHost/src/main}'s own coverage ratchet cannot be moved by a test of a
 * runtimehost-core class (measured 2026-09-03, {@code feedback_runtimehost_coverage_ratchet_scope}) --
 * this split keeps the app-tree class's own new surface to a single delegating call plus the
 * interceptor-construction loop, minimizing what that ratchet cannot ever see covered for a sample
 * with no mounted controller.
 */
public final class PluginControllerHandlerMappingSupport {

    private PluginControllerHandlerMappingSupport() {
    }

    /** {@code null} (no bean) when no controller is mounted -- matches every other manifest-driven
     *  bean's own graceful-absence convention in this package. */
    public static SimpleUrlHandlerMapping buildHandlerMapping(
            PluginControllerRouteManifest routeManifest,
            Object proxyHandler,
            HandlerInterceptor[] roleInterceptors
    ) {
        if (routeManifest.isEmpty()) {
            return null;
        }
        Map<String, Object> urlMap = new LinkedHashMap<>();
        for (PluginControllerRouteManifest.Entry entry : routeManifest.byCapability().values()) {
            urlMap.put(entry.basePath() + "/**", proxyHandler);
        }
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
        mapping.setUrlMap(urlMap);
        mapping.setInterceptors(roleInterceptors);
        return mapping;
    }
}
