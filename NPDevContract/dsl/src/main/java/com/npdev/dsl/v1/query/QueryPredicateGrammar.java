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

    // ============================================================================================
    // R4.3 (Roadmap Wave 1): PREDICATE GRAMMAR V2 -- OR-groups, IN, contains/startsWith, is-null,
    // and a reference-path left side, bounded at GroupByJoinGrammar.MAX_JOIN_HOPS hops.
    // ============================================================================================
    /*
     * Deliberately a SEPARATE entry point (parseGroups/PredicateOperator/PredicateLiteral/
     * PredicateClause below), not a change to parse()/Operator/Literal/Clause above -- not one
     * extra line of the v1 grammar's code is touched by this section.
     *
     * Why: parse() above is called by BOTH PackValidation.validateQueryWhereCompiles (authoring
     * time) and ConceptQueryPredicateCompiler.compile() (the SQL-pushdown path
     * DefaultProcedureExecutor's runQuery step uses, all the way down to a real JDBC WHERE clause
     * in JdbcBusinessConceptStore). That JDBC builder
     * (NPDevRuntimeHost/runtimehost-core/.../db/JdbcBusinessConceptStore.java) has an EXHAUSTIVE
     * switch over ConceptQuery.Operator with no `default` arm (its private sqlOperator(), ~line
     * 729) -- adding an operator ConceptQuery does not already have is a compile break there, and
     * ConceptQuery itself is constructed positionally by generated code and RuntimeHost/Generator
     * call sites this module does not own. Wiring OR/IN/contains/startsWith/is-null/reference-path
     * joins all the way to a live SQL WHERE clause needs changes in NPDevRuntimeHost (the WHERE/JOIN
     * builder, mirroring its own registerJoinChain's groupBy-join pattern for the FROM/JOIN side)
     * and possibly NPDevKernel/adapters/expression-cel -- both out of this change's owned surface.
     *
     * So parseGroups() below is the COMPLETE, tested grammar -- proven correct in isolation
     * (QueryPredicateGrammarTest) and reusing GroupByJoinGrammar's own hop-bounded join resolution
     * for a reference-path left side, exactly as the roadmap item asks. It is deliberately NOT wired
     * into PackValidation or ConceptQueryPredicateCompiler.compile() yet: doing so would let a model
     * author declare a queries[].where that validates cleanly at authoring time and then throws at
     * runtime the moment a runQuery step tries to push it to SQL -- the exact "validated but does
     * not run" trap parse()'s own javadoc (REG-101's fix, "one grammar, not two drifting copies")
     * was written to prevent. Wiring this in for real needs, in this order: (1) the JDBC WHERE/JOIN
     * builder understanding OR-groups, IN, is-null and reference-path joins; (2)
     * GeneratedCrudRuntimeSupport if generated CRUD also builds predicate SQL; (3) only then can
     * ConceptQueryPredicateCompiler.compile() / PackValidation.validateQueryWhereCompiles safely
     * accept it. See SqlDialect#containsPattern / #startsWithPattern / #inPlaceholders (NPDevKernel)
     * -- the escaping/binding primitives that step (1) needs are already built and tested
     * (DialectConformanceTierATest), specifically so that work does not start from zero.
     *
     * ConceptQueryPredicateCompiler#compilePredicate / ConceptQueryFilterSupport#applyPredicate
     * (NPDevKernel) DO evaluate this v2 grammar end-to-end, correctly, in memory -- proven by real
     * tests -- but that in-memory evaluator is not on any live request path in a generated app
     * today (production concepts run npdev.storage.mode=jdbc). See those classes' own javadoc.
     */

    /** field/path (== | != | > | >= | < | <= | contains | startsWith) literal
     *  | field/path "in" "(" literal ("," literal)* ")"
     *  | field/path "is" "null"
     *  | field/path "is" "not" "null" */
    public enum PredicateOperator {
        EQ("=="), NEQ("!="), GTE(">="), LTE("<="), GT(">"), LT("<"),
        /** Case-sensitive substring match, mirrors {@code ConceptQuery.Operator.CONTAINS}. */
        CONTAINS("contains"),
        STARTS_WITH("startsWith"),
        /** {@code field in (lit, lit, ...)} -- {@link PredicateLiteral.Values} carries the list. */
        IN("in"),
        IS_NULL("is null"),
        IS_NOT_NULL("is not null");

        private final String token;

        PredicateOperator(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }

        /** {@link #IS_NULL}/{@link #IS_NOT_NULL} take no literal on the right. */
        public boolean isUnary() {
            return this == IS_NULL || this == IS_NOT_NULL;
        }
    }

    public sealed interface PredicateLiteral {
        record Value(Object value) implements PredicateLiteral {
        }

        /** An unresolved {@code :name} bind placeholder -- resolution is the CALLER's job, same as v1. */
        record Placeholder(String name) implements PredicateLiteral {
        }

        /** The right-hand side of an {@link PredicateOperator#IN} clause. */
        record Values(List<PredicateLiteral> values) implements PredicateLiteral {
            public Values {
                if (values == null || values.isEmpty()) {
                    throw new IllegalArgumentException("an 'in' list must carry at least one value");
                }
                values = List.copyOf(values);
            }
        }

        /** The (non-)literal carried by {@link PredicateOperator#IS_NULL}/{@link PredicateOperator#IS_NOT_NULL}. */
        enum None implements PredicateLiteral {
            INSTANCE
        }
    }

    /**
     * One predicate clause. {@code path} is a {@link GroupByJoinGrammar.Target} -- a plain field
     * ({@code Direct}) or a 1-to-{@link GroupByJoinGrammar#MAX_JOIN_HOPS}-hop reference-path join
     * ({@code Join}), resolved by delegating to {@link GroupByJoinGrammar#parse} so a predicate's
     * left side is bounded by the exact same, already-proven cap {@code groupBy} join paths are.
     */
    public record PredicateClause(GroupByJoinGrammar.Target path, PredicateOperator operator, PredicateLiteral literal) {
    }

    /**
     * @return the OR-of-AND-combined clause groups for {@code where} (top-level {@code ||}
     *         separates groups; {@code &&} combines clauses within a group -- {@code &&} binds
     *         tighter, so no parentheses are needed for plain DNF); an empty list when
     *         {@code where} is null/blank
     * @throws UnsupportedPredicateException when any part of {@code where} is outside this
     *         grammar, including a reference path exceeding {@link GroupByJoinGrammar#MAX_JOIN_HOPS}
     *         hops (named by {@link GroupByJoinGrammar}'s own message, wrapped here rather than
     *         re-derived)
     */
    public static List<List<PredicateClause>> parseGroups(String where) {
        if (where == null || where.isBlank()) {
            return List.of();
        }
        String trimmed = where.trim();
        List<List<PredicateClause>> groups = new ArrayList<>();
        for (String orPart : splitOutsideQuotes(trimmed, "||")) {
            String orGroup = orPart.trim();
            if (orGroup.isEmpty()) {
                throw new UnsupportedPredicateException(where, null, "empty group between '||' operators");
            }
            List<PredicateClause> clauses = new ArrayList<>();
            for (String andPart : splitOutsideQuotes(orGroup, "&&")) {
                String candidate = andPart.trim();
                if (candidate.isEmpty()) {
                    throw new UnsupportedPredicateException(where, null, "empty clause between '&&' operators");
                }
                clauses.add(parsePredicateClause(where, candidate));
            }
            groups.add(List.copyOf(clauses));
        }
        if (groups.isEmpty()) {
            throw new UnsupportedPredicateException(where, null, "no clause found");
        }
        return List.copyOf(groups);
    }

    /** Same quote-aware split {@link #splitOnAnd} performs, generalised to any 2-char token
     *  ({@code "&&"} or {@code "||"}) so one method serves both grouping levels. */
    private static List<String> splitOutsideQuotes(String text, String token) {
        List<String> parts = new ArrayList<>();
        boolean inQuote = false;
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote && text.startsWith(token, index)) {
                parts.add(text.substring(start, index));
                index += token.length() - 1;
                start = index + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static PredicateClause parsePredicateClause(String where, String clauseText) {
        String isNotNullPath = suffixOutsideQuotes(clauseText, "is not null");
        if (isNotNullPath != null) {
            return buildUnaryClause(where, clauseText, isNotNullPath, PredicateOperator.IS_NOT_NULL);
        }
        String isNullPath = suffixOutsideQuotes(clauseText, "is null");
        if (isNullPath != null) {
            return buildUnaryClause(where, clauseText, isNullPath, PredicateOperator.IS_NULL);
        }

        int inAt = indexOfKeywordOutsideQuotes(clauseText, "in");
        if (inAt >= 0) {
            String pathText = clauseText.substring(0, inAt).trim();
            String rest = clauseText.substring(inAt + "in".length()).trim();
            if (!rest.startsWith("(") || !rest.endsWith(")")) {
                throw new UnsupportedPredicateException(where, clauseText,
                        "'in' must be followed by a parenthesized, comma-separated literal list: "
                                + "field in ('a', 'b')");
            }
            if (pathText.isEmpty()) {
                throw new UnsupportedPredicateException(where, clauseText, "no field/path before 'in'");
            }
            String inner = rest.substring(1, rest.length() - 1).trim();
            if (inner.isEmpty()) {
                throw new UnsupportedPredicateException(where, clauseText, "an 'in' list must name at least one value");
            }
            List<PredicateLiteral> values = new ArrayList<>();
            for (String rawValue : splitOutsideQuotesOnComma(inner)) {
                values.add(parsePredicateLiteral(where, clauseText, rawValue.trim()));
            }
            return new PredicateClause(pathTarget(where, clauseText, pathText), PredicateOperator.IN,
                    new PredicateLiteral.Values(values));
        }

        for (PredicateOperator keywordOp : List.of(PredicateOperator.CONTAINS, PredicateOperator.STARTS_WITH)) {
            int at = indexOfKeywordOutsideQuotes(clauseText, keywordOp.token());
            if (at < 0) {
                continue;
            }
            String pathText = clauseText.substring(0, at).trim();
            String literalText = clauseText.substring(at + keywordOp.token().length()).trim();
            if (pathText.isEmpty()) {
                throw new UnsupportedPredicateException(where, clauseText, "no field/path before '" + keywordOp.token() + "'");
            }
            if (literalText.isEmpty()) {
                throw new UnsupportedPredicateException(where, clauseText, "no literal after '" + keywordOp.token() + "'");
            }
            return new PredicateClause(pathTarget(where, clauseText, pathText), keywordOp,
                    parsePredicateLiteral(where, clauseText, literalText));
        }

        for (PredicateOperator operator : List.of(PredicateOperator.EQ, PredicateOperator.NEQ,
                PredicateOperator.GTE, PredicateOperator.LTE, PredicateOperator.GT, PredicateOperator.LT)) {
            int at = indexOfOperatorOutsideQuotes(clauseText, operator.token());
            if (at < 0) {
                continue;
            }
            String pathText = clauseText.substring(0, at).trim();
            String literalText = clauseText.substring(at + operator.token().length()).trim();
            if (pathText.isEmpty()) {
                throw new UnsupportedPredicateException(where, clauseText, "no field name before '" + operator.token() + "'");
            }
            if (literalText.isEmpty()) {
                throw new UnsupportedPredicateException(where, clauseText, "no literal after '" + operator.token() + "'");
            }
            return new PredicateClause(pathTarget(where, clauseText, pathText), operator,
                    parsePredicateLiteral(where, clauseText, literalText));
        }
        throw new UnsupportedPredicateException(where, clauseText, "no supported comparison/keyword operator found in this clause");
    }

    private static PredicateClause buildUnaryClause(
            String where, String clauseText, String pathText, PredicateOperator operator) {
        if (pathText.isEmpty()) {
            throw new UnsupportedPredicateException(where, clauseText, "no field/path before '" + operator.token() + "'");
        }
        return new PredicateClause(pathTarget(where, clauseText, pathText), operator, PredicateLiteral.None.INSTANCE);
    }

    /** Resolves a clause's left side through {@link GroupByJoinGrammar#parse} -- the SAME
     *  hop-bounded (max {@link GroupByJoinGrammar#MAX_JOIN_HOPS}) resolution {@code groupBy} join
     *  paths already use, reused rather than re-derived (this method's whole reason to exist). */
    private static GroupByJoinGrammar.Target pathTarget(String where, String clauseText, String pathText) {
        try {
            return GroupByJoinGrammar.parse(pathText);
        } catch (GroupByJoinGrammar.UnsupportedGroupByPathException hopFailure) {
            throw new UnsupportedPredicateException(where, clauseText,
                    "predicate path " + quoteForMessage(pathText) + " -- " + hopFailure.getMessage());
        }
    }

    /**
     * True when {@code clauseText} ends (outside a quoted literal) with {@code suffixKeyword}
     * preceded by whitespace; returns the trimmed text BEFORE that suffix, or null when it does
     * not match. Used for {@code "is null"}/{@code "is not null"}, which unlike every other
     * operator here have nothing on their right.
     */
    private static String suffixOutsideQuotes(String clauseText, String suffixKeyword) {
        String trimmed = clauseText.stripTrailing();
        if (trimmed.length() < suffixKeyword.length() + 1) {
            return null;
        }
        int start = trimmed.length() - suffixKeyword.length();
        if (!trimmed.regionMatches(start, suffixKeyword, 0, suffixKeyword.length())) {
            return null;
        }
        if (!Character.isWhitespace(trimmed.charAt(start - 1))) {
            return null;
        }
        if (isInsideQuoteAt(clauseText, start)) {
            return null;
        }
        return trimmed.substring(0, start).trim();
    }

    private static boolean isInsideQuoteAt(String text, int index) {
        boolean inQuote = false;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\'') {
                inQuote = !inQuote;
            }
        }
        return inQuote;
    }

    /** Finds {@code keyword} outside quotes, at a clean word boundary on both sides (so
     *  {@code "domain in (...)"} does not false-match the {@code "in"} inside {@code "domain"}). */
    private static int indexOfKeywordOutsideQuotes(String clause, String keyword) {
        boolean inQuote = false;
        for (int index = 0; index + keyword.length() <= clause.length(); index++) {
            char current = clause.charAt(index);
            if (current == '\'') {
                inQuote = !inQuote;
                continue;
            }
            if (inQuote) {
                continue;
            }
            if (clause.regionMatches(index, keyword, 0, keyword.length())) {
                boolean leftOk = index == 0 || !isIdentChar(clause.charAt(index - 1));
                int after = index + keyword.length();
                boolean rightOk = after == clause.length() || !isIdentChar(clause.charAt(after));
                if (leftOk && rightOk) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static boolean isIdentChar(char character) {
        return Character.isLetterOrDigit(character) || character == '_';
    }

    /** Same quote-aware split {@code splitOnAnd} performs, for comma-separated {@code in (...)} values. */
    private static List<String> splitOutsideQuotesOnComma(String text) {
        List<String> parts = new ArrayList<>();
        boolean inQuote = false;
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote && current == ',') {
                parts.add(text.substring(start, index));
                start = index + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    /** Same literal grammar {@link #parseLiteral} accepts, kept as a separate copy so the v1
     *  method above is never touched by this section (see this section's own header comment). */
    private static PredicateLiteral parsePredicateLiteral(String where, String clauseText, String text) {
        if (text.length() >= 2 && text.charAt(0) == '\'' && text.charAt(text.length() - 1) == '\'') {
            String inner = text.substring(1, text.length() - 1);
            if (inner.indexOf('\'') >= 0) {
                throw new UnsupportedPredicateException(where, clauseText,
                        "unbalanced quotes in literal " + text);
            }
            return new PredicateLiteral.Value(inner);
        }
        if ("true".equalsIgnoreCase(text)) {
            return new PredicateLiteral.Value(Boolean.TRUE);
        }
        if ("false".equalsIgnoreCase(text)) {
            return new PredicateLiteral.Value(Boolean.FALSE);
        }
        if (text.length() >= 2 && text.charAt(0) == ':' && isPlainName(text.substring(1))) {
            return new PredicateLiteral.Placeholder(text.substring(1));
        }
        try {
            if (text.indexOf('.') >= 0) {
                return new PredicateLiteral.Value(Double.parseDouble(text));
            }
            return new PredicateLiteral.Value(Long.parseLong(text));
        } catch (NumberFormatException ignored) {
            throw new UnsupportedPredicateException(where, clauseText,
                    "literal " + quoteForMessage(text) + " is neither a quoted string, a number, a boolean, "
                            + "nor a ':name' bind placeholder"
                            + (text.startsWith("$")
                            ? " -- a $-reference (context/parameter substitution) is not resolved here; "
                              + "substitute it before compiling the predicate"
                            : ""));
        }
    }
}
