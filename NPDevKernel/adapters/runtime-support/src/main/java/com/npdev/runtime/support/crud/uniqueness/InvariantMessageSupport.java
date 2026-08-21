package com.npdev.runtime.support.crud.uniqueness;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.kernel.ports.InvariantEngine;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.normalize;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): turning an {@link InvariantEngine}
 * violation (or its free-text message, for engines that don't populate structured
 * {@code details()}) into the {@code fieldPath}/{@code invariant} pair
 * {@code GeneratedCrudRuntimeSupport.InvariantViolationDetail} needs, plus the concept-level set of
 * {@code unique}-typed invariant refs used to tell a genuine uniqueness violation apart from an
 * ordinary schema failure.
 */
public final class InvariantMessageSupport {

    private static final Pattern FIELD_PATH_PATTERN = Pattern.compile("'([^']+)'");

    private InvariantMessageSupport() {
    }

    public static Set<String> collectUniqueInvariantRefs(CompiledConcept entity) {
        Set<String> out = new HashSet<>();
        if (entity == null || entity.getInvariants() == null) {
            return out;
        }
        entity.getInvariants().forEach(invariant -> {
            if (invariant == null || invariant.getRef() == null || invariant.getRef().isBlank()) {
                return;
            }
            if ("unique".equalsIgnoreCase(invariant.getType())) {
                out.add(normalize(invariant.getRef()));
            }
        });
        return out;
    }

    public static String extractFieldPath(InvariantEngine.Violation violation) {
        if (violation == null || violation.details() == null || violation.details().isEmpty()) {
            return null;
        }
        Object explicit = violation.details().get("fieldPath");
        if (explicit instanceof String path && !path.isBlank()) {
            return path;
        }
        Object fallback = violation.details().get("field");
        if (fallback instanceof String field && !field.isBlank()) {
            return field;
        }
        return null;
    }

    public static String extractPathFromMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher matcher = FIELD_PATH_PATTERN.matcher(message);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    public static String inferInvariantFromMessage(String path, String message) {
        if (path != null && !path.isBlank() && message != null && message.contains("required nested field")) {
            return "required(" + path + ")";
        }
        if (path != null && !path.isBlank() && message != null && message.contains("required field")) {
            return "required(" + path + ")";
        }
        if (path != null && !path.isBlank() && message != null && message.contains("unique constraint")) {
            return "unique(" + path + ")";
        }
        return "schema_validation";
    }
}
