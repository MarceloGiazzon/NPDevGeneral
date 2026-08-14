package com.finalexec.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * R10 -- the FOURTH runtime-supported-controller enforcement point, distinct in kind from the other
 * three named in {@code runtime-supported-controllers.json}/{@code RuntimeControllerAllowlistConfig}/
 * {@code build.gradle.template}'s {@code unsupportedRuntimeHostControllerSources}/
 * {@code run-runtime-surface-evidence.ps1}.
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
 * {@code security.minimumRole}, or merely declare it?" -- decision D9 (recorded in
 * {@code __OutsideRepo/strategy-2026-08-12/ROADMAP.md}) answers: enforce it, with an emitted wrapper,
 * because a declared-only security field is worse than none -- it reads as a guarantee nothing
 * checks. This class is that wrapper, generalized: it reads the per-app manifest the generator wrote
 * ({@code npdev/plugin-controllers/plugin-controller-security.json}) and, for every mounted
 * controller, registers a {@link HandlerInterceptor} that checks
 * {@link RuntimeContextService#currentContext(HttpServletRequest)}{@code .hasRole(minimumRole)} BEFORE
 * the author's controller method ever runs -- the same {@code requireSuperUser} idiom
 * {@code AgentProxyController} already uses, generalized from one hardcoded role to a per-mount
 * declared one. An interceptor, not a rewritten/wrapped controller class, because it needs no
 * knowledge of the author's endpoint methods -- it guards the URL prefix, which every framework-routed
 * request to that controller must cross.
 *
 * <p><b>Fail closed, not fail silent.</b> Every controller class under
 * {@code com.npdev.generated.plugin.} MUST have a matching manifest entry, checked here at boot
 * ({@link #addInterceptors}, which runs during the same context refresh every other bean is created
 * in). If the generator's copy step and its manifest-writing step ever drift apart -- a future bug, a
 * hand-edited source tree, anything that puts a controller under the reserved package without a
 * security declaration to match -- the app REFUSES TO START rather than silently serving an unguarded
 * route. This mirrors {@code RuntimeControllerAllowlistConfig}'s enumerate-beans-by-naming-convention
 * shape, but inverted: that class silently REMOVES an unlisted bean (a platform controller merely not
 * being in this app's release channel is not exceptional); this one THROWS (a plugin controller
 * existing at all was the author's explicit intent, so one missing its security declaration is a real
 * defect, not a channel decision).
 *
 * <p><b>npdev-plugin-controller-security-enforcement</b>: the twin-pair token
 * (scripts/quality/twin-pair-registry.json) binding this class to {@code GeneratedPluginMountPlan}
 * (which validates the descriptor and the reserved package prefix at generation time),
 * {@code RuntimeApiEmitter} (which writes the manifest this class reads), and
 * {@code run-r10-plugin-controller-proof.py} (the live generate+build+boot+HTTP proof this whole
 * mechanism is verified by -- this class imports {@code com.npdev.generated.*}, so per
 * {@code build.gradle.template}'s {@code generatedRuntimeDependentMainSources} exclusion it is
 * compiled ONLY inside an assembled app, never in a bare-template unit test; CLAUDE.md's own
 * "RuntimeHost tests that name com.npdev.generated. never run in any gate" note is why this class
 * has no JUnit test of its own -- one would be dead weight in run-runtimehost-gate.ps1, exactly the
 * trap that note warns about).
 */
@Configuration
public class PluginControllerSecurityConfig implements WebMvcConfigurer {

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

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        List<MountedControllerEntry> entries = loadManifest();
        failClosedIfAnyPluginControllerBeanIsUndeclared(entries);
        for (MountedControllerEntry entry : entries) {
            registry.addInterceptor(new MinimumRoleInterceptor(entry.minimumRole(), entry.controllerClass()))
                    .addPathPatterns(entry.basePath() + "/**");
        }
    }

    /**
     * Runs even when {@code entries} is empty: an app with NO declared plugin controllers must still
     * refuse to start if a {@code com.npdev.generated.plugin.*} controller bean somehow exists anyway
     * -- an empty manifest is not evidence of an empty package, only of nothing DECLARED.
     */
    private void failClosedIfAnyPluginControllerBeanIsUndeclared(List<MountedControllerEntry> entries) {
        Set<String> declaredSimpleNames = new LinkedHashSet<>();
        for (MountedControllerEntry entry : entries) {
            declaredSimpleNames.add(simpleName(entry.controllerClass()));
        }
        for (String beanName : applicationContext.getBeanNamesForAnnotation(RestController.class)) {
            Class<?> beanType = applicationContext.getType(beanName);
            if (beanType == null || !beanType.getName().startsWith(RESERVED_PLUGIN_CONTROLLER_PACKAGE_PREFIX)) {
                continue;
            }
            if (!declaredSimpleNames.contains(beanType.getSimpleName())) {
                throw new IllegalStateException(
                        "Plugin controller " + beanType.getName() + " (bean '" + beanName + "') is not declared "
                                + "in " + MANIFEST_RESOURCE + " -- every controller under "
                                + RESERVED_PLUGIN_CONTROLLER_PACKAGE_PREFIX + " must have a matching "
                                + "security.minimumRole entry, or it would serve requests with no role check at "
                                + "all. Refusing to start.");
            }
        }
    }

    private static String simpleName(String fullyQualifiedClassName) {
        int lastDot = fullyQualifiedClassName.lastIndexOf('.');
        return lastDot < 0 ? fullyQualifiedClassName : fullyQualifiedClassName.substring(lastDot + 1);
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
     * the same app cannot bleed into each other.
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
