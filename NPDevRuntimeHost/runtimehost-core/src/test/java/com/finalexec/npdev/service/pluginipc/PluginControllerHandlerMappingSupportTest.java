package com.finalexec.npdev.service.pluginipc;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginControllerHandlerMappingSupportTest {

    @Test
    void returnsNullWhenNoControllerIsMounted() {
        SimpleUrlHandlerMapping mapping = PluginControllerHandlerMappingSupport.buildHandlerMapping(
                PluginControllerRouteManifest.empty(), null, new HandlerInterceptor[0]);

        assertNull(mapping);
    }

    @Test
    void mapsEveryMountsBasePathToTheProxyHandlerAndAttachesInterceptors() {
        PluginControllerRouteManifest manifest = new PluginControllerRouteManifest(Map.of(
                "adminTools", new PluginControllerRouteManifest.Entry(
                        "adminTools", "com.example.AdminToolsController", "/api/plugins/admin-tools", List.of())
        ));
        HandlerInterceptor interceptor = new HandlerInterceptor() {
        };

        SimpleUrlHandlerMapping mapping = PluginControllerHandlerMappingSupport.buildHandlerMapping(
                manifest, "proxy-handler-stand-in", new HandlerInterceptor[]{interceptor});

        assertTrue(mapping.getOrder() < 0, "must beat RequestMappingHandlerMapping's default order");
        assertEquals("proxy-handler-stand-in", mapping.getUrlMap().get("/api/plugins/admin-tools/**"));
    }
}
