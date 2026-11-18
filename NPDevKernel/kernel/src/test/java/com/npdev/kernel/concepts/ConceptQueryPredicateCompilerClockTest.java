package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.expr.DateFunctions;
import com.npdev.dsl.v1.query.QueryPredicateGrammar.PredicateOperator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-341: {@code today()} / {@code now()} on the right of a comparison compile to a literal evaluated when the
 * predicate is compiled (every request), so a server-side {@code count('Ride', createdAt >= today())} answers a number
 * instead of the null a refused predicate gives. Anything else in call position is still refused (X0).
 */
class ConceptQueryPredicateCompilerClockTest {

    @Test
    void todayOnTheRightBecomesTodaysIsoDate() {
        List<ConceptQuery.Filter> filters = DateFunctions.withToday(LocalDate.of(2026, 11, 18),
                () -> ConceptQueryPredicateCompiler.compileToConceptQueryFilters(
                        "status == 'NO_DRIVER' && createdAt >= today()"));
        assertEquals(2, filters.size());
        assertEquals(new ConceptQuery.Filter("status", ConceptQuery.Operator.EQ, "NO_DRIVER"), filters.get(0));
        assertEquals(new ConceptQuery.Filter("createdAt", ConceptQuery.Operator.GTE, "2026-11-18"), filters.get(1));
    }

    @Test
    void theDateIsReadEachTimeThePredicateIsCompiledNotFrozenInTheGrammar() {
        String where = "listedOn >= today()";
        Object first = DateFunctions.withToday(LocalDate.of(2026, 1, 1),
                () -> ConceptQueryPredicateCompiler.compileToConceptQueryFilters(where).get(0).value());
        Object second = DateFunctions.withToday(LocalDate.of(2026, 1, 2),
                () -> ConceptQueryPredicateCompiler.compileToConceptQueryFilters(where).get(0).value());
        assertEquals("2026-01-01", first);
        assertEquals("2026-01-02", second);
    }

    @Test
    void nowOnTheRightBecomesAnInstantText() {
        List<ConceptQuery.Filter> filters = ConceptQueryPredicateCompiler.compileToConceptQueryFilters("createdAt < now()");
        assertEquals(1, filters.size());
        assertEquals(ConceptQuery.Operator.LT, filters.get(0).operator());
        assertTrue(String.valueOf(filters.get(0).value()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z"), String.valueOf(filters.get(0).value()));
    }

    @Test
    void otherFunctionsAndArgumentsAreStillRefused() {
        assertThrows(ConceptQueryPredicateCompiler.UnsupportedPredicateException.class,
                () -> ConceptQueryPredicateCompiler.compileToConceptQueryFilters("createdAt >= addDays(today(), 1)"));
        assertThrows(ConceptQueryPredicateCompiler.UnsupportedPredicateException.class,
                () -> ConceptQueryPredicateCompiler.compileToConceptQueryFilters("createdAt >= upper('x')"));
    }

    @Test
    void theOperatorIsKeptForEverySupportedComparison() {
        for (PredicateOperator operator : List.of(PredicateOperator.GT, PredicateOperator.GTE, PredicateOperator.LT, PredicateOperator.LTE)) {
            List<ConceptQuery.Filter> filters = ConceptQueryPredicateCompiler.compileToConceptQueryFilters(
                    "listedOn " + operator.token() + " today()");
            assertEquals(1, filters.size(), operator.token());
        }
    }
}
