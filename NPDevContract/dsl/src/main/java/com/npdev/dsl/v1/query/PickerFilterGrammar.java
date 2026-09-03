package com.npdev.dsl.v1.query;

import java.util.ArrayList;
import java.util.List;

/**
 * BOUNDARY_LIFT_PLAN_2026-09-02.md Wave 4 package 4.3 (B16) Step 1: the shared grammar behind
 * {@code field.picker.filter} / {@code transaction.bandPickers.<name>.filter} -- previously two
 * hand-copied, single-clause-only parsers ({@code AutoPanelExpander.parseBandPickerFilterExpression}
 * and {@code BusinessUiEmitter.parsePickerFilterExpression}), each stopping at the first {@code ==}/
 * {@code !=} and glomming everything after it into the literal. This class is the ONE place the
 * syntax is parsed; both callers still do their own field-existence checking afterward, because they
 * work against two different concept representations ({@code ConceptAst} in the dsl module,
 * {@code CompiledConcept} in the generator) that this module cannot unify without the dsl module
 * depending on the generator.
 *
 * <p>Grammar: {@code clause ("&&" clause)*}, {@code clause := field ("=="|"!=") literal}, {@code
 * literal := "'" text "'" | bareText | "$root." field}. Deliberately NOT the same {@code Literal}
 * shape {@link QueryPredicateGrammar} uses -- that type is pattern-matched exhaustively by production
 * code ({@code ConceptQueryPredicateCompiler.resolveLiteral}) that assumes exactly two variants
 * ({@code Value}/{@code Placeholder}); adding a third here for {@code $root.field} would risk a
 * silent {@code ClassCastException} at a call site this feature has no reason to touch.
 *
 * <p>{@code ||} (OR) is refused outright: the real server-side enforcement of this filter
 * ({@code business-concept-crud-controller.mustache}'s {@code parseWhereClauses}) only understands
 * comma/semicolon-separated AND clauses today, so an OR here would validate at authoring time and
 * then either fail or (worse) be silently misinterpreted at the HTTP boundary. An author who needs
 * OR logic wants a {@code visibleWhen} predicate instead, which is 100% client-side and already
 * supports it.
 *
 * <p>Unlike {@link QueryPredicateGrammar}, literal values here are never number/boolean-typed --
 * that matches the two ad hoc parsers this replaces, which always treated the right-hand side as a
 * string, and changing that now would be a behavior change nothing asked for.
 */
public final class PickerFilterGrammar {

    private PickerFilterGrammar() {
    }

    public enum Operator {
        EQ("=="), NEQ("!=");

        private final String token;

        Operator(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    public sealed interface Literal {
        record Value(String value) implements Literal {
        }

        /** An unresolved {@code $root.<field>} reference -- resolution against the aggregate's
         *  root record (or, when there is no enclosing aggregate, the filtered field's own record)
         *  is the CALLER's job, done client-side at request-build time. */
        record RootReference(String field) implements Literal {
        }
    }

    public record Clause(String field, Operator operator, Literal literal) {
    }

    /** Thrown when an {@code expression} cannot be parsed. Every current caller catches this and
     *  treats it as "no filter" -- matching this grammar's own predecessors' precedent that a bad
     *  filter drops the filter rather than failing compilation -- except the NEW authoring-time
     *  {@code $root.<field>} existence check in {@code PanelValidation}, which surfaces a real error
     *  once parsing succeeds but the referenced field does not exist. */
    public static final class UnsupportedFilterException extends RuntimeException {
        private final String expression;

        public UnsupportedFilterException(String expression, String reason) {
            super("cannot parse picker filter " + quote(expression) + " -- " + reason);
            this.expression = expression;
        }

        public String expression() {
            return expression;
        }

        private static String quote(String value) {
            return value == null ? "<null>" : "\"" + value + "\"";
        }
    }

    /**
     * @return the AND-combined clauses for {@code expression}; an empty list when {@code
     *         expression} is null/blank.
     * @throws UnsupportedFilterException when any part of {@code expression} is outside the grammar
     */
    public static List<Clause> parse(String expression) {
        if (expression == null || expression.isBlank()) {
            return List.of();
        }
        String trimmed = expression.trim();
        if (trimmed.contains("||")) {
            throw new UnsupportedFilterException(expression,
                    "'||' (OR) is not supported here -- picker/band-picker filters are AND-combined "
                            + "clauses matching the server's comma-separated where= grammar; use a "
                            + "visibleWhen predicate instead for OR logic");
        }
        List<Clause> clauses = new ArrayList<>();
        for (String raw : splitOnAnd(trimmed)) {
            String candidate = raw.trim();
            if (candidate.isEmpty()) {
                throw new UnsupportedFilterException(expression, "empty clause between '&&' operators");
            }
            clauses.add(parseClause(expression, candidate));
        }
        if (clauses.isEmpty()) {
            throw new UnsupportedFilterException(expression, "no clause found");
        }
        return List.copyOf(clauses);
    }

    /** Same quote-aware {@code &&} split {@link QueryPredicateGrammar}'s own {@code splitOnAnd}
     *  performs, kept as a separate copy since that one is private to its own class. */
    private static List<String> splitOnAnd(String expression) {
        List<String> parts = new ArrayList<>();
        boolean inQuote = false;
        int start = 0;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (current == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote && current == '&' && index + 1 < expression.length() && expression.charAt(index + 1) == '&') {
                parts.add(expression.substring(start, index));
                index++;
                start = index + 1;
            }
        }
        parts.add(expression.substring(start));
        return parts;
    }

    private static Clause parseClause(String expression, String clause) {
        int notEqualsIndex = clause.indexOf("!=");
        int equalsIndex = clause.indexOf("==");
        String field;
        Operator operator;
        String rawLiteral;
        if (notEqualsIndex >= 0 && (equalsIndex < 0 || notEqualsIndex < equalsIndex)) {
            field = clause.substring(0, notEqualsIndex).trim();
            operator = Operator.NEQ;
            rawLiteral = clause.substring(notEqualsIndex + 2).trim();
        } else if (equalsIndex >= 0) {
            field = clause.substring(0, equalsIndex).trim();
            operator = Operator.EQ;
            rawLiteral = clause.substring(equalsIndex + 2).trim();
        } else {
            throw new UnsupportedFilterException(expression,
                    "no supported comparison operator ('==' or '!=') found in clause \"" + clause + "\"");
        }
        if (field.startsWith("$row.")) {
            field = field.substring("$row.".length());
        }
        if (field.isEmpty()) {
            throw new UnsupportedFilterException(expression,
                    "no field name before the comparison operator in clause \"" + clause + "\"");
        }
        if (rawLiteral.isEmpty()) {
            throw new UnsupportedFilterException(expression,
                    "no literal after the comparison operator in clause \"" + clause + "\"");
        }
        return new Clause(field, operator, parseLiteral(expression, clause, rawLiteral));
    }

    private static Literal parseLiteral(String expression, String clause, String text) {
        if (text.startsWith("$root.")) {
            String rootField = text.substring("$root.".length()).trim();
            if (rootField.isEmpty()) {
                throw new UnsupportedFilterException(expression,
                        "no field name after '$root.' in clause \"" + clause + "\"");
            }
            return new Literal.RootReference(rootField);
        }
        if (text.length() >= 2 && text.charAt(0) == '\'' && text.charAt(text.length() - 1) == '\'') {
            return new Literal.Value(text.substring(1, text.length() - 1));
        }
        return new Literal.Value(text);
    }
}
