package com.npdev.dsl.v1.schemaevolution;

import java.util.Locale;

/**
 * LNCH-1 remediation R1 (finding F3). The SINGLE shared SQL-type normalizer used by BOTH
 * producers of a destructive-item stable string:
 * <ul>
 *   <li>the runtime executor ({@code com.finalexec.db.SchemaLifecycleExecutor#normalizeSqlType},
 *       now a one-line delegate to this class) and {@code com.finalexec.db.SchemaDeltaReport},
 *       which normalize LIVE JDBC-reported type names read back from the database at boot; and</li>
 *   <li>the generator ({@code com.npdev.generator.schemaevolution.MigrationPlanEmitter}), which
 *       normalizes MODEL-declared type strings (as produced by
 *       {@code com.npdev.dsl.v1.compiled.SqlTypeSupport#sqlType}) when previewing a future
 *       boot's classification.</li>
 * </ul>
 *
 * <p>Before R1 these two sides normalized independently -- the executor via its own
 * {@code normalizeSqlType}, the generator not at all (it put raw model-declared strings into
 * {@code DropColumn}/{@code NarrowType}). Any type whose live-metadata spelling diverged from its
 * model-declared spelling (e.g. H2 reports {@code CHARACTER VARYING} for a {@code VARCHAR(n)}
 * column; Postgres reports {@code int8} for {@code BIGINT}) produced two different stable strings
 * -> two different acknowledgment tokens -> a refused boot whose "expected token" the plan never
 * showed. Routing BOTH sides through this one method makes the token byte-identical by
 * construction, and {@code TokenAgreementConformanceTest} is the permanent ratchet that fails the
 * instant a future new type or engine alias drifts.
 *
 * <p>Lives in {@code com.npdev.dsl.v1.schemaevolution} (the DSL module) for the same reason
 * {@link SchemaDeltaItem}/{@link TypeChangeMatrix}/{@link DestructiveAckToken} do: the DSL jar is
 * on both the generator's and RuntimeHost's classpaths, so both sides depend on the identical
 * bytecode and can never independently drift.
 */
public final class SqlTypeNormalization {

    private SqlTypeNormalization() {
    }

    /**
     * Canonicalizes a SQL type string (live-JDBC-reported OR model-declared) into the single
     * spelling both producers agree on. Uppercases and whitespace-collapses the base name, applies
     * the engine/JDBC alias table below, and preserves any parenthesized size/precision (with all
     * internal whitespace stripped) so a VARCHAR-length or NUMERIC-precision change is still
     * distinguishable. Returns {@code null} for a null/blank input (the callers' documented
     * "unreadable" fallback signal).
     *
     * <p>The alias table is the union of every spelling either side can present for the same
     * logical type; each entry records the engine + source that motivated it:
     * <ul>
     *   <li>{@code JSONB -> JSON}: the model declares {@code JSONB} (SqlTypeSupport maps
     *       object/array/file fields to JSONB); H2 has no JSONB and renders/reports it as JSON.</li>
     *   <li>{@code CHARACTER VARYING -> VARCHAR}: H2 reports {@code CHARACTER VARYING} via
     *       {@code TYPE_NAME} for a column declared {@code VARCHAR(n)}.</li>
     *   <li>{@code INT4/INT8/INT2/BOOL/TIMESTAMPTZ/FLOAT4/FLOAT8}: confirmed against a real
     *       Postgres 15 instance -- its JDBC driver reports these internal pg_type short names via
     *       {@code TYPE_NAME} rather than the SQL-standard names the manifests declare (LNCH-1
     *       Phase 7 task 7.2, commit 74de76c).</li>
     *   <li>{@code INT -> INTEGER}, {@code DECIMAL -> NUMERIC}: SQL-standard synonyms that a driver
     *       or a hand-authored model type could present for the canonical model spelling
     *       ({@code SqlTypeSupport} emits {@code INTEGER}/{@code NUMERIC}); mirrors
     *       {@link TypeChangeMatrix}'s own alias set so classification and hashing agree.</li>
     * </ul>
     */
    public static String normalize(String sqlType) {
        if (sqlType == null || sqlType.isBlank()) {
            return null;
        }
        String trimmed = sqlType.trim().toUpperCase(Locale.ROOT);
        int parenIndex = trimmed.indexOf('(');
        String base = parenIndex >= 0 ? trimmed.substring(0, parenIndex).trim() : trimmed;
        String parameters = parenIndex >= 0 ? trimmed.substring(parenIndex).replaceAll("\\s+", "") : "";
        base = base.replaceAll("\\s+", " ");
        switch (base) {
            case "JSONB" -> base = "JSON";
            case "CHARACTER VARYING" -> base = "VARCHAR";
            case "INT", "INT4" -> base = "INTEGER";
            case "INT8" -> base = "BIGINT";
            case "INT2" -> base = "SMALLINT";
            case "BOOL" -> base = "BOOLEAN";
            case "TIMESTAMPTZ" -> base = "TIMESTAMP WITH TIME ZONE";
            case "FLOAT4" -> base = "REAL";
            case "FLOAT8" -> base = "DOUBLE";
            case "DECIMAL" -> base = "NUMERIC";
            default -> {
                // no alias needed -- VARCHAR, TEXT, NUMERIC, UUID, DATE, JSON, BIGINT, INTEGER,
                // BOOLEAN, SMALLINT, REAL, DOUBLE, TIMESTAMP WITH TIME ZONE all already round-trip
                // exactly once the aliases above are applied.
            }
        }
        return base + parameters;
    }
}
