package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.compiled.CompiledProcedureParameter;
import com.npdev.dsl.v1.query.QueryPredicateGrammar;
import com.npdev.dsl.v1.query.QueryPredicateGrammar.Clause;
import com.npdev.dsl.v1.query.QueryPredicateGrammar.Literal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * LC-P0 (MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 0.3): compiles a declared {@code queries[].where}
 * string into {@link ConceptQuery.Filter}s -- the filter tree the store already pushes to SQL --
 * and <b>refuses anything it cannot compile</b>.
 *
 * <p><b>The one rule this class exists to enforce:</b> a predicate the engine cannot compile is an
 * ERROR, never a default answer. Silently returning everything, nothing, or an inverted result is
 * the defect. See {@code docs/X0_SILENT_EXPRESSION_REGISTER.md}.
 *
 * <p>Move 12 P1.4 (item 2 / REG-101, fix shape (c)): the GRAMMAR (tokenizing {@code where} into
 * field/operator/literal clauses) now lives in {@link QueryPredicateGrammar}
 * ({@code NPDevContract/dsl}), so the DSL validator can reuse it at authoring time
 * ({@code PackValidation.validateQueries}) without depending on the kernel. This class is the
 * kernel-side consumer: it maps the shared grammar's clauses onto {@link ConceptQuery.Filter}, and
 * -- new in this move -- resolves a {@code :name} bind placeholder against a query's declared
 * {@code parameters[]} and a caller-supplied value map (fix shape (b)). REG-101's own corpus
 * witness, {@code pack-sample}'s {@code SalesByStore} ({@code where: "storeId == :storeId"}), is the
 * proof: before this move nothing substituted {@code :storeId}, so it compared every row's
 * {@code storeId} against the seven-character string {@code ":storeId"} and returned zero rows for
 * its whole life with no error anywhere.
 */
public final class ConceptQueryPredicateCompiler {

    private ConceptQueryPredicateCompiler() {
    }

    /** Thrown when a {@code where} cannot be compiled. Never caught-and-ignored by design. */
    public static final class UnsupportedPredicateException extends RuntimeException {
        private final String where;
        private final String clause;

        /** Wraps a grammar-level parse failure verbatim -- its message already names the clause and quotes {@code where}. */
        UnsupportedPredicateException(QueryPredicateGrammar.UnsupportedPredicateException grammarFailure) {
            super("QUERY_PREDICATE_UNSUPPORTED: " + grammarFailure.getMessage());
            this.where = grammarFailure.where();
            this.clause = grammarFailure.clause();
        }

        /** For parameter-binding failures (fix shape (b)), which have no grammar-level cause to wrap. */
        UnsupportedPredicateException(String where, String clause, String reason) {
            super("QUERY_PREDICATE_UNSUPPORTED: cannot compile query predicate " + quote(where)
                    + (clause == null ? "" : " at clause " + quote(clause)) + " -- " + reason);
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

    /**
     * @return the AND-combined filters for {@code where}; an empty list when {@code where} is
     *         null/blank (no predicate declared is not the same as a predicate that failed).
     * @throws UnsupportedPredicateException when any part of {@code where} is outside the grammar,
     *         or names a {@code :name} bind placeholder (this overload has no bound values to
     *         substitute -- use {@link #compile(String, List, Map)} when the caller has them)
     */
    public static List<ConceptQuery.Filter> compile(String where) {
        return compile(where, List.of(), Map.of());
    }

    /**
     * Move 12 P1.4 (REG-101 fix shape (b)): the SAME grammar, plus substitution -- a {@code :name}
     * literal is resolved against {@code parameters} (the query's own declared {@code parameters[]},
     * for the "is this even a real parameter" check) and {@code boundParameters} (the caller-supplied
     * values). Per X0's rule, an unbound parameter is refused by name, never defaulted to
     * {@code null} or dropped from the filter.
     *
     * @throws UnsupportedPredicateException when {@code where} is outside the grammar, names a
     *         {@code :name} placeholder that is not declared in {@code parameters}, or names a
     *         declared parameter that {@code boundParameters} does not carry a value for
     */
    public static List<ConceptQuery.Filter> compile(
            String where, List<CompiledProcedureParameter> parameters, Map<String, Object> boundParameters) {
        List<Clause> clauses;
        try {
            clauses = QueryPredicateGrammar.parse(where);
        } catch (QueryPredicateGrammar.UnsupportedPredicateException grammarFailure) {
            throw new UnsupportedPredicateException(grammarFailure);
        }
        List<ConceptQuery.Filter> filters = new ArrayList<>();
        for (Clause clause : clauses) {
            Object literal = resolveLiteral(where, clause, parameters, boundParameters);
            filters.add(new ConceptQuery.Filter(clause.field(), mapOperator(clause.operator()), literal));
        }
        return List.copyOf(filters);
    }

    private static Object resolveLiteral(
            String where, Clause clause, List<CompiledProcedureParameter> parameters, Map<String, Object> boundParameters) {
        if (clause.literal() instanceof Literal.Value value) {
            return value.value();
        }
        String name = ((Literal.Placeholder) clause.literal()).name();
        boolean declared = parameters.stream()
                .anyMatch(parameter -> parameter.name() != null && parameter.name().equalsIgnoreCase(name));
        if (!declared) {
            throw new UnsupportedPredicateException(where, clause.field() + " " + clause.operator().token() + " :" + name,
                    "':" + name + "' is not declared in this query's parameters[] -- a bind placeholder "
                            + "must name a real declared parameter, not a typo or a procedure-state ref");
        }
        if (!boundParameters.containsKey(name)) {
            throw new UnsupportedPredicateException(where, clause.field() + " " + clause.operator().token() + " :" + name,
                    "parameter ':" + name + "' is declared but was not supplied a value for this query "
                            + "invocation -- an unbound parameter is refused rather than defaulted to null "
                            + "or dropped from the filter (X0)");
        }
        return boundParameters.get(name);
    }

    private static ConceptQuery.Operator mapOperator(QueryPredicateGrammar.Operator operator) {
        return switch (operator) {
            case EQ -> ConceptQuery.Operator.EQ;
            case NEQ -> ConceptQuery.Operator.NEQ;
            case GTE -> ConceptQuery.Operator.GTE;
            case LTE -> ConceptQuery.Operator.LTE;
            case GT -> ConceptQuery.Operator.GT;
            case LT -> ConceptQuery.Operator.LT;
        };
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
