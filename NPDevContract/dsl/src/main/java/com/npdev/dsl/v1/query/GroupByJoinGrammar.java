package com.npdev.dsl.v1.query;

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
 *   "lote.produtoId"                 -&gt; Join(null, "lote", "produtoId")
 *   "inventory::lote.produtoId"      -&gt; Join("inventory", "lote", "produtoId")
 * </pre>
 *
 * {@code lote} is a declared {@code reference}-typed field on the query's own concept; {@code
 * produtoId} is a field on {@code lote}'s reference target. The optional leading {@code context::}
 * names the context the JOINED concept (not the field, not the base concept) belongs to -- a
 * disambiguating, author-checkable restatement of what the reference field's own target already
 * carries, verified for consistency by {@code PackValidation} rather than trusted blindly.
 *
 * <p><b>v1 supports exactly one join hop.</b> {@code "a.b.c"} (two dots) is refused, not
 * best-effort-resolved -- X0's rule ("an input the evaluator cannot handle is an error, never a
 * default answer") applies here exactly as it does to {@link QueryPredicateGrammar}: a join path
 * this grammar cannot parse is a compile error, never a silently-dropped or partially-applied
 * {@code groupBy} clause.
 */
public final class GroupByJoinGrammar {

    private GroupByJoinGrammar() {
    }

    public sealed interface Target {
        /** A plain field on the query's own concept -- the only shape that existed before S4. */
        record Direct(String field) implements Target {
        }

        /** A one-hop join: {@code referenceField} is a reference-typed field on the query's own
         *  concept; {@code targetField} is a field on the concept that reference points at.
         *  {@code context}, when the author wrote one, names which context the TARGET concept
         *  belongs to -- null when omitted (same-context or unqualified join). */
        record Join(String context, String referenceField, String targetField) implements Target {
        }
    }

    /** Thrown when a {@code groupBy} field string cannot be parsed. Never caught-and-ignored by
     *  design -- see the class javadoc's X0 note. */
    public static final class UnsupportedGroupByPathException extends RuntimeException {
        private final String field;

        public UnsupportedGroupByPathException(String field, String reason) {
            super("cannot parse groupBy field " + quote(field) + " -- " + reason
                    + ". Supported: a plain field name, a one-hop join 'referenceField.targetField', "
                    + "or a context-qualified one-hop join 'context::referenceField.targetField'.");
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
     *         for more than one join hop
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

        int dot = remainder.indexOf('.');
        if (dot < 0) {
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

        if (remainder.indexOf('.', dot + 1) >= 0) {
            throw new UnsupportedGroupByPathException(rawField,
                    "more than one join hop ('a.b.c') -- v1 supports exactly one 'referenceField.targetField' hop");
        }
        String referenceField = remainder.substring(0, dot);
        String targetField = remainder.substring(dot + 1);
        if (!isPlainName(referenceField)) {
            throw new UnsupportedGroupByPathException(rawField,
                    "'" + referenceField + "' (before '.') is not a plain reference field name");
        }
        if (!isPlainName(targetField)) {
            throw new UnsupportedGroupByPathException(rawField,
                    "'" + targetField + "' (after '.') is not a plain target field name");
        }
        return new Target.Join(context, referenceField, targetField);
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
