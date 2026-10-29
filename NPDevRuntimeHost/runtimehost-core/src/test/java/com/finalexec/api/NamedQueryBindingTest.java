package com.finalexec.api;

import com.npdev.dsl.v1.compiled.CompiledProcedureParameter;
import com.npdev.dsl.v1.expr.DateFunctions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** REP-1: the binding rules extracted from the generated query controller must stay exactly those. */
class NamedQueryBindingTest {

    @Test
    void coercesByShape() {
        assertEquals(true, NamedQueryBinding.coerceLiteral(" TRUE "));
        assertEquals(false, NamedQueryBinding.coerceLiteral("false"));
        assertEquals(42L, NamedQueryBinding.coerceLiteral("42"));
        assertEquals(1.5d, NamedQueryBinding.coerceLiteral("1.5"));
        assertEquals("abc", NamedQueryBinding.coerceLiteral("abc"));
        assertEquals(" x ", NamedQueryBinding.coerceLiteral(" x "), "plain strings keep their raw form");
    }

    @Test
    void anUnboundDeclaredParameterIsRefusedNotDefaulted() {
        CompiledProcedureParameter minPrice = new CompiledProcedureParameter("minPrice", "decimal", false, null, null);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> NamedQueryBinding.bind(name -> null, List.of(minPrice)));
        assertEquals("Query parameter 'minPrice' is required (declared in this query's parameters[] but not supplied).",
                refused.getMessage());
        Map<String, Object> bound = NamedQueryBinding.bind(name -> "7", List.of(minPrice));
        assertEquals(Map.of("minPrice", 7L), bound);
    }

    // ---- REG-313 (b): a declared defaultExpression stands in for an absent value ----

    private static CompiledProcedureParameter withDefault(String expression) {
        return new CompiledProcedureParameter("asOf", "date", false, null, null, expression);
    }

    @Test
    void anAbsentParameterTakesItsDefaultExpression() {
        Map<String, Object> bound = DateFunctions.withToday(LocalDate.of(2026, 10, 29), () ->
                NamedQueryBinding.bind(name -> null, List.of(withDefault("addDays(today(), -7)"))));
        assertEquals(Map.of("asOf", "2026-10-22"), bound);
    }

    @Test
    void aSuppliedValueAlwaysWinsOverTheDefault() {
        Map<String, Object> bound = DateFunctions.withToday(LocalDate.of(2026, 10, 29), () ->
                NamedQueryBinding.bind(name -> "2026-01-01", List.of(withDefault("addDays(today(), -7)"))));
        assertEquals(Map.of("asOf", "2026-01-01"), bound);
    }

    @Test
    void literalDefaultsKeepTheirNaturalType() {
        assertEquals(Map.of("asOf", "2026-01-01"),
                NamedQueryBinding.bind(name -> null, List.of(withDefault("'2026-01-01'"))));
        assertEquals(Map.of("asOf", 42L), NamedQueryBinding.bind(name -> null, List.of(withDefault("42"))));
    }

    @Test
    void aDefaultThatEvaluatesToNothingIsRefusedLikeAMissingParameter() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> NamedQueryBinding.bind(name -> null, List.of(withDefault("addDays('not-a-date', 1)"))));
        assertEquals("Query parameter 'asOf' was not supplied and its defaultExpression "
                + "(addDays('not-a-date', 1)) evaluated to no value.", refused.getMessage());
    }

    @Test
    void aDefaultTheRuntimeCannotEvaluateIsAServerFaultNotABadRequest() {
        assertThrows(IllegalStateException.class,
                () -> NamedQueryBinding.bind(name -> null, List.of(withDefault("addDays(today(,"))));
    }

    @Test
    void parametersWithoutADefaultStillCoexistWithOnesThatHaveOne() {
        CompiledProcedureParameter minPrice = new CompiledProcedureParameter("minPrice", "decimal", false, null, null);
        Map<String, Object> bound = DateFunctions.withToday(LocalDate.of(2026, 10, 29), () ->
                NamedQueryBinding.bind(name -> "minPrice".equals(name) ? "7" : null,
                        List.of(minPrice, withDefault("today()"))));
        assertEquals(Map.of("minPrice", 7L, "asOf", "2026-10-29"), bound);
    }
}
