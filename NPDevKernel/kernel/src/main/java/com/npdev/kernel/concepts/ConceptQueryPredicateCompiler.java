package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.compiled.CompiledProcedureParameter;
import com.npdev.dsl.v1.query.GroupByJoinGrammar;
import com.npdev.dsl.v1.query.QueryPredicateGrammar;
import com.npdev.dsl.v1.query.QueryPredicateGrammar.Clause;
import com.npdev.dsl.v1.query.QueryPredicateGrammar.Literal;
import com.npdev.dsl.v1.query.QueryPredicateGrammar.PredicateClause;
import com.npdev.dsl.v1.query.QueryPredicateGrammar.PredicateLiteral;
import com.npdev.dsl.v1.query.QueryPredicateGrammar.PredicateOperator;

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

    // ============================================================================================
    // R4.3 (Roadmap Wave 1): the kernel-side compiler for QueryPredicateGrammar's v2 grammar
    // (OR-groups, IN, contains/startsWith, is-null, reference paths). See
    // QueryPredicateGrammar's own "PREDICATE GRAMMAR V2" section header for why this is a SEPARATE
    // entry point from compile()/mapOperator() above (untouched by this section) rather than a
    // change to them: compile() above stays the exact SQL-pushdown-safe subset it always was,
    // because ConceptQuery.Operator/ConceptQuery itself are consumed by an exhaustive switch and
    // positional constructors this module does not own (NPDevRuntimeHost's JdbcBusinessConceptStore
    // in particular). compilePredicate() below feeds ConceptQueryFilterSupport#applyPredicate, the
    // in-memory v2 evaluator -- not (yet) reachable from any live request path in a generated app.
    // ============================================================================================

    /**
     * One resolved v2 predicate clause: {@code path} identifies the (possibly joined) field exactly
     * as {@link QueryPredicateGrammar.PredicateClause#path()} did, and {@code value} is the
     * already-substituted right-hand side -- a scalar for every operator except
     * {@link PredicateOperator#IN} (a {@code List<Object>}) and
     * {@link PredicateOperator#IS_NULL}/{@link PredicateOperator#IS_NOT_NULL} ({@code null}, unused).
     */
    public record ResolvedClause(GroupByJoinGrammar.Target path, PredicateOperator operator, Object value) {
    }

    /**
     * @return the OR-of-AND {@link ResolvedClause} groups for {@code where}; an empty list when
     *         {@code where} is null/blank
     * @throws UnsupportedPredicateException when any part of {@code where} is outside the v2
     *         grammar, or names a {@code :name} bind placeholder (this overload has no bound values)
     */
    public static List<List<ResolvedClause>> compilePredicate(String where) {
        return compilePredicate(where, List.of(), Map.of());
    }

    /**
     * The v2 sibling of {@link #compile(String, List, Map)}: same placeholder-substitution contract
     * (a {@code :name} literal must name a declared parameter with a supplied value, or this throws
     * -- X0's "refuse rather than default" rule, unchanged from v1), extended to resolve every value
     * inside an {@link PredicateOperator#IN} list the same way.
     *
     * @throws UnsupportedPredicateException when {@code where} is outside the v2 grammar, or names
     *         an undeclared/unbound {@code :name} placeholder (including inside an {@code in (...)} list)
     */
    public static List<List<ResolvedClause>> compilePredicate(
            String where, List<CompiledProcedureParameter> parameters, Map<String, Object> boundParameters) {
        List<List<PredicateClause>> groups;
        try {
            groups = QueryPredicateGrammar.parseGroups(where);
        } catch (QueryPredicateGrammar.UnsupportedPredicateException grammarFailure) {
            throw new UnsupportedPredicateException(grammarFailure);
        }
        List<List<ResolvedClause>> resolvedGroups = new ArrayList<>();
        for (List<PredicateClause> group : groups) {
            List<ResolvedClause> resolvedClauses = new ArrayList<>();
            for (PredicateClause clause : group) {
                resolvedClauses.add(resolvePredicateClause(where, clause, parameters, boundParameters));
            }
            resolvedGroups.add(List.copyOf(resolvedClauses));
        }
        return List.copyOf(resolvedGroups);
    }

    private static ResolvedClause resolvePredicateClause(
            String where, PredicateClause clause,
            List<CompiledProcedureParameter> parameters, Map<String, Object> boundParameters) {
        if (clause.operator().isUnary()) {
            return new ResolvedClause(clause.path(), clause.operator(), null);
        }
        Object value;
        if (clause.literal() instanceof PredicateLiteral.Values values) {
            List<Object> resolvedValues = new ArrayList<>();
            for (PredicateLiteral element : values.values()) {
                resolvedValues.add(resolvePredicateLiteral(where, clause, element, parameters, boundParameters));
            }
            value = List.copyOf(resolvedValues);
        } else {
            value = resolvePredicateLiteral(where, clause, clause.literal(), parameters, boundParameters);
        }
        return new ResolvedClause(clause.path(), clause.operator(), value);
    }

    private static Object resolvePredicateLiteral(
            String where, PredicateClause clause, PredicateLiteral literal,
            List<CompiledProcedureParameter> parameters, Map<String, Object> boundParameters) {
        if (literal instanceof PredicateLiteral.Value value) {
            return value.value();
        }
        String name = ((PredicateLiteral.Placeholder) literal).name();
        String describedClause = predicatePathText(clause.path()) + " " + clause.operator().token() + " :" + name;
        boolean declared = parameters.stream()
                .anyMatch(parameter -> parameter.name() != null && parameter.name().equalsIgnoreCase(name));
        if (!declared) {
            throw new UnsupportedPredicateException(where, describedClause,
                    "':" + name + "' is not declared in this query's parameters[] -- a bind placeholder "
                            + "must name a real declared parameter, not a typo or a procedure-state ref");
        }
        if (!boundParameters.containsKey(name)) {
            throw new UnsupportedPredicateException(where, describedClause,
                    "parameter ':" + name + "' is declared but was not supplied a value for this query "
                            + "invocation -- an unbound parameter is refused rather than defaulted to null "
                            + "or dropped from the filter (X0)");
        }
        return boundParameters.get(name);
    }

    // ============================================================================================
    // R4.3 (Roadmap Wave 1): the bridge from compilePredicate's OR-of-AND ResolvedClause groups to
    // the ConceptQuery.Filter shape a store (JdbcBusinessConceptStore in particular) actually
    // executes. This is what makes the v2 grammar reachable as REAL SQL rather than only the
    // in-memory evaluator ConceptQueryFilterSupport#applyPredicate already had -- see
    // ConceptQuery.Operator#OR_GROUPS's own javadoc for why the bridge target is a marker Filter
    // rather than a wider ConceptQuery record shape (that shape is constructed positionally by
    // generated code this module does not own, so its arity cannot change).
    // ============================================================================================

    /**
     * @return the v2-compiled {@code where} as {@link ConceptQuery.Filter}s ready to hand a store:
     *         a flat AND-list (identical shape {@link #compile} has always produced) when there is
     *         exactly one OR-group, or a single {@link ConceptQuery.Operator#OR_GROUPS} marker
     *         filter when there is more than one; an empty list when {@code where} is null/blank
     * @throws UnsupportedPredicateException when {@code where} is outside the v2 grammar, or names
     *         a {@code :name} bind placeholder (this overload has no bound values to substitute)
     */
    public static List<ConceptQuery.Filter> compileToConceptQueryFilters(String where) {
        return compileToConceptQueryFilters(where, List.of(), Map.of());
    }

    /**
     * The v2-to-{@link ConceptQuery.Filter} sibling of {@link #compileToConceptQueryFilters(String)},
     * with the same placeholder-substitution contract {@link #compilePredicate(String, List, Map)}
     * already has.
     *
     * @throws UnsupportedPredicateException when {@code where} is outside the v2 grammar, or names
     *         an undeclared/unbound {@code :name} placeholder (including inside an {@code in (...)} list)
     */
    public static List<ConceptQuery.Filter> compileToConceptQueryFilters(
            String where, List<CompiledProcedureParameter> parameters, Map<String, Object> boundParameters) {
        List<List<ResolvedClause>> groups = compilePredicate(where, parameters, boundParameters);
        if (groups.isEmpty()) {
            return List.of();
        }
        if (groups.size() == 1) {
            return toConceptQueryFilterGroup(groups.get(0));
        }
        List<List<ConceptQuery.Filter>> converted = new ArrayList<>();
        for (List<ResolvedClause> group : groups) {
            converted.add(toConceptQueryFilterGroup(group));
        }
        return List.of(ConceptQuery.Filter.orGroups(converted));
    }

    private static List<ConceptQuery.Filter> toConceptQueryFilterGroup(List<ResolvedClause> group) {
        List<ConceptQuery.Filter> filters = new ArrayList<>();
        for (ResolvedClause clause : group) {
            filters.add(new ConceptQuery.Filter(
                    predicatePathText(clause.path()), mapPredicateOperator(clause.operator()), clause.value()));
        }
        return List.copyOf(filters);
    }

    private static ConceptQuery.Operator mapPredicateOperator(PredicateOperator operator) {
        return switch (operator) {
            case EQ -> ConceptQuery.Operator.EQ;
            case NEQ -> ConceptQuery.Operator.NEQ;
            case GTE -> ConceptQuery.Operator.GTE;
            case LTE -> ConceptQuery.Operator.LTE;
            case GT -> ConceptQuery.Operator.GT;
            case LT -> ConceptQuery.Operator.LT;
            case CONTAINS -> ConceptQuery.Operator.CONTAINS;
            case STARTS_WITH -> ConceptQuery.Operator.STARTS_WITH;
            case IN -> ConceptQuery.Operator.IN;
            case IS_NULL -> ConceptQuery.Operator.IS_NULL;
            case IS_NOT_NULL -> ConceptQuery.Operator.IS_NOT_NULL;
        };
    }

    /** Renders a {@link GroupByJoinGrammar.Target} back to the dotted-path text an author wrote --
     *  used both for error messages and (R4.3) as the {@link ConceptQuery.Filter#field()} text a
     *  store re-parses via {@code GroupByJoinGrammar.parse} to resolve a reference-path join. */
    private static String predicatePathText(GroupByJoinGrammar.Target path) {
        if (path instanceof GroupByJoinGrammar.Target.Direct direct) {
            return direct.field();
        }
        GroupByJoinGrammar.Target.Join join = (GroupByJoinGrammar.Target.Join) path;
        String prefix = join.context() == null ? "" : join.context() + "::";
        return prefix + String.join(".", join.referenceFields()) + "." + join.targetField();
    }
}
