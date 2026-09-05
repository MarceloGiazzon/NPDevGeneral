package com.finalexec.npdev.service.pluginipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B30/SEC-9: the {@code plugin:java-controller} sibling of {@link ManifestDrivenJavaSourcePluginHandler}
 * -- the fixed {@code handlerClassName} every pooled worker uses for a controller invoke. Reads
 * {@code npdev/plugin-runtime/plugin-controller-routes.json} (on the child's own classpath, since a
 * pooled worker runs on the host's full classpath) to resolve the real controller's FQCN for the
 * call's capability, reflectively instantiates it (a no-arg constructor, mirroring the java-source
 * handler's own POJO assumption), locates the named method (the host-side
 * {@code PluginControllerProxyHandler} already matched the request to a specific route and passes its
 * {@code methodName} as {@code call.operation()}), binds its declared parameters from the request
 * envelope, invokes it, and shapes the result into an HTTP-response envelope.
 *
 * <p>Binds only what generation-time admission ({@code PluginControllerRouteVisitor}, NPDevGenerator)
 * already guarantees every route method's parameters are: {@code @PathVariable}, {@code @RequestParam},
 * or {@code @RequestBody}, each carrying an explicit name (never relying on {@code Parameter.getName()},
 * which silently returns {@code arg0}-style names unless the app was compiled with {@code -parameters}).
 * Uses plain JDK reflection, not Spring's {@code AnnotatedElementUtils} -- {@code @AliasFor} attribute
 * aliasing between an annotation's {@code value()}/{@code name()} is a Spring merged-annotation
 * behaviour plain reflection does not apply, so both accessors are checked explicitly.
 */
public final class ManifestDrivenJavaControllerPluginHandler implements CapabilityAdapter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ManifestDrivenJavaControllerPluginHandler(PluginIpcCallbackClient callbackClient) {
        // Unused: B30/SEC-9 v1 rejects every plugin:java-controller callback (PluginControllerProxyHandler
        // builds its PluginIpcHostSession with rejectCallback), matching plugin:java-source's own
        // production posture today -- no existing PluginIpcHostSession caller grants one yet.
    }

    @Override
    public String adapterId() {
        return "plugin:java-controller";
    }

    @Override
    public String capability() {
        return "*";
    }

    @Override
    @SuppressWarnings("unchecked")
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        PluginControllerRouteManifest manifest = new PluginControllerRouteManifestLoader(objectMapper).load();
        PluginControllerRouteManifest.Entry entry = manifest.entryForCapability(call.capability()).orElse(null);
        if (entry == null) {
            return CapabilityResult.failure(
                    "PLUGIN_CONTROLLER_ROUTE_NOT_FOUND",
                    "No plugin-controller-routes.json entry for capability " + call.capability(),
                    CapabilityErrorKind.PERMANENT,
                    Map.of("capability", call.capability())
            );
        }
        String methodName = call.operation();
        Map<String, Object> envelope = (Map<String, Object>) call.input();
        try {
            Class<?> controllerClass = Class.forName(entry.controllerClassName());
            Method method = findMethod(controllerClass, methodName);
            if (method == null) {
                return CapabilityResult.failure(
                        "PLUGIN_CONTROLLER_METHOD_NOT_FOUND",
                        "No method named " + methodName + " on " + entry.controllerClassName(),
                        CapabilityErrorKind.PERMANENT,
                        Map.of("controllerClassName", entry.controllerClassName(), "methodName", methodName)
                );
            }
            Object target = controllerClass.getDeclaredConstructor().newInstance();
            Object[] args = bindArguments(method, envelope);
            Object result = method.invoke(target, args);
            return CapabilityResult.success(shapeResponse(result));
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            return CapabilityResult.failure(
                    "PLUGIN_CONTROLLER_INVOCATION_FAILED",
                    cause.getMessage(),
                    CapabilityErrorKind.PERMANENT,
                    Map.of("exceptionType", cause.getClass().getName())
            );
        } catch (ReflectiveOperationException exception) {
            return CapabilityResult.failure(
                    "PLUGIN_CONTROLLER_DISPATCH_ERROR",
                    exception.getMessage(),
                    CapabilityErrorKind.PERMANENT,
                    Map.of("exceptionType", exception.getClass().getName())
            );
        }
    }

    private static Method findMethod(Class<?> controllerClass, String methodName) {
        for (Method method : controllerClass.getMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object[] bindArguments(Method method, Map<String, Object> envelope) {
        Map<String, String> pathVariables = envelope == null
                ? Map.of() : (Map<String, String>) envelope.getOrDefault("pathVariables", Map.of());
        Map<String, List<String>> queryParams = envelope == null
                ? Map.of() : (Map<String, List<String>>) envelope.getOrDefault("queryParams", Map.of());
        Object body = envelope == null ? null : envelope.get("body");

        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            PathVariable pathVariable = parameter.getAnnotation(PathVariable.class);
            RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
            RequestBody requestBody = parameter.getAnnotation(RequestBody.class);
            if (pathVariable != null) {
                String name = explicitName(pathVariable.value(), pathVariable.name());
                args[i] = objectMapper.convertValue(pathVariables.get(name), parameter.getType());
            } else if (requestParam != null) {
                String name = explicitName(requestParam.value(), requestParam.name());
                List<String> values = queryParams.get(name);
                String rawValue = values == null || values.isEmpty() ? null : values.get(0);
                if (rawValue == null && !ValueConstants.DEFAULT_NONE.equals(requestParam.defaultValue())) {
                    rawValue = requestParam.defaultValue();
                }
                args[i] = rawValue == null ? null : objectMapper.convertValue(rawValue, parameter.getType());
            } else if (requestBody != null) {
                args[i] = objectMapper.convertValue(body, parameter.getType());
            } else {
                // Generation-time admission (PluginControllerRouteVisitor) already refuses any other
                // parameter shape -- unreachable for a generated app, defensive only.
                throw new IllegalStateException("Unsupported plugin controller parameter: " + parameter);
            }
        }
        return args;
    }

    private static String explicitName(String value, String name) {
        return !value.isBlank() ? value : name;
    }

    private Object shapeResponse(Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (result instanceof ResponseEntity<?> responseEntity) {
            response.put("status", responseEntity.getStatusCode().value());
            Map<String, String> headers = new LinkedHashMap<>();
            responseEntity.getHeaders().forEach((headerName, headerValues) -> {
                if (!headerValues.isEmpty()) {
                    headers.put(headerName, headerValues.get(0));
                }
            });
            response.put("headers", headers);
            response.put("body", responseEntity.getBody());
            return response;
        }
        response.put("status", 200);
        response.put("headers", Map.of());
        response.put("body", result);
        return response;
    }
}
