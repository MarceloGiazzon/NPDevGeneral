package com.finalexec.api;

import com.npdev.dsl.v1.compiled.CompiledProcedureParameter;
import org.junit.jupiter.api.Test;

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
}
