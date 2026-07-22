package com.finalexec.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LNCH-1 Phase 7 (task 7.2) -- a pre-existing bug found while building the Postgres Testcontainers
 * twin ({@code SchemaLifecycleExecutorPostgresProofMatrixTest}), fixed in its own commit per
 * guardrail 10, unit-tested here as a pure string function (no database needed -- confirmed against
 * a real Postgres 15 Testcontainers instance separately, in the twin test, that these are exactly
 * the raw {@code TYPE_NAME} values Postgres's JDBC driver actually reports).
 *
 * <p>{@link SchemaLifecycleExecutor#normalizeSqlType} already aliased H2's
 * {@code "CHARACTER VARYING"} and the generator's {@code "JSONB"} to their canonical forms, but
 * never accounted for Postgres's OWN internal pg_type short names (e.g. a column declared BIGINT
 * live-reports {@code TYPE_NAME="int8"}) -- so on Postgres, {@code classify()}/{@code hasTypeChange()}
 * misclassified every unchanged INTEGER/BIGINT/SMALLINT/BOOLEAN/REAL/DOUBLE column as a type change
 * the moment ANY fingerprint mismatch triggered a diff, which (per {@code beforeMigrate}'s per-table
 * all-or-nothing composition) could route an otherwise-safe change straight to destructive
 * recreation. Left latent until this phase because no prior phase had a real Postgres instance to
 * catch it against.
 */
class SchemaLifecycleExecutorNormalizeSqlTypePostgresAliasTest {

    @ParameterizedTest(name = "normalizeSqlType({0}) == normalizeSqlType({1})")
    @CsvSource({
            "int4, INTEGER",
            "int8, BIGINT",
            "int2, SMALLINT",
            "bool, BOOLEAN",
            "float4, REAL",
            "float8, DOUBLE",
            "timestamptz, 'TIMESTAMP WITH TIME ZONE'",
            // case-insensitivity of the Postgres short name itself, matching the existing
            // case-insensitive handling of every other alias in this method.
            "INT8, BIGINT",
            "Int8, BIGINT"
    })
    void postgresShortTypeNameNormalizesToTheCanonicalManifestForm(String postgresLive, String manifestCanonical) {
        assertEquals(SchemaLifecycleExecutor.normalizeSqlType(manifestCanonical),
                SchemaLifecycleExecutor.normalizeSqlType(postgresLive),
                "a Postgres-live-reported type and the manifest's canonical declaration for the same "
                        + "column must normalize identically, or every unchanged column of this type "
                        + "would be misclassified as a type change on every boot");
    }

    @Test
    void preExistingAliasesAreUnregressed() {
        // The H2/generator aliases this method already had before this phase must still hold.
        assertEquals(SchemaLifecycleExecutor.normalizeSqlType("VARCHAR"),
                SchemaLifecycleExecutor.normalizeSqlType("CHARACTER VARYING"));
        assertEquals(SchemaLifecycleExecutor.normalizeSqlType("JSON"),
                SchemaLifecycleExecutor.normalizeSqlType("JSONB"));
    }

    @Test
    void lengthAndPrecisionParametersSurviveThePostgresAliasUnchanged() {
        // The alias only rewrites the BASE type name -- a parenthesized length/precision (already
        // appended by qualifyTypeWithSize before this method ever sees the string) must pass through.
        assertEquals("VARCHAR(255)", SchemaLifecycleExecutor.normalizeSqlType("varchar(255)"));
        assertEquals("NUMERIC(19,2)", SchemaLifecycleExecutor.normalizeSqlType("numeric(19,2)"));
    }
}
