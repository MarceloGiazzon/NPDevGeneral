package com.npdev.dsl.v1.schemaevolution;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LNCH-1 Phase 3, moved to the DSL module in Phase 6 (task 6.1's (A) share decision -- see
 * {@code MigrationPlanEmitter}'s class javadoc for the full reasoning). Pure classification of a
 * declared (old SQL type -&gt; new SQL type) change into {@link Classification#WIDENING} (safe,
 * data-preserving, auto-appliable in place), {@link Classification#NARROWING} (may
 * truncate/lose data), or {@link Classification#INCOMPARABLE} (unrelated type families, or an
 * unrecognized type string).
 *
 * <p>Lives in {@code com.npdev.dsl.v1.schemaevolution} -- not RuntimeHost, not the generator --
 * so BOTH {@code com.finalexec.db.SchemaDeltaReport} (the runtime executor's residual-diff
 * itemization, re-derived from live-DB introspection at boot) and
 * {@code com.npdev.generator.schemaevolution.MigrationPlanEmitter} (the generator's model-vs-model
 * preview) classify a type change with the IDENTICAL bytecode -- one derivation, not two
 * independently-maintained copies that could silently drift apart. Both sides already depend on
 * the {@code :dsl} module (the generator directly; RuntimeHost via the {@code runtimehost-libs}
 * jar-staging mechanism that already ships {@code com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson*}
 * into every generated app).
 *
 * <p>Exactly the v1 rule set from {@code docs/archive/programme-history/LNCH1_SCHEMA_EVOLUTION_PLAN.md} §3.1, nothing more:
 * <ul>
 *   <li>{@code SMALLINT -> INTEGER -> BIGINT}, transitively ({@code SMALLINT -> BIGINT} is WIDENING
 *       too, even though the two types are not adjacent in the rule list).</li>
 *   <li>{@code REAL -> DOUBLE}.</li>
 *   <li>{@code NUMERIC(p,s) -> NUMERIC(p',s)} where {@code p' >= p} AND the scale {@code s} is
 *       UNCHANGED. A scale change is NOT in the v1 widening set -- see the class-level note below
 *       on where that case lands.</li>
 *   <li>{@code VARCHAR(n) -> VARCHAR(m)} where {@code m >= n}.</li>
 *   <li>{@code VARCHAR(n) -> TEXT / CLOB}.</li>
 * </ul>
 * Everything else -- a type-family mismatch (e.g. {@code VARCHAR -> INTEGER}), any narrowing
 * direction, a NUMERIC scale change, or an unrecognized/unparseable type string -- is NARROWING or
 * INCOMPARABLE.
 *
 * <p><b>NUMERIC scale-change design decision:</b> classified {@code NARROWING}, not INCOMPARABLE.
 * A scale change (e.g. {@code NUMERIC(10,2) -> NUMERIC(10,4)}) is a real, potentially
 * data-altering change to the stored representation (existing values would need re-scaling, which
 * this class's "just widen the column" mechanism cannot safely do without touching row data) but
 * it is still comparing two NUMERIC types, i.e. more informative to the operator as "this needs a
 * closer look at the same family" than a bucket shared with a wholesale type-family mismatch.
 *
 * <p><b>NOT NULL -&gt; nullable scope decision:</b> the plan lists relaxing nullability as a
 * widening item, but neither side's per-column type information carries a NULL/NOT NULL
 * annotation today (bare SQL type strings only, e.g. {@code "VARCHAR(255)"}). Nullability
 * relaxation is therefore out of reach for this matrix as currently wired -- this class does not
 * attempt it, and it is not part of the (fromSqlType, toSqlType) contract. Flagged here as a
 * follow-up gap: closing it needs new manifest/model plumbing (a per-column nullable flag), which
 * is disproportionate to add speculatively.
 */
public final class TypeChangeMatrix {

    public enum Classification {
        WIDENING,
        NARROWING,
        INCOMPARABLE
    }

    /** SMALLINT -> INTEGER -> BIGINT, in ascending widening order; rank comparison makes the
     * SMALLINT -> BIGINT jump WIDENING too without needing to special-case non-adjacent pairs. */
    private static final List<String> INTEGER_WIDENING_ORDER = List.of("SMALLINT", "INTEGER", "BIGINT");

    private TypeChangeMatrix() {
    }

    public static Classification classify(String fromSqlType, String toSqlType) {
        ParsedType from = parse(fromSqlType);
        ParsedType to = parse(toSqlType);
        if (from == null || to == null) {
            return Classification.INCOMPARABLE;
        }
        if (from.base.equals(to.base)) {
            return classifySameFamily(from, to);
        }

        int fromRank = INTEGER_WIDENING_ORDER.indexOf(from.base);
        int toRank = INTEGER_WIDENING_ORDER.indexOf(to.base);
        if (fromRank >= 0 && toRank >= 0) {
            return toRank > fromRank ? Classification.WIDENING : Classification.NARROWING;
        }
        if ("REAL".equals(from.base) && "DOUBLE".equals(to.base)) {
            return Classification.WIDENING;
        }
        if ("DOUBLE".equals(from.base) && "REAL".equals(to.base)) {
            return Classification.NARROWING;
        }
        if ("VARCHAR".equals(from.base) && "TEXT".equals(to.base)) {
            return Classification.WIDENING;
        }
        if ("TEXT".equals(from.base) && "VARCHAR".equals(to.base)) {
            return Classification.NARROWING;
        }
        return Classification.INCOMPARABLE;
    }

    private static Classification classifySameFamily(ParsedType from, ParsedType to) {
        if ("VARCHAR".equals(from.base)) {
            int n = from.paramOrDefault(0, -1);
            int m = to.paramOrDefault(0, -1);
            if (n < 0 || m < 0) {
                return Classification.INCOMPARABLE;
            }
            return m >= n ? Classification.WIDENING : Classification.NARROWING;
        }
        if ("NUMERIC".equals(from.base)) {
            int p = from.paramOrDefault(0, -1);
            int s = from.paramOrDefault(1, 0);
            int p2 = to.paramOrDefault(0, -1);
            int s2 = to.paramOrDefault(1, 0);
            if (p < 0 || p2 < 0) {
                return Classification.INCOMPARABLE;
            }
            if (s != s2) {
                // Scale change: not in the v1 widening set -- see the class-level design note.
                return Classification.NARROWING;
            }
            return p2 >= p ? Classification.WIDENING : Classification.NARROWING;
        }
        // Identical base and (if any) identical/unparameterized params -- either a genuinely
        // unchanged type (unreachable in practice: callers only invoke this class once a diff has
        // already been found) or a same-family type this matrix doesn't otherwise parameterize
        // (e.g. BIGINT -> BIGINT). Treat as WIDENING: a no-op is safe to "apply" (idempotent by
        // construction once the ALTER runs).
        return Classification.WIDENING;
    }

    /**
     * Parses a raw SQL type string (as carried by the manifest or read live via JDBC, e.g.
     * {@code "VARCHAR(255)"}, {@code "  numeric ( 10 , 2 ) "}, {@code "BIGINT"}) into a
     * case/whitespace-normalized base type name plus its parenthesized integer parameters, if any.
     * Deliberately independent of {@code SchemaLifecycleExecutor.normalizeSqlType} -- this class is
     * a standalone pure unit with no dependency on the executor, and needs the parsed integer
     * parameters (not just a normalized string) to compare lengths/precisions numerically.
     */
    private static ParsedType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(Locale.ROOT);
        int parenIndex = trimmed.indexOf('(');
        String base = (parenIndex >= 0 ? trimmed.substring(0, parenIndex) : trimmed).trim().replaceAll("\\s+", " ");
        base = alias(base);
        if (parenIndex < 0) {
            return new ParsedType(base, List.of());
        }
        int closeIndex = trimmed.lastIndexOf(')');
        if (closeIndex < parenIndex) {
            return null;
        }
        String inner = trimmed.substring(parenIndex + 1, closeIndex);
        List<Integer> params = new ArrayList<>();
        for (String part : inner.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                params.add(Integer.parseInt(value));
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return new ParsedType(base, List.copyOf(params));
    }

    private static String alias(String base) {
        return switch (base) {
            case "CHARACTER VARYING" -> "VARCHAR";
            case "INT" -> "INTEGER";
            case "DECIMAL" -> "NUMERIC";
            case "CLOB", "TEXT" -> "TEXT";
            // LNCH-1 Phase 7 (task 7.2) fix: confirmed against a real Postgres 15 instance that its
            // JDBC driver reports these internal pg_type short names via TYPE_NAME (e.g. a BIGINT
            // column -> "int8"), not the SQL-standard names this project's manifests declare.
            // attemptInPlaceTypeWidenings passes the RAW (unnormalized) live JDBC type straight into
            // classify(fromSqlType, toSqlType) -- without this alias, e.g. an unchanged INTEGER
            // column ("int4" live vs "INTEGER" expected) parsed to different bases and fell through
            // to the same-family/no-op path never being reached, instead comparing as two DIFFERENT,
            // unrelated bases -- INCOMPARABLE -- which made the per-table all-or-nothing rule (LNCH-1
            // Phase 3) block an otherwise-safe widening on any table that also happened to have an
            // untouched INTEGER/BIGINT/BOOLEAN column, on Postgres only (H2's native type names
            // already matched the canonical form, so this was invisible without a real Postgres run --
            // see SchemaLifecycleExecutor.normalizeSqlType's sibling fix for the same root cause in
            // the classify()/hasTypeChange() path).
            case "INT4" -> "INTEGER";
            case "INT8" -> "BIGINT";
            case "INT2" -> "SMALLINT";
            case "BOOL" -> "BOOLEAN";
            case "FLOAT4" -> "REAL";
            case "FLOAT8" -> "DOUBLE";
            default -> base;
        };
    }

    private record ParsedType(String base, List<Integer> params) {
        int paramOrDefault(int index, int fallback) {
            return index < params.size() ? params.get(index) : fallback;
        }
    }
}
