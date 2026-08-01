package com.npdev.dsl.v1.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Move 12 P1.4 (item 2 / REG-101, fix shape (c)): the grammar half of
 * {@code ConceptQueryPredicateCompiler} (kernel), lifted into {@code NPDevContract/dsl} so it is
 * usable from BOTH sides of the dependency edge -- the kernel already depends on this module
 * ({@code NPDevKernel/kernel} includes {@code project(':dsl')}), but the DSL validator cannot depend
 * on the kernel, so a grammar that only existed in kernel code could never be reused for
 * authoring-time validation. {@code ConceptQueryPredicateCompiler.compile} now delegates here and
 * maps the result onto {@code ConceptQuery.Filter}/{@code Sort} (kernel-only types this class does
 * not know about); {@code PackValidation.validateQueries} calls this directly.
 *
 * <p>Same grammar {@code ConceptQueryPredicateCompiler} has always enforced --
 * "AND-combined single-field comparisons":
 *
 * <pre>
 *   where   := clause ( "&amp;&amp;" clause )*
 *   clause  := field op literal
 *   op      := "==" | "!=" | "&gt;=" | "&lt;=" | "&gt;" | "&lt;"
 *   literal := "'" text "'" | number | true | false | ":" name
 * </pre>
 *
 * <p><b>New in this move:</b> a literal spelled {@code :name} parses as a {@link Literal.Placeholder}
 * rather than failing -- REG-101's fix. Before this, {@code :storeId} fell into the same bucket as
 * any other unparseable literal and was refused outright, which is why the ledger item's own
 * corpus witness ({@code pack-sample}'s {@code SalesByStore}) needed an allowlist entry: the grammar
 * had no way to accept a bind placeholder even provisionally. A caller that has actual bound values
 * (the kernel, once REG-101's substitution half runs) resolves a {@code Placeholder} to a real value;
 * a caller that only has the model in hand (the DSL validator) checks the placeholder's name against
 * the query's own declared {@code parameters[]} -- see {@code PackValidation.validateQueries}.
 */
public final class QueryPredicateGrammar {

    private QueryPredicateGrammar() {
    }

    public enum Operator {
        EQ("=="), NEQ("!="), GTE(">="), LTE("<="), GT(">"), LT("<");

        private final String token;

        Operator(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    /** Operators longest-first, so {@code >=} is never mis-read as {@code >}. */
    private static final Operator[] OPERATORS_LONGEST_FIRST = {
            Operator.EQ, Operator.NEQ, Operator.GTE, Operator.LTE, Operator.GT, Operator.LT,
    };

    public sealed interface Literal {
        record Value(Object value) implements Literal {
        }

        /** An unresolved {@code :name} bind placeholder -- resolution is the CALLER's job. */
        record Placeholder(String name) implements Literal {
        }
    }

    public record Clause(String field, Operator operator, Literal literal) {
    }

    /** Thrown when a {@code where} cannot be parsed. Never caught-and-ignored by design. */
    public static final class UnsupportedPredicateException extends RuntimeException {
        private final String where;
        private final String clause;

        public UnsupportedPredicateException(String where, String clause, String reason) {
            super("cannot compile query predicate " + quote(where)
                    + (clause == null ? "" : " at clause " + quote(clause)) + " -- " + reason
                    + ". Supported: AND-combined single-field comparisons, "
                    + "field (== | != | > | >= | < | <=) literal, literal being 'text', a number, "
                    + "true, false, or a ':name' bind placeholder. A predicate that cannot be "
                    + "compiled is refused rather than applied partially: an unenforced filter "
                    + "returns rows the author asked to exclude, with no error anywhere.");
            this.where = where;
            this.clause = clause;
        }

        public String where() {
            return where;
        }

        public String clause() {
            return clause;
        }

        private static String quote(String value) {
            return value == null ? "<null>" : "\"" + value + "\"";
        }
    }

    /**
     * @return the AND-combined clauses for {@code where}; an empty list when {@code where} is
     *         null/blank (no predicate declared is not the same as a predicate that failed).
     * @throws UnsupportedPredicateException when any part of {@code where} is outside the grammar
     */
    public static List<Clause> parse(String where) {
        if (where == null || where.isBlank()) {
            return List.of();
        }
        String trimmed = where.trim();
        if (trimmed.contains("||")) {
            throw new UnsupportedPredicateException(where, null,
                    "'||' (OR) is not supported -- ConceptQuery filters are AND-combined by contract, "
                            + "and silently treating an OR as an AND would return the wrong rows");
        }
        List<Clause> clauses = new ArrayList<>();
        for (String raw : splitOnAnd(trimmed)) {
            String candidate = raw.trim();
            if (candidate.isEmpty()) {
                throw new UnsupportedPredicateException(where, null, "empty clause between '&&' operators");
            }
            clauses.add(parseClause(where, candidate));
        }
        if (clauses.isEmpty()) {
            throw new UnsupportedPredicateException(where, null, "no clause found");
        }
        return List.copyOf(clauses);
    }

    /**
     * Splits on {@code &&} that is NOT inside a quoted literal, so a literal containing the token
     * (e.g. {@code note == 'a && b'}) is not torn in half.
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

    private static Clause parseClause(String where, String clause) {
        for (Operator operator : OPERATORS_LONGEST_FIRST) {
            int at = indexOfOperatorOutsideQuotes(clause, operator.token());
            if (at < 0) {
                continue;
            }
            String field = clause.substring(0, at).trim();
            String literalText = clause.substring(at + operator.token().length()).trim();
            if (field.isEmpty()) {
                throw new UnsupportedPredicateException(where, clause, "no field name before '" + operator.token() + "'");
            }
            if (!isPlainName(field)) {
                throw new UnsupportedPredicateException(where, clause,
                        "'" + field + "' is not a plain field name -- nested paths, functions and "
                                + "expressions on the left of a comparison are not supported");
            }
            if (literalText.isEmpty()) {
                throw new UnsupportedPredicateException(where, clause, "no literal after '" + operator.token() + "'");
            }
            return new Clause(field, operator, parseLiteral(where, clause, literalText));
        }
        throw new UnsupportedPredicateException(where, clause, "no supported comparison operator found in this clause");
    }

    /** True for {@code [A-Za-z_][A-Za-z0-9_]*} -- deliberately no dots: a nested path is not compilable to SQL here. */
    private static boolean isPlainName(String name) {
        if (name.isEmpty() || (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_')) {
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

    private static Literal parseLiteral(String where, String clause, String text) {
        if (text.length() >= 2 && text.charAt(0) == '\'' && text.charAt(text.length() - 1) == '\'') {
            String inner = text.substring(1, text.length() - 1);
            if (inner.indexOf('\'') >= 0) {
                throw new UnsupportedPredicateException(where, clause,
                        "unbalanced quotes in literal " + text + " -- this is the shape that made the "
                                + "previous engine read an entire multi-clause expression as one literal");
            }
            return new Literal.Value(inner);
        }
        if ("true".equalsIgnoreCase(text)) {
            return new Literal.Value(Boolean.TRUE);
        }
        if ("false".equalsIgnoreCase(text)) {
            return new Literal.Value(Boolean.FALSE);
        }
        if (text.length() >= 2 && text.charAt(0) == ':' && isPlainName(text.substring(1))) {
            return new Literal.Placeholder(text.substring(1));
        }
        try {
            if (text.indexOf('.') >= 0) {
                return new Literal.Value(Double.parseDouble(text));
            }
            return new Literal.Value(Long.parseLong(text));
        } catch (NumberFormatException ignored) {
            throw new UnsupportedPredicateException(where, clause,
                    "literal " + quoteForMessage(text) + " is neither a quoted string, a number, a boolean, "
                            + "nor a ':name' bind placeholder"
                            + (text.startsWith("$")
                            ? " -- a $-reference (context/parameter substitution) is not resolved here; "
                              + "substitute it before compiling the predicate. A query bind placeholder "
                              + "uses this grammar's own convention instead: ':name'"
                            : ""));
        }
    }

    private static String quoteForMessage(String text) {
        return "\"" + text + "\"";
    }
}
