package com.npdev.dsl.v1.compiled;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** REG-274: the domain type's normalization rules are applied, in order, to text values only. */
class DomainNormalizationTest {

    @Test
    void rulesRunInDeclaredOrderAndAreCaseInsensitive() {
        assertEquals("AB-12", DomainNormalization.apply(List.of("trim", "uppercase"), " ab-12 "));
        assertEquals("ab-12", DomainNormalization.apply(List.of("Trim", "LOWERCASE"), " AB-12 "));
        assertEquals("a b c", DomainNormalization.apply(List.of("collapseWhitespace"), "a  b \t c"));
        assertEquals("1234567890", DomainNormalization.apply(List.of("digitsOnly", "trim"), " 123-456 7890 "));
    }

    @Test
    void anUnknownOrNullRuleIsIgnored() {
        assertEquals(" x ", DomainNormalization.apply(Arrays.asList("rot13", null), " x "));
    }

    @Test
    void nonTextValuesAndEmptyRuleListsPassThroughUntouched() {
        Integer number = 7;
        assertSame(number, DomainNormalization.apply(List.of("uppercase"), number));
        assertEquals(" x ", DomainNormalization.apply(List.of(), " x "));
        assertEquals(" x ", DomainNormalization.apply(null, " x "));
    }
}
