package com.npdev.kernel.storage.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FORM P3-T4: the great-circle SQL each dialect renders, checked as TEXT so it runs on a machine whose
 * local profile has SQL Server / MySQL / Postgres switched off. The numeric answer is pinned against a
 * real engine by the dialect conformance tests; this pins the per-dialect differences (float type, the
 * clamp, how often a bind marker repeats) that those tests only reach when the engine is enabled.
 */
class GreatCircleSqlRenderingTest {

    private static SqlDialect.GreatCircleSql render(SqlDialect dialect) {
        return dialect.greatCircleKm("a", "b", "c", "d");
    }

    @Test
    void theDefaultDialectsClampWithLeastAndUseDoublePrecisionOrDouble() {
        for (SqlDialect dialect : List.of(H2Dialect.INSTANCE, PostgresDialect.INSTANCE)) {
            String sql = render(dialect).sql();
            assertTrue(sql.contains("LEAST(1, "), dialect + ": " + sql);
            assertTrue(sql.contains("AS DOUBLE PRECISION"), dialect + ": " + sql);
            assertTrue(sql.endsWith(", 3)"), dialect + ": " + sql);
        }
        assertTrue(render(H2Dialect.INSTANCE).sql().startsWith("ROUND(2 * 6371 * ASIN("));
    }

    @Test
    void postgresRoundsAsNumericBecauseItHasNoRoundOfDoublePrecisionWithDigits() {
        String sql = render(PostgresDialect.INSTANCE).sql();
        assertTrue(sql.startsWith("ROUND(CAST(2 * 6371 * ASIN("), sql);
        assertTrue(sql.endsWith(" AS NUMERIC), 3)"), sql);
    }

    @Test
    void mySqlCastsToDoubleBecauseItsCastHasNoDoublePrecisionTarget() {
        String sql = render(MySqlDialect.INSTANCE).sql();
        assertTrue(sql.contains("AS DOUBLE)"), sql);
        assertFalse(sql.contains("DOUBLE PRECISION"), sql);
        assertTrue(sql.contains("LEAST(1, "), sql);
    }

    @Test
    void sqlServerCastsToFloatAndClampsWithCaseWhichRepeatsTheExpression() {
        SqlDialect.GreatCircleSql rendered = render(SqlServerDialect.INSTANCE);
        assertTrue(rendered.sql().contains("AS FLOAT)"), rendered.sql());
        assertTrue(rendered.sql().contains("CASE WHEN "), rendered.sql());
        assertFalse(rendered.sql().contains("LEAST("), rendered.sql());
        assertEquals(2, SqlServerDialect.INSTANCE.leastOneUses());
        assertEquals(1, H2Dialect.INSTANCE.leastOneUses());
    }

    @Test
    void theOperandOrderRepeatsOncePerUseOfTheClampedExpression() {
        // one pass through the haversine term lists lat2, lat1, lat1, lat2, lng2, lng1 (indexes 2,0,0,2,3,1)
        List<Integer> pass = List.of(2, 0, 0, 2, 3, 1);
        assertEquals(pass, render(H2Dialect.INSTANCE).operandOrder());
        List<Integer> twice = new java.util.ArrayList<>(pass);
        twice.addAll(pass);
        assertEquals(twice, render(SqlServerDialect.INSTANCE).operandOrder());
    }

    @Test
    void everyBindMarkerArgumentIsReferencedOncePerListedOperand() {
        SqlDialect.GreatCircleSql rendered = H2Dialect.INSTANCE.greatCircleKm("?", "?", "?", "?");
        long markers = rendered.sql().chars().filter(ch -> ch == '?').count();
        assertEquals(rendered.operandOrder().size(), markers);
    }
}
