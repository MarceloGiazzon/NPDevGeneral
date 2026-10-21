package com.npdev.runtime.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.kernel.ports.RuntimeInvariantEngineFactory;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * REG-253..256: declared field defaults count as "present" for the required pre-check, and the
 * declared maxLength / min / max / precision / scale are rejected by the same field-level validation
 * every other typed violation goes through (422-shaped {@code InvariantViolationDetail}).
 */
class GeneratedCrudRuntimeSupportFieldConstraintsTest {

    private static CompiledField scalar(String name, String type, boolean required, Object defaultValue,
                                        String defaultExpression, Integer maxLength, Integer precision,
                                        Integer scale, Double min, Double max) {
        CompiledSchema schema = new CompiledSchema(type, Map.of(), null, List.of(), List.of(), defaultValue,
                defaultExpression, null, "", null, maxLength, precision, scale, null, null, null, null, null,
                min, max, null);
        return new CompiledField(name, type, "Object", false, required, false, List.of(), null, null, null,
                schema, List.of());
    }

    private static GeneratedCrudRuntimeSupport support() {
        CompiledConcept thing = new CompiledConcept("Thing", "Thing", "things", List.of(
                new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                scalar("title", "string", true, null, null, 5, null, null, null, null),
                scalar("kind", "string", true, "PLAIN", null, null, null, null, null, null),
                scalar("stamp", "string", true, null, "now()", null, null, null, null, null),
                scalar("rows", "int", true, null, null, null, null, null, 1.0, 11.0),
                scalar("unitPrice", "decimal", false, null, null, null, 12, 2, null, null)));
        CompiledModel model = new CompiledModel("demo", "1.0.0", "v1", Map.of("Thing", thing));
        InvariantEngine noInvariants = new InvariantEngine() {
            @Override
            public List<String> evaluate(String entityName, Object payload) {
                return List.of();
            }
        };
        return new GeneratedCrudRuntimeSupport(
                () -> model,
                new KernelRunner((EventBus) event -> { }, noInvariants),
                null,
                null,
                null,
                null,
                new SystemRuntimeClock(),
                new InMemoryOrchestrationExecutionRegistry(),
                new RuntimeInvariantEngineFactory() {
                    @Override
                    public InvariantEngine create(UniqueValueLookup uniqueValueLookup, ConflictLookup conflictLookup) {
                        return noInvariants;
                    }
                });
    }

    private static List<GeneratedCrudRuntimeSupport.InvariantViolationDetail> validate(Map<String, Object> payload) {
        return support().validateEntityDetailed("Thing", payload, (e, f, v, id, raw) -> false);
    }

    private static Map<String, Object> valid() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "abcde");
        payload.put("kind", "PLAIN");
        payload.put("stamp", "s");
        payload.put("rows", 1);
        payload.put("unitPrice", new BigDecimal("12.34"));
        return payload;
    }

    @Test
    void hasCreateDefaultSeesLiteralDefaultsAndExpressionsButNotPlainRequiredFields() {
        GeneratedCrudRuntimeSupport support = support();

        assertTrue(support.hasCreateDefault("Thing", "kind"));
        assertTrue(support.hasCreateDefault("Thing", "stamp"));
        assertFalse(support.hasCreateDefault("Thing", "title"));
        assertFalse(support.hasCreateDefault("Thing", "nope"));
        assertFalse(support.hasCreateDefault("Nope", "kind"));
        assertFalse(support.hasCreateDefault(null, "kind"));
    }

    @Test
    void aDefaultedFieldIsFilledIntoTheCreatePayloadSoRequiredIsSatisfied() {
        Map<String, Object> payload = support().buildCreateInvariantPayload("Thing", Map.of("title", "abc", "rows", 1));

        assertEquals("PLAIN", payload.get("kind"));
    }

    @Test
    void aFullyValidPayloadHasNoViolations() {
        assertTrue(validate(valid()).isEmpty());
    }

    @Test
    void maxLengthViolationIsReportedOnTheFieldPath() {
        Map<String, Object> payload = valid();
        payload.put("title", "abcdef");

        var violations = validate(payload);

        assertEquals(1, violations.size());
        assertEquals("title", violations.get(0).path());
        assertTrue(violations.get(0).message().contains("maxLength 5"), violations.get(0).message());
        assertFalse(violations.get(0).unique());
    }

    @Test
    void minAndMaxViolationsAreReported() {
        Map<String, Object> low = valid();
        low.put("rows", 0);
        Map<String, Object> high = valid();
        high.put("rows", 12);

        assertEquals("rows", validate(low).get(0).path());
        assertTrue(validate(low).get(0).message().contains("below min 1"));
        assertTrue(validate(high).get(0).message().contains("above max 11"));
    }

    @Test
    void precisionAndScaleViolationsAreReportedForADecimal() {
        Map<String, Object> tooWide = valid();
        tooWide.put("unitPrice", 12345678901234.5d);
        Map<String, Object> tooPrecise = valid();
        tooPrecise.put("unitPrice", 1.555d);

        assertTrue(validate(tooWide).stream().anyMatch(v -> v.message().contains("precision 12")));
        assertTrue(validate(tooPrecise).stream().anyMatch(v -> v.message().contains("scale 2")));
    }

    @Test
    void aViolationBecomesA422WithTheUsualErrorBody() {
        Map<String, Object> payload = valid();
        payload.put("rows", -5);

        var exception = new GeneratedCrudRuntimeSupport.InvariantViolationException(validate(payload));

        assertEquals(422, exception.statusCode());
        assertEquals("invariant_failed", exception.toResponseBody().get("code"));
        assertEquals("rows", exception.toResponseBody().get("path"));
    }
}
