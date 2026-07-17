package com.finalexec.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LNCH-1 Phase 3. Pure classification of a declared (old SQL type -&gt; new SQL type) change into
 * {@link Classification#WIDENING} (safe, data-preserving, auto-appliable in place),
 * {@link Classification#NARROWING} (may truncate/lose data), or {@link Classification#INCOMPARABLE}
 * (unrelated type families, or an unrecognized type string). Both NARROWING and INCOMPARABLE
 * currently route to the same "refuse, fall through to the existing destructive path" behavior in
 * the executor -- Phase 4 is where itemized acknowledgment tokens get built -- but this class still
 * returns the more semantically accurate bucket per pair, since that distinction is useful for the
 * boot log and will matter once Phase 4 itemizes.
 *
 * <p>Exactly the v1 rule set from {@code docs/LNCH1_SCHEMA_EVOLUTION_PLAN.md} §3.1, nothing more:
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
 * widening item, but {@code SchemaLifecycleExecutor.SchemaManifest#businessTableColumnTypes} --
 * the only per-column type information the executor carries today -- has no NULL/NOT NULL
 * annotation at all (confirmed by reading {@code SchemaRealizationEmitter.columnTypes()} and a real
 * emitted manifest: the values are bare SQL type strings like {@code "VARCHAR(255)"}, nothing
 * more). Nullability relaxation is therefore out of reach for this matrix as currently wired --
 * this class does not attempt it, and it is not part of the (fromSqlType, toSqlType) contract.
 * Flagged here as a follow-up gap: closing it needs new manifest plumbing (a per-column nullable
 * flag), which is disproportionate to add speculatively in this phase.
 */
final class TypeChangeMatrix {

    enum Classification {
        WIDENING,
        NARROWING,
        INCOMPARABLE
    }

    /** SMALLINT -> INTEGER -> BIGINT, in ascending widening order; rank comparison makes the
     * SMALLINT -> BIGINT jump WIDENING too without needing to special-case non-adjacent pairs. */
    private static final List<String> INTEGER_WIDENING_ORDER = List.of("SMALLINT", "INTEGER", "BIGINT");

    private TypeChangeMatrix() {
    }

    static Classification classify(String fromSqlType, String toSqlType) {
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
        // unchanged type (unreachable in practice: the executor only calls this class once
        // hasTypeChange() has already found a diff) or a same-family type this matrix doesn't
        // otherwise parameterize (e.g. BIGINT -> BIGINT). Treat as WIDENING: a no-op is safe to
        // "apply" (idempotent by construction once the ALTER runs).
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
            default -> base;
        };
    }

    private record ParsedType(String base, List<Integer> params) {
        int paramOrDefault(int index, int fallback) {
            return index < params.size() ? params.get(index) : fallback;
        }
    }
}
