package com.finalexec.npdev.service.pluginipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.service.PluginExecutionPolicyEvaluator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityError;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.Controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * B30/SEC-9: the host-side half of "{@code plugin:java-controller} crosses the process boundary."
 * {@code PluginControllerProxyConfig} registers one instance of this class against every mounted
 * controller's {@code basePath + "/**"} pattern (a {@code SimpleUrlHandlerMapping}), replacing the
 * direct {@code DispatcherServlet} -> author's-own-{@code @RestController} dispatch that used to run
 * in-process (the class itself is no longer a Spring bean at all -- see
 * {@code FinalExecApplication}'s {@code @ComponentScan} exclude filter). Every request this handler
 * receives has ALREADY passed {@code PluginControllerSecurityConfig}'s {@code MinimumRoleInterceptor}
 * (registered by URL pattern, so it applies here exactly as it did to the old direct dispatch) --
 * this class only resolves the specific route, builds a request envelope, and dispatches it through
 * the SAME {@link PluginIpcChildProcessPool} B1 (STOR-25) built for isolated Java migration hooks.
 *
 * <p>Callbacks are rejected (v1): no existing {@link PluginIpcHostSession} caller grants one yet
 * ({@code plugin:java-source}, B1's migration hooks) -- a controller mount gets no DataSource, no
 * Spring context, no host classloader, full stop.
 */
public final class PluginControllerProxyHandler implements Controller {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final PluginControllerRouteManifest routeManifest;
    private final ObjectProvider<PluginIpcChildProcessPool> poolProvider;
    private final RuntimePluginAdapterRegistry registry;
    private final PluginExecutionPolicyEvaluator policyEvaluator;
    private final ObjectMapper objectMapper;

    public PluginControllerProxyHandler(
            PluginControllerRouteManifest routeManifest,
            ObjectProvider<PluginIpcChildProcessPool> poolProvider,
            RuntimePluginAdapterRegistry registry,
            PluginExecutionPolicyEvaluator policyEvaluator,
            ObjectMapper objectMapper
    ) {
        this.routeManifest = Objects.requireNonNull(routeManifest, "routeManifest");
        this.poolProvider = Objects.requireNonNull(poolProvider, "poolProvider");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.policyEvaluator = Objects.requireNonNull(policyEvaluator, "policyEvaluator");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ModelAndView handleRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = requestPath(request);
        PluginControllerRouteManifest.Entry entry = routeManifest.entryForRequestPath(path).orElse(null);
        if (entry == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "No plugin controller mounted at " + path);
            return null;
        }
        PluginControllerRouteManifest.Route matchedRoute = null;
        Map<String, String> pathVariables = Map.of();
        for (PluginControllerRouteManifest.Route route : entry.routes()) {
            if (!route.httpMethod().equalsIgnoreCase(request.getMethod())) {
                continue;
            }
            if (PATH_MATCHER.match(route.path(), path)) {
                matchedRoute = route;
                pathVariables = PATH_MATCHER.extractUriTemplateVariables(route.path(), path);
                break;
            }
        }
        if (matchedRoute == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "No route matches " + request.getMethod() + " " + path);
            return null;
        }

        Map<String, List<String>> queryParams = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> queryParams.put(name, List.of(values)));

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("httpMethod", matchedRoute.httpMethod());
        envelope.put("path", matchedRoute.path());
        envelope.put("pathVariables", pathVariables);
        envelope.put("queryParams", queryParams);
        envelope.put("body", readBody(request));

        PluginIpcChildProcessPool pool = poolProvider.getIfAvailable();
        if (pool == null) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Plugin isolation pool is not available");
            return null;
        }
        RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution =
                registry.requireContribution(entry.capability(), matchedRoute.methodName(), "plugin:java-controller");
        CapabilityCall call = new CapabilityCall(entry.capability(), null, "plugin:java-controller", matchedRoute.methodName(), (Object) envelope);
        PluginIpcHostSession hostSession = new PluginIpcHostSession(registry, policyEvaluator, PluginControllerProxyHandler::rejectCallback);
        CapabilityResult result;
        try {
            result = pool.invoke(hostSession, contribution, call, Map.of(), ManifestDrivenJavaControllerPluginHandler.class.getName());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Interrupted dispatching plugin controller request");
            return null;
        }
        writeResponse(response, result);
        return null;
    }

    private static CapabilityResult rejectCallback(CapabilityCall call) {
        return CapabilityResult.failure(
                "PLUGIN_CONTROLLER_CALLBACK_DENIED",
                "plugin:java-controller mounts cannot call back into the host (v1)",
                CapabilityErrorKind.AUTH,
                Map.of("capability", call.capability(), "operation", call.operation())
        );
    }

    private static String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private Object readBody(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readAllBytes();
        if (bytes.length == 0) {
            return null;
        }
        try {
            return objectMapper.readValue(bytes, Object.class);
        } catch (IOException malformedJson) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    private void writeResponse(HttpServletResponse response, CapabilityResult result) throws IOException {
        if (result.ok()) {
            Map<String, Object> envelope = result.value() instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of("status", 200, "body", result.value());
            int status = envelope.get("status") instanceof Number number ? number.intValue() : 200;
            response.setStatus(status);
            Object headersValue = envelope.get("headers");
            if (headersValue instanceof Map<?, ?> headers) {
                headers.forEach((headerName, headerValue) -> response.setHeader(String.valueOf(headerName), String.valueOf(headerValue)));
            }
            Object body = envelope.get("body");
            if (body != null) {
                response.setContentType("application/json");
                objectMapper.writeValue(response.getOutputStream(), body);
            }
            return;
        }
        CapabilityError error = result.error();
        response.setStatus(statusFor(error.code()));
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(), Map.of("code", error.code(), "message", String.valueOf(error.message())));
    }

    /** Mirrors the failure codes {@link PluginIpcChildProcessPool}/{@link PluginIpcChildProcess}/
     *  {@link PluginIpcHostSession} already use on the wire (they cross a process boundary as plain
     *  JSON strings, so they are effectively public contract even though the Java constants that
     *  define them are private to their own classes). */
    private static int statusFor(String errorCode) {
        return switch (errorCode) {
            case "PLUGIN_EXECUTION_PROCESS_KILLED", "PLUGIN_IPC_CHANNEL_CLOSED", "PLUGIN_EXECUTION_INTERRUPTED" ->
                    HttpServletResponse.SC_GATEWAY_TIMEOUT;
            case "PLUGIN_CONTROLLER_ROUTE_NOT_FOUND", "PLUGIN_CONTROLLER_METHOD_NOT_FOUND" ->
                    HttpServletResponse.SC_NOT_FOUND;
            default -> HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        };
    }
}
