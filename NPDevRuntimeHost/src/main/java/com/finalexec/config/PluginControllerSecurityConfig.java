package com.finalexec.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.service.pluginipc.PluginControllerHandlerMappingSupport;
import com.finalexec.npdev.service.pluginipc.PluginControllerProxyHandler;
import com.finalexec.npdev.service.pluginipc.PluginControllerRouteManifest;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * R10/B30/SEC-9 -- the FOURTH runtime-supported-controller enforcement point, distinct in kind from
 * the other three named in {@code runtime-supported-controllers.json}/
 * {@code RuntimeControllerAllowlistConfig}/{@code build.gradle.template}'s
 * {@code unsupportedRuntimeHostControllerSources}/{@code run-runtime-surface-evidence.ps1}.
 *
 * <p><b>Why a fourth mechanism, not a fourth use of the existing three.</b> Those three all key off
 * {@code com/finalexec/api} plus ONE fixed, byte-identical-per-app manifest describing the
 * platform-owned surface. A plugin controller is author-supplied, lives under the reserved
 * {@code com.npdev.generated.plugin.} package (never {@code com.finalexec.*} -- {@code
 * RuntimeControllerAllowlistConfig} only ever inspects {@code com.finalexec.*Controller} bean class
 * names, so it does not even see a plugin controller, by design), and its declared security posture is
 * PER-APP, generated at generation time from the model's own {@code capability.plugin.json}
 * descriptors (see {@code GeneratedPluginMountPlan}/{@code RuntimeApiEmitter} in NPDevGenerator).
 * Adding its name to {@code allowedControllers} would neither enable it (wrong package) nor be
 * architecturally sound (that manifest is not supposed to vary per app).
 *
 * <p><b>What this class actually enforces (D9).</b> "Does {@code plugin:java-controller} enforce
 * {@code security.minimumRole}, or merely declare it?" -- decision D9 answers: enforce it, with an
 * emitted wrapper, because a declared-only security field is worse than none. This class is that
 * wrapper: it reads the per-app manifest the generator wrote
 * ({@code npdev/plugin-controllers/plugin-controller-security.json}) and, for every mounted
 * controller, registers a {@link HandlerInterceptor} that checks
 * {@link RuntimeContextService#currentContext(HttpServletRequest)}{@code .hasRole(minimumRole)} BEFORE
 * the request ever reaches {@link PluginControllerProxyHandler} -- the same {@code requireSuperUser}
 * idiom {@code AgentProxyController} already uses, generalized to a per-mount declared role.
 *
 * <p><b>B30/SEC-9 correction (found by the live proof, not assumed):</b> a {@code WebMvcConfigurer
 * .addInterceptors}-registered {@link org.springframework.web.servlet.config.annotation.InterceptorRegistry}
 * entry is only ever wired into the {@code HandlerMapping} beans Spring MVC's OWN auto-configuration
 * builds ({@code RequestMappingHandlerMapping} and friends) -- it is never re-detected by an
 * independently-declared {@link SimpleUrlHandlerMapping} bean the way a global
 * {@link MappedInterceptor} bean would be. Since the isolated dispatch path
 * ({@code PluginControllerProxyHandler}, registered via a {@code SimpleUrlHandlerMapping} at
 * {@link Ordered#HIGHEST_PRECEDENCE} so it wins the reserved {@code /api/plugins/*} prefix before
 * {@code RequestMappingHandlerMapping} is even consulted) now answers every plugin-controller
 * request, the interceptor MUST be attached directly to that same mapping -- see
 * {@link #pluginControllerHandlerMapping}, which wraps one {@link MinimumRoleInterceptor} per mount in
 * a {@link MappedInterceptor} scoped to that mount's own {@code basePath + "/**"} (so two mounts with
 * different roles never bleed into each other on a single shared interceptor list) and sets them
 * directly on the mapping instance, rather than relying on Spring to auto-detect them.
 *
 * <p><b>Fail closed, not fail silent.</b> Every controller class under
 * {@code com.npdev.generated.plugin.} MUST have a matching manifest entry -- more precisely,
 * post-B30/SEC-9, NONE should exist as a live Spring bean at all ({@link #onContextRefreshed}, which
 * fires once every singleton bean in the context exists). If the generator's copy step and its
 * manifest-writing step ever drift apart -- a future bug, a hand-edited source tree, a broken
 * {@code @ComponentScan} exclude filter -- the app REFUSES TO START rather than silently serving an
 * unguarded, in-process route.
 *
 * <p><b>What this class does NOT independently verify.</b> The interceptor registered here trusts
 * that the manifest's {@code basePath} actually covers every route the named controller class
 * declares -- it never inspects the route table itself. That invariant is established at GENERATION
 * time instead, by {@code GeneratedPluginMountPlan.validateControllerRoutesWithinBasePath} (refusing
 * to generate an app whose controller declares a route outside its basePath) and
 * {@code PluginControllerRouteVisitor} (refusing an unsupported route-method parameter shape).
 *
 * <p><b>npdev-plugin-controller-security-enforcement</b>: the twin-pair token
 * (scripts/quality/twin-pair-registry.json) binding this class to {@code GeneratedPluginMountPlan},
 * {@code RuntimeApiEmitter}, and {@code run-r10-plugin-controller-proof.py} (the live
 * generate+build+boot+HTTP proof this whole mechanism is verified by -- this class imports
 * {@code com.npdev.generated.*}, so per {@code build.gradle.template}'s
 * {@code generatedRuntimeDependentMainSources} exclusion it is compiled ONLY inside an assembled app,
 * never in a bare-template unit test).
 */
@Configuration
public class PluginControllerSecurityConfig {

    static final String MANIFEST_RESOURCE = "npdev/plugin-controllers/plugin-controller-security.json";

    /** Must equal GeneratedPluginMountPlan.PLUGIN_CONTROLLER_PACKAGE_PREFIX (NPDevGenerator) --
     *  npdev-plugin-controller-security-enforcement twin-pair rule (scripts/quality/twin-pair-registry.json)
     *  pins the two literals together so they cannot drift apart unnoticed. */
    static final String RESERVED_PLUGIN_CONTROLLER_PACKAGE_PREFIX = "com.npdev.generated.plugin.";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RuntimeContextService runtimeContextService;
    private final ApplicationContext applicationContext;

    public PluginControllerSecurityConfig(RuntimeContextService runtimeContextService, ApplicationContext applicationContext) {
        this.runtimeContextService = runtimeContextService;
        this.applicationContext = applicationContext;
    }

    /**
     * B30/SEC-9: the isolated dispatch path's {@code HandlerMapping} -- see the class javadoc for why
     * the role interceptor must be attached here directly rather than through
     * {@code WebMvcConfigurer.addInterceptors}. The urlMap/mapping construction itself is delegated to
     * {@link PluginControllerHandlerMappingSupport} (runtimehost-core, unit-tested there) -- this
     * method's own surface is kept to just the one thing that genuinely needs
     * {@link RuntimeContextService}: building each mount's {@link MinimumRoleInterceptor}.
     * {@code NPDevRuntimeHost/src/main}'s own coverage ratchet only ever measures THIS class, never
     * runtimehost-core (feedback_runtimehost_coverage_ratchet_scope) -- for a sample with no mounted
     * controller this method's loop runs zero iterations regardless, so minimizing what lives here
     * (rather than what lives in the tested-elsewhere support class) is a real, not cosmetic, fix.
     */
    @Bean
    public SimpleUrlHandlerMapping pluginControllerHandlerMapping(
            PluginControllerRouteManifest pluginControllerRouteManifest,
            PluginControllerProxyHandler pluginControllerProxyHandler
    ) {
        MappedInterceptor[] interceptors = loadManifest().stream()
                .map(entry -> new MappedInterceptor(
                        new String[]{entry.basePath() + "/**"},
                        new MinimumRoleInterceptor(entry.minimumRole(), entry.controllerClass())))
                .toArray(MappedInterceptor[]::new);
        return PluginControllerHandlerMappingSupport.buildHandlerMapping(
                pluginControllerRouteManifest, pluginControllerProxyHandler, interceptors);
    }

    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed() {
        failClosedIfAnyPluginControllerBeanIsUndeclared();
    }

    /**
     * B30/SEC-9 tightening: since {@code FinalExecApplication}'s {@code @ComponentScan} now excludes
     * {@code com.npdev.generated.plugin.*} entirely (the package a mounted controller's source is
     * copied into, so the isolated child can classload it -- see {@code PluginControllerProxyHandler}),
     * NO bean should EVER exist under that prefix, declared or not: the guard that used to be
     * "undeclared plugin controller bean" is now simply "any plugin controller bean at all," a
     * stronger and simpler invariant. Its previous form (accepting a bean whose class name matched a
     * manifest entry) would now be vacuously permissive -- a future change that broke the exclude
     * filter would silently restore in-process dispatch for exactly the classes this guard is meant to
     * catch.
     *
     * <p>Adversarial-review finding (unchanged from the original R10 review, still applies): enumerates
     * both {@code @RestController} and {@code @Controller} beans, since a controller written as plain
     * {@code @Controller} + {@code @ResponseBody} would otherwise be invisible to this guard.
     */
    private void failClosedIfAnyPluginControllerBeanIsUndeclared() {
        Set<String> candidateBeanNames = new LinkedHashSet<>();
        candidateBeanNames.addAll(List.of(applicationContext.getBeanNamesForAnnotation(RestController.class)));
        candidateBeanNames.addAll(List.of(applicationContext.getBeanNamesForAnnotation(Controller.class)));
        for (String beanName : candidateBeanNames) {
            Class<?> beanType = applicationContext.getType(beanName);
            if (beanType == null || !beanType.getName().startsWith(RESERVED_PLUGIN_CONTROLLER_PACKAGE_PREFIX)) {
                continue;
            }
            throw new IllegalStateException(
                    "Plugin controller " + beanType.getName() + " (bean '" + beanName + "') is a live Spring bean "
                            + "under " + RESERVED_PLUGIN_CONTROLLER_PACKAGE_PREFIX + " -- this package is excluded "
                            + "from component scanning (FinalExecApplication) and must be dispatched only through "
                            + "the isolated child process (PluginControllerProxyHandler), never in-process. Refusing "
                            + "to start.");
        }
    }

    private List<MountedControllerEntry> loadManifest() {
        ClassPathResource resource = new ClassPathResource(MANIFEST_RESOURCE);
        if (!resource.exists()) {
            return List.of();
        }
        try (InputStream inputStream = resource.getInputStream()) {
            JsonNode root = OBJECT_MAPPER.readTree(inputStream);
            List<MountedControllerEntry> entries = new ArrayList<>();
            for (JsonNode node : root.path("mountedControllers")) {
                String controllerClass = text(node, "controllerClass");
                String basePath = text(node, "basePath");
                String minimumRole = text(node, "minimumRole");
                if (controllerClass.isBlank() || basePath.isBlank() || minimumRole.isBlank()) {
                    throw new IllegalStateException(MANIFEST_RESOURCE
                            + " has an entry missing controllerClass/basePath/minimumRole: " + node);
                }
                entries.add(new MountedControllerEntry(controllerClass, basePath, minimumRole));
            }
            return List.copyOf(entries);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load " + MANIFEST_RESOURCE, exception);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private record MountedControllerEntry(String controllerClass, String basePath, String minimumRole) {
    }

    /**
     * D9's enforcement. One instance per mounted controller, each closing over its OWN declared role
     * -- never a shared/global role -- so two plugin controllers with different security postures in
     * the same app cannot bleed into each other. Wrapped in a basePath-scoped {@link MappedInterceptor}
     * by {@link #pluginControllerHandlerMapping} rather than relying on annotation-driven path
     * matching, since this class is no longer a {@code WebMvcConfigurer}.
     */
    private final class MinimumRoleInterceptor implements HandlerInterceptor {
        private final String minimumRole;
        private final String controllerClassName;

        MinimumRoleInterceptor(String minimumRole, String controllerClassName) {
            this.minimumRole = minimumRole;
            this.controllerClassName = controllerClassName;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            // currentContext() itself throws 401 for an unauthenticated caller (RuntimeContextService,
            // NPDevGenerator's npdev-runtime-context-service.mustache) -- deliberately left to
            // propagate rather than caught here, same as every other manual role-check idiom in this
            // codebase (AgentProxyController.requireSuperUser).
            ExecutionContext context = runtimeContextService.currentContext(request);
            if (!context.hasRole(minimumRole)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "plugin controller " + controllerClassName + " requires role " + minimumRole);
            }
            return true;
        }
    }
}
