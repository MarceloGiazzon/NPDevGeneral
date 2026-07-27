package com.npdev.adapters.tracing.redaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * ADR-0009 / P2: the single source of "what field-name substring counts as sensitive" for the
 * whole redaction policy family. Before this class existed, {@link DefaultEventRedactionPolicy},
 * {@link DefaultExecutionRedactionPolicy} and {@link DefaultTraceRedactionPolicy} each carried
 * their own private {@code isSensitiveKey}, and had already silently drifted from each other --
 * the Trace policy's copy omitted {@code "authorization"}, found while building this feature.
 * Adding a fourth independent copy for AI-pack redaction would have been exactly the eight-passes
 * debt REG-6 already paid off once, moved one policy over. All four consumers now delegate here,
 * and here loads the one substring list from {@code sensitive-key-patterns.json} rather than
 * hardcoding it a fifth time.
 */
public final class SensitiveKeyPolicy {

    private static final String RESOURCE_PATH = "/npdev/redaction/sensitive-key-patterns.json";
    private static final Set<String> KEY_SUBSTRINGS = loadKeySubstrings();

    private SensitiveKeyPolicy() {
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        for (String substring : KEY_SUBSTRINGS) {
            if (normalized.contains(substring)) {
                return true;
            }
        }
        return false;
    }

    /** The email-shape value heuristic every consumer already applied ad hoc alongside isSensitiveKey. */
    public static boolean looksLikeSensitiveValue(String value) {
        return value != null && value.contains("@");
    }

    private static Set<String> loadKeySubstrings() {
        try (InputStream in = SensitiveKeyPolicy.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + RESOURCE_PATH);
            }
            JsonNode root = new ObjectMapper().readTree(in);
            Set<String> substrings = new LinkedHashSet<>();
            for (JsonNode node : root.path("keySubstrings")) {
                substrings.add(node.asText().toLowerCase(Locale.ROOT));
            }
            if (substrings.isEmpty()) {
                throw new IllegalStateException(RESOURCE_PATH + " declared no keySubstrings");
            }
            return Set.copyOf(substrings);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed loading " + RESOURCE_PATH, e);
        }
    }
}
