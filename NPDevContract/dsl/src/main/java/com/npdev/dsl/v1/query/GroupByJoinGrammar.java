package com.npdev.dsl.v1.query;

import java.util.ArrayList;
import java.util.List;

/**
 * S4 (roadmap B27, ADR-0011 D1): the grammar half of a {@code query.groupBy[]} join hop -- lifted
 * into {@code NPDevContract/dsl} (same reasoning as {@link QueryPredicateGrammar}'s own docstring)
 * so it is usable from BOTH authoring-time validation ({@code PackValidation.validateAggregateQuery})
 * and every runtime consumer that resolves a {@code groupBy} field to a real column
 * ({@code JdbcBusinessConceptStore}, {@code InMemoryConceptStore}) -- one grammar, not drifting
 * copies.
 *
 * <p><b>The shape, straight from B20's own ordering rationale (ADR-0011 D1):</b> with {@code ::}
 * already meaning "context qualifier" and {@code .} free for a join hop, a {@code groupBy} field
 * string parses unambiguously:
 *
 * <pre>
 *   "warehouseId"                    -&gt; Direct("warehouseId")
 *   "lote.produtoId"                 -&gt; Join(null, ["lote"], "produtoId")
 *   "inventory::lote.produtoId"      -&gt; Join("inventory", ["lote"], "produtoId")
 *   "lote.produto.categoria"         -&gt; Join(null, ["lote", "produto"], "categoria")
 * </pre>
 *
 * {@code lote} is a declared {@code reference}-typed field on the query's own concept; each
 * subsequent {@code referenceFields} entry is itself a {@code reference}-typed field on the concept
 * the PREVIOUS hop targets; {@code targetField} is a plain field on the concept the LAST hop
 * targets. The optional leading {@code context::} names the context the FINAL joined concept (the
 * one {@code targetField} lives on, not any intermediate hop, not the base concept) belongs to -- a
 * disambiguating, author-checkable restatement of what that reference field's own target already
 * carries, verified for consistency by {@code PackValidation} rather than trusted blindly.
 *
 * <p><b>S8 W1.1 (roadmap deferred item #1): up to {@link #MAX_JOIN_HOPS} chained join hops.</b>
 * {@code "a.b"} is one hop, {@code "a.b.c"} is two, {@code "a.b.c.d"} is three (the cap) --
 * {@code "a.b.c.d.e"} (four hops) is refused, not best-effort-resolved. The cap exists because an
 * unbounded chain invites an accidental cartesian-product join; three hops covers realistic
 * reporting ("revenue by customer's region's country") without inviting one. X0's rule ("an input
 * the evaluator cannot handle is an error, never a default answer") applies here exactly as it does
 * to {@link QueryPredicateGrammar}: a join path this grammar cannot parse -- whether malformed or
 * simply too long -- is a compile error, never a silently-dropped or partially-applied
 * {@code groupBy} clause.
 */
public final class GroupByJoinGrammar {

    /** S8 W1.1: the maximum number of chained reference-field hops a {@code groupBy} join path may
     *  name. See the class javadoc for why 3, not unbounded. */
    public static final int MAX_JOIN_HOPS = 3;

    private GroupByJoinGrammar() {
    }

    public sealed interface Target {
        /** A plain field on the query's own concept -- the only shape that existed before S4. */
        record Direct(String field) implements Target {
        }

        /** A 1-to-{@link #MAX_JOIN_HOPS}-hop join: {@code referenceFields} is the chain of
         *  reference-typed fields walked in order -- {@code referenceFields.get(0)} on the query's
         *  own concept, {@code referenceFields.get(1)} on the concept hop 0 targets, and so on;
         *  {@code targetField} is a field on the concept the LAST reference field targets.
         *  {@code context}, when the author wrote one, names which context the FINAL joined concept
         *  belongs to -- null when omitted (same-context or unqualified join). */
        record Join(String context, List<String> referenceFields, String targetField) implements Target {
            public Join {
                if (referenceFields == null || referenceFields.isEmpty()) {
                    throw new IllegalArgumentException("a groupBy Join must name at least one reference field hop");
                }
                referenceFields = List.copyOf(referenceFields);
            }

            /** Convenience for the (still-common) single-hop case. */
            public String referenceField() {
                return referenceFields.get(0);
            }
        }
    }

    /** Thrown when a {@code groupBy} field string cannot be parsed. Never caught-and-ignored by
     *  design -- see the class javadoc's X0 note. */
    public static final class UnsupportedGroupByPathException extends RuntimeException {
        private final String field;

        public UnsupportedGroupByPathException(String field, String reason) {
            super("cannot parse groupBy field " + quote(field) + " -- " + reason
                    + ". Supported: a plain field name, a join of 1-" + MAX_JOIN_HOPS
                    + " hops ('referenceField.../targetField'), or a context-qualified join "
                    + "('context::referenceField.../targetField').");
            this.field = field;
        }

        public String field() {
            return field;
        }

        private static String quote(String value) {
            return value == null ? "<null>" : "\"" + value + "\"";
        }
    }

    /**
     * @throws UnsupportedGroupByPathException when {@code rawField} is blank, malformed, or asks
     *         for more join hops than {@link #MAX_JOIN_HOPS}
     */
    public static Target parse(String rawField) {
        if (rawField == null || rawField.isBlank()) {
            throw new UnsupportedGroupByPathException(rawField, "groupBy field must be non-blank");
        }
        String trimmed = rawField.trim();

        String context = null;
        String remainder = trimmed;
        int separator = trimmed.indexOf("::");
        if (separator >= 0) {
            if (trimmed.indexOf("::", separator + 2) >= 0) {
                throw new UnsupportedGroupByPathException(rawField, "more than one '::' -- a groupBy "
                        + "join path names at most one context qualifier");
            }
            context = trimmed.substring(0, separator);
            remainder = trimmed.substring(separator + 2);
            if (!isPlainName(context)) {
                throw new UnsupportedGroupByPathException(rawField,
                        "'" + context + "' (before '::') is not a plain context name");
            }
        }

        if (remainder.indexOf('.') < 0) {
            if (context != null) {
                throw new UnsupportedGroupByPathException(rawField,
                        "a context qualifier ('" + context + "::') requires a join hop "
                                + "('referenceField.targetField'), not a bare field");
            }
            if (!isPlainName(remainder)) {
                throw new UnsupportedGroupByPathException(rawField, "'" + remainder + "' is not a plain field name");
            }
            return new Target.Direct(remainder);
        }

        List<String> segments = new ArrayList<>();
        int start = 0;
        for (int index = 0; index <= remainder.length(); index++) {
            if (index == remainder.length() || remainder.charAt(index) == '.') {
                segments.add(remainder.substring(start, index));
                start = index + 1;
            }
        }

        int hopCount = segments.size() - 1;
        if (hopCount > MAX_JOIN_HOPS) {
            throw new UnsupportedGroupByPathException(rawField,
                    hopCount + " join hops exceeds the cap of " + MAX_JOIN_HOPS
                            + " -- an unbounded join chain invites an accidental cartesian-product join");
        }

        List<String> referenceFields = new ArrayList<>();
        for (int i = 0; i < segments.size() - 1; i++) {
            String referenceField = segments.get(i);
            if (!isPlainName(referenceField)) {
                throw new UnsupportedGroupByPathException(rawField,
                        "'" + referenceField + "' (before '.') is not a plain reference field name");
            }
            referenceFields.add(referenceField);
        }
        String targetField = segments.get(segments.size() - 1);
        if (!isPlainName(targetField)) {
            throw new UnsupportedGroupByPathException(rawField,
                    "'" + targetField + "' (after '.') is not a plain target field name");
        }
        return new Target.Join(context, referenceFields, targetField);
    }

    /** True for {@code [A-Za-z_][A-Za-z0-9_]*} -- same convention {@code QueryPredicateGrammar}
     *  uses for a bare field/context name. */
    private static boolean isPlainName(String name) {
        if (name == null || name.isEmpty() || (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_')) {
            return false;
        }
        for (int index = 1; index < name.length(); index++) {
            char current = name.charAt(index);
            if (!Character.isLetterOrDigit(current) && current != '_') {
                return false;
            }
        }
        return true;
    }
}
