package com.finalexec.npdev.service;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class ArtifactLocalJavaSourceCapabilityHandler implements DynamicCapabilityHandler {

    private final Object target;
    private final Map<String, String> methodByOperation;

    public ArtifactLocalJavaSourceCapabilityHandler(Object target, Map<String, String> methodByOperation) {
        this.target = Objects.requireNonNull(target, "target");
        this.methodByOperation = normalizeMethods(methodByOperation);
        if (this.methodByOperation.isEmpty()) {
            throw new IllegalArgumentException("methodByOperation must not be empty");
        }
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        Objects.requireNonNull(call, "call");
        String methodName = methodByOperation.get(normalize(call.operation()));
        if (methodName == null || methodName.isBlank()) {
            return CapabilityResult.failure(
                    "JAVA_SOURCE_OPERATION_NOT_BOUND",
                    "Artifact-local Java source capability operation is not bound: " + call.operation(),
                    CapabilityErrorKind.PERMANENT,
                    details(call, Map.of("availableOperations", methodByOperation.keySet()))
            );
        }

        List<Object> args = call.args();
        Method method = null;
        try {
            method = resolveMethod(methodName, args == null ? 0 : args.size());
            method.setAccessible(true);
            Object output = method.getParameterCount() == 0
                    ? method.invoke(target)
                    : method.invoke(target, args.toArray());
            return CapabilityResult.success(output);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            return CapabilityResult.failure(
                    "JAVA_SOURCE_CAPABILITY_FAILED",
                    messageOrDefault(cause, "Artifact-local Java source capability failed"),
                    CapabilityErrorKind.PERMANENT,
                    details(call, Map.of("exceptionType", cause.getClass().getName()))
            );
        } catch (ReflectiveOperationException | RuntimeException exception) {
            // A vague "argument type mismatch" (the JVM's own IllegalArgumentException message,
            // which never names the offending argument) is diagnostically useless once a capability
            // grows past one arg -- callCapability's args are compiled in ALPHABETICAL-by-key order
            // (ModelCompiler.sortObjectMap), not JSON declaration order, so a positional mismatch is
            // an easy, silent mistake to make. Naming the resolved method's declared parameter types
            // alongside the actual argument runtime types turns a guessing game into a one-look fix.
            Map<String, Object> mismatchDetails = new LinkedHashMap<>();
            mismatchDetails.put("exceptionType", exception.getClass().getName());
            if (method != null) {
                mismatchDetails.put("resolvedMethod", method.toGenericString());
            }
            if (args != null) {
                mismatchDetails.put("actualArgTypes", args.stream()
                        .map(a -> a == null ? "null" : a.getClass().getName())
                        .toList());
            }
            return CapabilityResult.failure(
                    "JAVA_SOURCE_CAPABILITY_DISPATCH_ERROR",
                    messageOrDefault(exception, "Artifact-local Java source capability dispatch failed"),
                    CapabilityErrorKind.PERMANENT,
                    details(call, mismatchDetails)
            );
        }
    }

    private Method resolveMethod(String methodName, int argCount) throws NoSuchMethodException {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(methodName)
                    && method.getParameterCount() == argCount
                    && !method.getDeclaringClass().equals(Object.class)) {
                return method;
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + methodName + " with " + argCount + " argument(s)");
    }

    private static Map<String, String> normalizeMethods(Map<String, String> input) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (input == null) {
            return normalized;
        }
        input.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> {
                    String operation = normalize(entry.getKey());
                    String method = entry.getValue() == null ? "" : entry.getValue().trim();
                    if (!operation.isBlank() && !method.isBlank()) {
                        normalized.put(operation, method);
                    }
                });
        return Map.copyOf(normalized);
    }

    private static Map<String, Object> details(CapabilityCall call, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("capability", call.capability());
        details.put("operation", call.operation());
        details.put("adapterId", call.adapterId());
        details.putAll(extra);
        return details;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String messageOrDefault(Throwable throwable, String fallback) {
        String message = throwable == null ? null : throwable.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }
}
