package com.npdev.kernel.inproc;

import com.npdev.kernel.ports.InvariantEngine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Minimal deterministic invariant engine for MVP runtime and tests.
 * It passes by default and supports explicit failure hooks through payload/state markers.
 */
public final class SimpleInvariantEngine implements InvariantEngine {

    @Override
    @Deprecated
    public List<String> evaluate(String entityName, Object payload) {
        if (!(payload instanceof Map<?, ?> map)) {
            return List.of();
        }
        Object forceFailure = map.get("__forceInvariantFailure");
        if (Boolean.TRUE.equals(forceFailure)) {
            return List.of("Forced invariant failure for concept " + entityName);
        }
        Object failureMessage = map.get("__invariantFailureMessage");
        if (failureMessage instanceof String message && !message.isBlank()) {
            return List.of(message);
        }
        return List.of();
    }

    @Override
    public InvariantEvaluationResult evaluate(InvariantEvaluationRequest request) {
        Objects.requireNonNull(request, "request");

        Set<String> requested = new LinkedHashSet<>(request.invariantRefs());
        Set<String> forcedFailures = collectForcedFailureRefs(request.payload(), request.state());
        String overrideMessage = resolveFailureMessage(request.payload(), request.state());

        List<Violation> violations = new ArrayList<>();
        for (String ref : requested) {
            if (!forcedFailures.contains(ref)) {
                continue;
            }
            String message = overrideMessage == null
                    ? "Invariant failed: " + ref
                    : overrideMessage;
            violations.add(new Violation(
                    "INVARIANT_FAIL",
                    message,
                    ref,
                    request.conceptName(),
                    request.metadata().flowName(),
                    request.metadata().stepName(),
                    request.metadata().stepIndex(),
                    Map.of("engine", "SimpleInvariantEngine")
            ));
        }
        return new InvariantEvaluationResult(violations);
    }

    private static Set<String> collectForcedFailureRefs(Object payload, Map<String, Object> state) {
        Set<String> refs = new LinkedHashSet<>();
        collectRefsFromObject(payload, refs);
        if (state != null) {
            collectRefsFromObject(state.get("__failInvariantRefs"), refs);
            Object single = state.get("__failInvariantRef");
            if (single instanceof String singleRef && !singleRef.isBlank()) {
                refs.add(singleRef.trim());
            }
        }
        return refs;
    }

    private static void collectRefsFromObject(Object value, Set<String> target) {
        if (value instanceof Map<?, ?> map) {
            Object refs = map.get("__failInvariantRefs");
            if (refs instanceof List<?> list) {
                for (Object candidate : list) {
                    if (candidate instanceof String text && !text.isBlank()) {
                        target.add(text.trim());
                    }
                }
            }
            Object single = map.get("__failInvariantRef");
            if (single instanceof String singleRef && !singleRef.isBlank()) {
                target.add(singleRef.trim());
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object candidate : list) {
                if (candidate instanceof String text && !text.isBlank()) {
                    target.add(text.trim());
                }
            }
        }
    }

    private static String resolveFailureMessage(Object payload, Map<String, Object> state) {
        String payloadMessage = resolveMessageFromMap(payload);
        if (payloadMessage != null) {
            return payloadMessage;
        }
        if (state == null) {
            return null;
        }
        Object stateMessage = state.get("__invariantFailureMessage");
        if (stateMessage instanceof String message && !message.isBlank()) {
            return message.trim();
        }
        return null;
    }

    private static String resolveMessageFromMap(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) {
            return null;
        }
        Object message = map.get("__invariantFailureMessage");
        if (message instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        return null;
    }
}
