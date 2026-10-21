package com.npdev.runtime.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.kernel.ports.RuntimeInvariantEngineFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * REG-273: minLength, array minItems / maxItems / uniqueItems / itemIdentityField + duplicationPolicy
 * and the scalar constraints of nested object properties are refused through the same field-level
 * validation as every other typed violation.
 */
class GeneratedCrudRuntimeSupportNestedSchemaConstraintsTest {

    private static CompiledSchema scalarSchema(String type, Integer minLength, Integer maxLength, Double min, Double max) {
        return new CompiledSchema(type, Map.of(), null, List.of(), List.of(), null, null, null, "",
                minLength, maxLength, null, null, null, null, null, null, null, min, max, null);
    }

    private static CompiledSchema arraySchema(CompiledSchema items, Integer minItems, Integer maxItems,
                                              Boolean unique, String identityField, String duplicationPolicy) {
        return new CompiledSchema("array", Map.of(), items, List.of(), List.of(), null, null, null, "",
                null, null, null, null, minItems, maxItems, unique, identityField, duplicationPolicy, null, null, null);
    }

    private static CompiledSchema objectSchema(Map<String, CompiledSchema> properties, List<String> required) {
        return new CompiledSchema("object", properties, null, required, List.of(), null, null, null, "",
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static CompiledField field(String name, String type, CompiledSchema schema) {
        return new CompiledField(name, type, "Object", false, false, false, List.of(), null, null, null, schema, List.of());
    }

    private static GeneratedCrudRuntimeSupport support() {
        Map<String, CompiledSchema> memberProps = new LinkedHashMap<>();
        memberProps.put("memberCode", scalarSchema("string", null, null, null, null));
        Map<String, CompiledSchema> profileProps = new LinkedHashMap<>();
        profileProps.put("nickname", scalarSchema("string", null, 5, null, null));
        profileProps.put("level", scalarSchema("integer", null, null, 1.0, 9.0));

        CompiledConcept thing = new CompiledConcept("Thing", "Thing", "things", List.of(
                new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                field("shortName", "string", scalarSchema("string", 3, 12, null, null)),
                field("tags", "array", arraySchema(scalarSchema("string", null, null, null, null), 1, 3, true, null, null)),
                field("members", "array", arraySchema(objectSchema(memberProps, List.of("memberCode")), null, null,
                        null, "memberCode", "deny")),
                field("plain", "array", arraySchema(scalarSchema("string", null, null, null, null), null, null,
                        null, null, "deny")),
                field("profile", "object", objectSchema(profileProps, List.of()))));
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

    private static List<GeneratedCrudRuntimeSupport.InvariantViolationDetail> validate(String field, Object value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(field, value);
        return support().validateEntityDetailed("Thing", payload, (e, f, v, id, raw) -> false);
    }

    private static void assertRefused(String field, Object value, String path, String messagePart) {
        var violations = validate(field, value);
        assertTrue(violations.stream().anyMatch(v -> v.path().equals(path) && v.message().contains(messagePart)),
                "expected '" + messagePart + "' at " + path + " but got " + violations);
    }

    @Test
    void minLengthRefusesAShortValueAndAcceptsTheBoundary() {
        assertRefused("shortName", "ab", "shortName", "minLength 3");
        assertTrue(validate("shortName", "abc").isEmpty());
    }

    @Test
    void arrayItemCountLimitsAreEnforced() {
        assertRefused("tags", List.of(), "tags", "at least 1");
        assertRefused("tags", List.of("a", "b", "c", "d"), "tags", "at most 3");
        assertTrue(validate("tags", List.of("a", "b", "c")).isEmpty());
    }

    @Test
    void uniqueItemsRefusesARepeatedValueIgnoringSurroundingSpaces() {
        assertRefused("tags", List.of("red", " red"), "tags", "duplicate");
    }

    @Test
    void denyPolicyRefusesADuplicateIdentityAndIgnoresItemsWithoutOne() {
        assertRefused("members", List.of(Map.of("memberCode", "m1"), Map.of("memberCode", "m1")), "members", "by memberCode");
        assertTrue(validate("members", List.of(Map.of("memberCode", "m1"), Map.of("memberCode", "m2"))).isEmpty());
    }

    @Test
    void denyPolicyWithoutAnIdentityFieldComparesWholeItems() {
        assertEquals(1, validate("plain", List.of("x", "x")).size());
        assertTrue(validate("plain", List.of("x", "y")).isEmpty());
    }

    @Test
    void nestedObjectPropertiesApplyTheirScalarConstraintsAndTypes() {
        assertRefused("profile", Map.of("nickname", "toolongname"), "profile.nickname", "maxLength 5");
        assertRefused("profile", Map.of("level", 12), "profile.level", "above max 9");
        assertRefused("profile", Map.of("level", "abc"), "profile.level", "must be a number");
        assertRefused("profile", Map.of("level", 2.5d), "profile.level", "whole number");
        assertTrue(validate("profile", Map.of("nickname", "ok", "level", 5)).isEmpty());
        assertTrue(validate("profile", Map.of("level", "7")).isEmpty(), "a numeric string is a number");
    }
}
