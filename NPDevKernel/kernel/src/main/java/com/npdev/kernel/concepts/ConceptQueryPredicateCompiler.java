package com.npdev.kernel.concepts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LC-P0 (MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 0.3): compiles a declared {@code queries[].where}
 * string into {@link ConceptQuery.Filter}s -- the filter tree the store already pushes to SQL --
 * and <b>refuses anything it cannot compile</b>.
 *
 * <p><b>The one rule this class exists to enforce:</b> a predicate the engine cannot compile is an
 * ERROR, never a default answer. Silently returning everything, nothing, or an inverted result is
 * the defect. See {@code docs/X0_SILENT_EXPRESSION_REGISTER.md}.
 *
 * <p><b>What it replaces.</b> {@link ConceptQueryFilterSupport}'s hand-rolled {@code indexOf("==")}
 * scan had three distinct silent failure modes, all reproduced in
 * {@code ConceptQueryFilterSupportRedTest} before this class existed:
 * <ol>
 *   <li>a comparison operator ({@code >} etc.) matched neither {@code indexOf}, so the predicate was
 *       dropped and <b>every row</b> came back -- the one mode its javadoc documented;</li>
 *   <li>an AND of two {@code ==} clauses split on the FIRST {@code ==}, making the literal the whole
 *       rest of the string, which no row could equal -- <b>zero rows</b>, the opposite of the
 *       documented mode and easily mistaken for "there is no data";</li>
 *   <li>a {@code !=} anywhere in the string won the operator race outright, producing a nonsense
 *       field name that no record has, so the negation branch kept <b>every row</b>.</li>
 * </ol>
 *
 * <p><b>Grammar.</b> Deliberately the one {@link ConceptQuery} already declares --
 * "AND-combined single-field comparisons" -- so this is a parser for an existing contract, not a new
 * dialect:
 *
 * <pre>
 *   where   := clause ( "&amp;&amp;" clause )*
 *   clause  := field op literal
 *   op      := "==" | "!=" | "&gt;=" | "&lt;=" | "&gt;" | "&lt;"
 *   literal := "'" text "'" | number | true | false
 * </pre>
 *
 * Longer operators are matched before their prefixes, so {@code >=} is never read as {@code >}.
 * {@code ||} is deliberately NOT supported: {@link ConceptQuery} is AND-combined by contract, and
 * accepting an OR that silently became an AND would be this class's own bug class. It is refused by
 * name, so an author is told rather than surprised.
 */
public final class ConceptQueryPredicateCompiler {

    private ConceptQueryPredicateCompiler() {
    }

    /** Thrown when a {@code where} cannot be compiled. Never caught-and-ignored by design. */
    public static final class UnsupportedPredicateException extends RuntimeException {
        private final String where;
        private final String clause;

        UnsupportedPredicateException(String where, String clause, String reason) {
            super("QUERY_PREDICATE_UNSUPPORTED: cannot compile query predicate " + quote(where)
                    + (clause == null ? "" : " at clause " + quote(clause)) + " -- " + reason
                    + ". Supported: AND-combined single-field comparisons, "
                    + "field (== | != | > | >= | < | <=) literal, literal being 'text', a number, "
                    + "true or false. A predicate that cannot be compiled is refused rather than "
                    + "applied partially: an unenforced filter returns rows the author asked to "
                    + "exclude, with no error anywhere (LC-P0).");
            this.where = where;
            this.clause = clause;
        }

        public String where() {
            return where;
        }

        /** The specific clause that could not be compiled, or null when the whole expression failed. */
        public String clause() {
            return clause;
        }

        private static String quote(String value) {
            return value == null ? "<null>" : "\"" + value + "\"";
        }
    }

    /** Operators longest-first, so {@code >=} is never mis-read as {@code >}. */
    private static final ConceptQuery.Operator[] OPERATORS_LONGEST_FIRST = {
            ConceptQuery.Operator.EQ, ConceptQuery.Operator.NEQ,
            ConceptQuery.Operator.GTE, ConceptQuery.Operator.LTE,
            ConceptQuery.Operator.GT, ConceptQuery.Operator.LT,
    };

    /**
     * @return the AND-combined filters for {@code where}; an empty list when {@code where} is
     *         null/blank (no predicate declared is not the same as a predicate that failed).
     * @throws UnsupportedPredicateException when any part of {@code where} is outside the grammar
     */
    public static List<ConceptQuery.Filter> compile(String where) {
        if (where == null || where.isBlank()) {
            return List.of();
        }
        String trimmed = where.trim();
        if (trimmed.contains("||")) {
            throw new UnsupportedPredicateException(where, null,
                    "'||' (OR) is not supported -- ConceptQuery filters are AND-combined by contract, "
                            + "and silently treating an OR as an AND would return the wrong rows");
        }
        List<ConceptQuery.Filter> filters = new ArrayList<>();
        for (String clause : splitOnAnd(trimmed)) {
            String candidate = clause.trim();
            if (candidate.isEmpty()) {
                throw new UnsupportedPredicateException(where, null, "empty clause between '&&' operators");
            }
            filters.add(compileClause(where, candidate));
        }
        if (filters.isEmpty()) {
            throw new UnsupportedPredicateException(where, null, "no clause found");
        }
        return List.copyOf(filters);
    }

    /**
     * Splits on {@code &&} that is NOT inside a quoted literal, so a literal containing the token
     * (e.g. {@code note == 'a && b'}) is not torn in half -- the same class of mistake the
     * first-{@code ==} scan this replaces made with quotes.
     */
    private static List<String> splitOnAnd(String where) {
        List<String> parts = new ArrayList<>();
        boolean inQuote = false;
        int start = 0;
        for (int index = 0; index < where.length(); index++) {
            char current = where.charAt(index);
            if (current == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote && current == '&' && index + 1 < where.length() && where.charAt(index + 1) == '&') {
                parts.add(where.substring(start, index));
                index++;
                start = index + 1;
            }
        }
        parts.add(where.substring(start));
        return parts;
    }

    private static ConceptQuery.Filter compileClause(String where, String clause) {
        for (ConceptQuery.Operator operator : OPERATORS_LONGEST_FIRST) {
            int at = indexOfOperatorOutsideQuotes(clause, operator.token());
            if (at < 0) {
                continue;
            }
            String field = clause.substring(0, at).trim();
            String literalText = clause.substring(at + operator.token().length()).trim();
            if (field.isEmpty()) {
                throw new UnsupportedPredicateException(where, clause, "no field name before '" + operator.token() + "'");
            }
            if (!isPlainFieldName(field)) {
                throw new UnsupportedPredicateException(where, clause,
                        "'" + field + "' is not a plain field name -- nested paths, functions and "
                                + "expressions on the left of a comparison are not supported");
            }
            if (literalText.isEmpty()) {
                throw new UnsupportedPredicateException(where, clause, "no literal after '" + operator.token() + "'");
            }
            return new ConceptQuery.Filter(field, operator, parseLiteral(where, clause, literalText));
        }
        throw new UnsupportedPredicateException(where, clause,
                "no supported comparison operator found in this clause");
    }

    /** True for {@code [A-Za-z_][A-Za-z0-9_]*} -- deliberately no dots: a nested path is not compilable to SQL here. */
    private static boolean isPlainFieldName(String field) {
        if (!Character.isLetter(field.charAt(0)) && field.charAt(0) != '_') {
            return false;
        }
        for (int index = 1; index < field.length(); index++) {
            char current = field.charAt(index);
            if (!Character.isLetterOrDigit(current) && current != '_') {
                return false;
            }
        }
        return true;
    }

    private static int indexOfOperatorOutsideQuotes(String clause, String token) {
        boolean inQuote = false;
        for (int index = 0; index + token.length() <= clause.length(); index++) {
            char current = clause.charAt(index);
            if (current == '\'') {
                inQuote = !inQuote;
                continue;
            }
            if (!inQuote && clause.startsWith(token, index)) {
                return index;
            }
        }
        return -1;
    }

    private static Object parseLiteral(String where, String clause, String text) {
        if (text.length() >= 2 && text.charAt(0) == '\'' && text.charAt(text.length() - 1) == '\'') {
            String inner = text.substring(1, text.length() - 1);
            if (inner.indexOf('\'') >= 0) {
                throw new UnsupportedPredicateException(where, clause,
                        "unbalanced quotes in literal " + text + " -- this is the shape that made the "
                                + "previous engine read an entire multi-clause expression as one literal");
            }
            return inner;
        }
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        try {
            if (text.indexOf('.') >= 0) {
                return Double.parseDouble(text);
            }
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            throw new UnsupportedPredicateException(where, clause,
                    "literal " + quoteForMessage(text) + " is neither a quoted string, a number, nor a boolean"
                            + (text.startsWith("$")
                            ? " -- a $-reference (context/parameter substitution) is not resolved here; "
                              + "substitute it before compiling the predicate"
                            : ""));
        }
    }

    private static String quoteForMessage(String text) {
        return "\"" + text + "\"";
    }

    /** Lower-cased operator token, for adapters that key off it. */
    public static String token(ConceptQuery.Operator operator) {
        return operator.token().toLowerCase(Locale.ROOT);
    }

    /**
     * MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 0 (LC-P0 scale half): compiles a declared
     * {@code orderBy} (list of field names, each optionally suffixed {@code " desc"}/{@code " asc"},
     * default ascending -- the same shape {@link ConceptQueryFilterSupport#applyOrderBy} parses for
     * in-memory evaluation) into {@link ConceptQuery.Sort}s, so a pushdown caller can hand the store
     * real {@code ORDER BY} instead of sorting a fully materialized list in the JVM. One grammar: this
     * is the sole place that spec is parsed; {@code applyOrderBy} delegates here rather than keeping
     * its own copy.
     */
    public static List<ConceptQuery.Sort> compileOrderBy(List<String> orderBy) {
        if (orderBy == null || orderBy.isEmpty()) {
            return List.of();
        }
        List<ConceptQuery.Sort> sorts = new ArrayList<>();
        for (String spec : orderBy) {
            if (spec == null || spec.isBlank()) {
                continue;
            }
            String trimmed = spec.trim();
            boolean descending = false;
            String field = trimmed;
            int spaceIndex = trimmed.lastIndexOf(' ');
            if (spaceIndex > 0) {
                String direction = trimmed.substring(spaceIndex + 1).trim();
                if ("desc".equalsIgnoreCase(direction) || "descending".equalsIgnoreCase(direction)) {
                    descending = true;
                    field = trimmed.substring(0, spaceIndex).trim();
                } else if ("asc".equalsIgnoreCase(direction) || "ascending".equalsIgnoreCase(direction)) {
                    field = trimmed.substring(0, spaceIndex).trim();
                }
            }
            if (!field.isEmpty()) {
                sorts.add(new ConceptQuery.Sort(field, descending));
            }
        }
        return List.copyOf(sorts);
    }
}
