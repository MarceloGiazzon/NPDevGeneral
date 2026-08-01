package com.npdev.adapters.tracing.redaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collection;
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
 *
 * <p>R80 (ledger/items/REG-80.yml, docs/MOVE7_IMPLEMENTATION_SPEC.md): {@link
 * #registerModelSensitiveFieldNames} adds a SECOND, OR'd-in source -- a model author's own {@code
 * field.sensitive: true} declarations -- without replacing this class's static, key-name-substring
 * denylist. Deliberately kept DSL-agnostic here (no dependency on {@code CompiledModel}/{@code
 * CompiledField}, which this adapter module does not otherwise depend on): the caller extracts the
 * field names and passes plain strings. See {@code NpdevObservabilityConfig} for the actual
 * extraction + registration call, at the same {@code CompiledModel}-becomes-available boot point
 * {@code DefaultConceptGateway.governedBy}'s caller uses.
 */
public final class SensitiveKeyPolicy {

    private static final String RESOURCE_PATH = "/npdev/redaction/sensitive-key-patterns.json";
    private static final Set<String> KEY_SUBSTRINGS = loadKeySubstrings();

    /** Exact (not substring) field names from {@code field.sensitive: true}, lowercased. Empty until
     * {@link #registerModelSensitiveFieldNames} runs -- an app with no compiled model wired (e.g. a
     * bare adapter unit test) behaves exactly as before this field existed. */
    private static volatile Set<String> modelSensitiveFieldNames = Set.of();

    private SensitiveKeyPolicy() {
    }

    /**
     * Registers the model's own declared sensitive field names, consulted by {@link
     * #isSensitiveKey} as EXACT (not substring) matches -- a model author knows precisely which
     * field is sensitive, so this does not need the static denylist's broader substring heuristic.
     * Safe to call more than once (e.g. a test resetting state); the latest call wins. {@code null}
     * or empty clears the registration back to "nothing model-declared".
     *
     * <p>Move 8 (item G7): redaction here is by FIELD NAME and applies globally -- marking any
     * concept's field sensitive redacts that key name in traces and event payloads across all
     * concepts, not just the declaring concept. This registry is a flat {@code Set<String>} with no
     * concept scoping, and that is deliberate: trace records are flat key/value maps where the
     * originating concept is frequently not available at redaction time, so making this per-concept
     * would trade a safe over-redaction for a real risk of under-redaction. This is the fail-safe
     * direction, not a bug.
     */
    public static void registerModelSensitiveFieldNames(Collection<String> fieldNames) {
        if (fieldNames == null || fieldNames.isEmpty()) {
            modelSensitiveFieldNames = Set.of();
            return;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String fieldName : fieldNames) {
            if (fieldName != null && !fieldName.isBlank()) {
                normalized.add(fieldName.toLowerCase(Locale.ROOT));
            }
        }
        modelSensitiveFieldNames = Set.copyOf(normalized);
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        if (modelSensitiveFieldNames.contains(normalized)) {
            return true;
        }
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
