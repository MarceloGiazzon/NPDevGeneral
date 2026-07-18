package com.npdev.dsl.v1.schemaevolution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.npdev.dsl.v1.schemaevolution.TypeChangeMatrix.Classification.INCOMPARABLE;
import static com.npdev.dsl.v1.schemaevolution.TypeChangeMatrix.Classification.NARROWING;
import static com.npdev.dsl.v1.schemaevolution.TypeChangeMatrix.Classification.WIDENING;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LNCH-1 Phase 3 (task 3.1/3.4), moved to the DSL module in Phase 6 (task 6.1's (A) share
 * decision -- see {@code MigrationPlanEmitter}'s class javadoc). Exhaustive unit coverage of
 * {@link TypeChangeMatrix}'s v1 rule set (plan §3.1) against synthetic type strings -- deliberately
 * not gated on what the current generator can actually emit (see the plan's "Realistic
 * reachability" note: today's {@code SqlTypeSupport} only ever produces INTEGER->BIGINT as a
 * real-world type change; the other pairs here exercise the matrix as a generic, future-proof, and
 * legacy-DB-tolerant classifier).
 */
class TypeChangeMatrixTest {

    @ParameterizedTest(name = "{0} -> {1} is WIDENING")
    @CsvSource({
            "SMALLINT, INTEGER",
            "INTEGER, BIGINT",
            "SMALLINT, BIGINT",          // transitive, non-adjacent
            "REAL, DOUBLE",
            "'NUMERIC(10,2)', 'NUMERIC(19,2)'",
            "'NUMERIC(5,2)', 'NUMERIC(5,2)'", // equal precision/scale -- idempotent no-op case
            "'VARCHAR(20)', 'VARCHAR(50)'",
            "'VARCHAR(20)', 'VARCHAR(20)'",   // equal length -- idempotent no-op case
            "'VARCHAR(255)', TEXT",
            "'VARCHAR(255)', CLOB",
            "BIGINT, BIGINT"
    })
    void widening(String from, String to) {
        assertEquals(WIDENING, TypeChangeMatrix.classify(from, to));
    }

    @ParameterizedTest(name = "{0} -> {1} is NARROWING")
    @CsvSource({
            "BIGINT, INTEGER",
            "BIGINT, SMALLINT",
            "INTEGER, SMALLINT",
            "DOUBLE, REAL",
            "'VARCHAR(50)', 'VARCHAR(20)'",
            "'NUMERIC(10,2)', 'NUMERIC(5,2)'",
            "TEXT, 'VARCHAR(255)'",
            "CLOB, 'VARCHAR(255)'"
    })
    void narrowing(String from, String to) {
        assertEquals(NARROWING, TypeChangeMatrix.classify(from, to));
    }

    @Test
    void numericScaleChangeIsClassifiedNarrowingNotIncomparable() {
        // Design decision (documented on the class): a same-precision, DIFFERENT-scale NUMERIC
        // change is not in the v1 widening set. It lands in NARROWING, not INCOMPARABLE -- still
        // informative as "same family, needs a closer look" rather than a wholesale mismatch.
        assertEquals(NARROWING, TypeChangeMatrix.classify("NUMERIC(10,2)", "NUMERIC(10,4)"));
        assertEquals(NARROWING, TypeChangeMatrix.classify("NUMERIC(10,4)", "NUMERIC(10,2)"));
        // Larger precision but ALSO a scale change -- scale mismatch still wins (not auto-widened).
        assertEquals(NARROWING, TypeChangeMatrix.classify("NUMERIC(10,2)", "NUMERIC(19,4)"));
    }

    @ParameterizedTest(name = "{0} -> {1} is INCOMPARABLE")
    @CsvSource({
            "'VARCHAR(255)', BIGINT",
            "BIGINT, 'VARCHAR(255)'",
            "BOOLEAN, INTEGER",
            "UUID, BIGINT",
            "DATE, 'VARCHAR(255)'"
    })
    void incomparable(String from, String to) {
        assertEquals(INCOMPARABLE, TypeChangeMatrix.classify(from, to));
    }

    @Test
    void unparseableOrMissingTypesAreIncomparable() {
        assertEquals(INCOMPARABLE, TypeChangeMatrix.classify(null, "BIGINT"));
        assertEquals(INCOMPARABLE, TypeChangeMatrix.classify("BIGINT", null));
        assertEquals(INCOMPARABLE, TypeChangeMatrix.classify("", "BIGINT"));
        assertEquals(INCOMPARABLE, TypeChangeMatrix.classify("VARCHAR(notanumber)", "VARCHAR(50)"));
        assertEquals(INCOMPARABLE, TypeChangeMatrix.classify("VARCHAR(20", "VARCHAR(50)"));
    }

    @Test
    void caseAndWhitespaceVariantsOfTheSameTypeStringCompareEqual() {
        // Different case, different internal spacing around the parens/comma -- must normalize to
        // the identical parsed form and therefore classify as the same (here: idempotent WIDENING).
        assertEquals(WIDENING, TypeChangeMatrix.classify("varchar(50)", " VARCHAR ( 50 ) "));
        assertEquals(WIDENING, TypeChangeMatrix.classify("numeric(10,2)", "NUMERIC ( 10 , 2 )"));
        assertEquals(WIDENING, TypeChangeMatrix.classify("bigint", " BigInt "));
    }

    @Test
    void h2CharacterVaryingAliasesToVarcharForComparison() {
        // H2's live JDBC metadata reports "CHARACTER VARYING" for a VARCHAR column (see
        // SchemaLifecycleExecutorVarcharTypeNormalizationTest) -- the matrix must treat it the
        // same as the manifest's canonical "VARCHAR" when classifying a widening pair.
        assertEquals(WIDENING, TypeChangeMatrix.classify("CHARACTER VARYING(20)", "VARCHAR(50)"));
        assertEquals(WIDENING, TypeChangeMatrix.classify("VARCHAR(20)", "CHARACTER VARYING(50)"));
    }

    @Test
    void intAliasesToIntegerInTheWideningOrder() {
        assertEquals(WIDENING, TypeChangeMatrix.classify("INT", "BIGINT"));
        assertEquals(NARROWING, TypeChangeMatrix.classify("BIGINT", "INT"));
    }
}
