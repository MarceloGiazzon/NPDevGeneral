package com.npdev.kernel.inproc;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.CapabilityInvoker;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic in-process capability invoker with explicit registry keys.
 */
public final class InProcCapabilityInvoker implements CapabilityInvoker {

    @FunctionalInterface
    public interface Handler {
        Object invoke(Object input, ExecutionContext context, Map<String, Object> currentState);
    }

    private final Map<String, Handler> handlersByKey = new LinkedHashMap<>();

    public InProcCapabilityInvoker register(
            String capabilityName,
            String adapterId,
            String operationName,
            Handler handler
    ) {
        Objects.requireNonNull(handler, "handler");
        handlersByKey.put(bindingKey(capabilityName, adapterId, operationName), handler);
        return this;
    }

    @Override
    public Object invoke(
            String capabilityName,
            String adapterId,
            String operationName,
            Object input,
            ExecutionContext executionContext,
            Map<String, Object> currentState
    ) {
        String key = bindingKey(capabilityName, adapterId, operationName);
        Handler handler = handlersByKey.get(key);
        if (handler == null) {
            throw new IllegalStateException(
                    "Missing capability binding for capability="
                            + capabilityName + ", adapter=" + adapterId + ", operation=" + operationName
            );
        }
        return handler.invoke(
                input,
                executionContext == null ? ExecutionContext.anonymous() : executionContext,
                currentState == null ? Map.of() : Map.copyOf(currentState)
        );
    }

    private static String bindingKey(String capabilityName, String adapterId, String operationName) {
        return normalize(capabilityName) + "#" + normalize(adapterId) + "#" + normalize(operationName);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
