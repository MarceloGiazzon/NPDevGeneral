package com.npdev.dsl.v1.compiled;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** REG-254/255/256: the shared maxLength / min / max / precision / scale rules. */
class FieldValueConstraintsTest {

    private static CompiledField field(String name, String type, Integer maxLength, Integer precision, Integer scale,
                                       Double min, Double max) {
        CompiledSchema schema = new CompiledSchema(type, Map.of(), null, List.of(), List.of(), null, null, null,
                "", null, maxLength, precision, scale, null, null, null, null, null, min, max, null);
        return new CompiledField(name, type, "Object", false, false, false, List.of(), null, null, null, schema, List.of());
    }

    private static List<String> check(CompiledField field, Object value) {
        return FieldValueConstraints.violations("Thing", field, value);
    }

    @Test
    void maxLengthAcceptsTheBoundaryAndRejectsOneOver() {
        CompiledField title = field("title", "string", 5, null, null, null, null);

        assertTrue(check(title, "abcde").isEmpty());
        List<String> over = check(title, "abcdef");
        assertEquals(1, over.size());
        assertTrue(over.get(0).contains("field 'title'") && over.get(0).contains("maxLength 5"), over.get(0));
    }

    @Test
    void minAndMaxAreInclusiveAndApplyToIntegersAndDecimals() {
        CompiledField rows = field("rows", "int", null, null, null, 1.0, 11.0);

        assertTrue(check(rows, 1).isEmpty());
        assertTrue(check(rows, 11).isEmpty());
        assertEquals(1, check(rows, 0).size());
        assertTrue(check(rows, -5).get(0).contains("below min 1"));
        assertTrue(check(rows, 12).get(0).contains("above max 11"));
        assertEquals(1, check(rows, "0").size(), "a numeric string is range-checked too");

        CompiledField price = field("price", "decimal", null, null, null, 0.5, null);
        assertTrue(check(price, new BigDecimal("0.50")).isEmpty());
        assertEquals(1, check(price, new BigDecimal("0.49")).size());
    }

    @Test
    void decimalPrecisionAndScaleAreEnforcedOnTheValueNotTheWrittenForm() {
        CompiledField unitPrice = field("unitPrice", "decimal", null, 12, 2, null, null);

        assertTrue(check(unitPrice, new BigDecimal("12.34")).isEmpty());
        assertTrue(check(unitPrice, new BigDecimal("12.340")).isEmpty(), "trailing zeros carry no information");
        assertTrue(check(unitPrice, new BigDecimal("9999999999.99")).isEmpty(), "10 integer digits + 2 = 12 fits");
        assertEquals(1, check(unitPrice, new BigDecimal("1.555")).size(), "three decimals exceed scale 2");
        assertTrue(check(unitPrice, new BigDecimal("1.555")).get(0).contains("scale 2"));
        assertEquals(1, check(unitPrice, new BigDecimal("12345678901234.5")).stream()
                .filter(m -> m.contains("precision 12")).count(), "14 integer digits exceed precision 12");
        assertEquals(1, check(unitPrice, 12345678901234.5d).stream()
                .filter(m -> m.contains("precision 12")).count(), "a JSON double is checked as its decimal value");
    }

    @Test
    void anUndeclaredConstraintAndAnAbsentValueNeverViolate() {
        assertTrue(check(field("free", "string", null, null, null, null, null), "x".repeat(10_000)).isEmpty());
        assertTrue(check(field("n", "decimal", null, null, null, null, null), new BigDecimal("1.123456789")).isEmpty());
        assertTrue(check(field("title", "string", 1, null, null, null, null), null).isEmpty());
        assertTrue(FieldValueConstraints.violations("Thing", null, "x").isEmpty());
        assertTrue(check(new CompiledField("plain", "string", "String", false, false, false), "x").isEmpty(),
                "a field without a schema declares nothing");
    }

    @Test
    void aDerivedFieldIsNeverRefusedBecauseTheValueIsComputedByThePlatform() {
        CompiledSchema derived = new CompiledSchema("decimal", Map.of(), null, List.of(), List.of(), null, null,
                "quantity * unitPrice", "", null, null, 12, 2, null, null, null, null, null, null, null, null);
        CompiledField lineTotal = new CompiledField("lineTotal", "decimal", "Object", false, false, false,
                List.of(), null, null, null, derived, List.of());

        assertTrue(check(lineTotal, 37.019999999999996d).isEmpty());
    }

    @Test
    void minLengthAcceptsTheBoundaryAndRejectsOneUnder() {
        CompiledSchema schema = new CompiledSchema("string", Map.of(), List.of(), "", 3, 12, null, null, null);

        assertTrue(FieldValueConstraints.violations("Thing", "shortName", "string", schema, "abc").isEmpty());
        List<String> under = FieldValueConstraints.violations("Thing", "shortName", "string", schema, "ab");
        assertEquals(1, under.size());
        assertTrue(under.get(0).contains("field 'shortName'") && under.get(0).contains("minLength 3"), under.get(0));
    }

    @Test
    void regexIsSearchedAndRefusesAMismatchOnFreeTextOnly() {
        CompiledSchema schema = new CompiledSchema("string", Map.of(), List.of(), "", null, null, null, null, "^[A-Z0-9-]+$");

        assertTrue(FieldValueConstraints.violations("Thing", "code", "string", schema, "AB-12").isEmpty());
        List<String> bad = FieldValueConstraints.violations("Thing", "code", "string", schema, "a!");
        assertEquals(1, bad.size());
        assertTrue(bad.get(0).contains("field 'code'") && bad.get(0).contains("pattern"), bad.get(0));
        assertTrue(FieldValueConstraints.violations("Thing", "code", "enum", schema, "a!").isEmpty(), "an enum is not free text");

        CompiledSchema broken = new CompiledSchema("string", Map.of(), List.of(), "", null, null, null, null, "[unclosed");
        List<String> invalid = FieldValueConstraints.violations("Thing", "code", "string", broken, "x");
        assertEquals(1, invalid.size(), "an uncompilable pattern fails closed instead of silently matching");
        assertTrue(invalid.get(0).contains("not a valid regular expression"), invalid.get(0));
    }

    @Test
    void theNestedPathOverloadNamesTheDottedPathAndAppliesTheRangeRules() {
        CompiledSchema level = new CompiledSchema("integer", Map.of(), List.of(), "", null, null, 1.0, 9.0, null);

        List<String> over = FieldValueConstraints.violations("Thing", "profile.level", "integer", level, 12);
        assertEquals(1, over.size());
        assertTrue(over.get(0).contains("field 'profile.level'") && over.get(0).contains("above max 9"), over.get(0));
        assertTrue(FieldValueConstraints.isNumericType("Integer") && FieldValueConstraints.isIntegralType("integer"));
        assertTrue(!FieldValueConstraints.isIntegralType("decimal"));
    }

    @Test
    void aTextLimitIsNotAppliedToNonTextTypes() {
        assertTrue(check(field("when", "datetime", 3, null, null, null, null), "2026-01-01T00:00:00Z").isEmpty());
        assertTrue(check(field("kind", "enum", 3, null, null, null, null), "LONGVALUE").isEmpty());
    }
}
