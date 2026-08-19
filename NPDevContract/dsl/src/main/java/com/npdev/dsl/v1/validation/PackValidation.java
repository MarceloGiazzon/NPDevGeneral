package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.CapabilityAst;
import com.npdev.dsl.v1.ast.ConceptAccessAst;
import com.npdev.dsl.v1.ast.CapabilityBindingAst;
import com.npdev.dsl.v1.ast.CapabilityOperationAst;
import com.npdev.dsl.v1.ast.DomainTypeAst;
import com.npdev.dsl.v1.ast.CapabilityPolicyAst;
import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.EventAst;
import com.npdev.dsl.v1.ast.EventPayloadAst;
import com.npdev.dsl.v1.ast.ExternalAiAst;
import com.npdev.dsl.v1.ast.EnumOptionAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.FlowScheduleAst;
import com.npdev.dsl.v1.ast.InvariantAst;
import com.npdev.dsl.v1.ast.LifecycleAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.OrchestrationActionAst;
import com.npdev.dsl.v1.ast.OrchestrationAst;
import com.npdev.dsl.v1.ast.OrchestrationTriggerAst;
import com.npdev.dsl.v1.ast.AggregateAst;
import com.npdev.dsl.v1.ast.AggregateCollectionAst;
import com.npdev.dsl.v1.ast.AutoPanelAst;
import com.npdev.dsl.v1.ast.AutoPanelComputedAst;
import com.npdev.dsl.v1.ast.AutoPanelSurfaceAst;
import com.npdev.dsl.v1.ast.SelectorAst;
import com.npdev.dsl.v1.ast.GroupByFieldAst;
import com.npdev.dsl.v1.ast.AggregateFunctionAst;
import com.npdev.dsl.v1.ast.GuidePageAst;
import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.dsl.v1.ast.GuidePageGadgetAst;
import com.npdev.dsl.v1.compiled.FieldWidgetDefaults;
import com.npdev.dsl.v1.compiled.GuidePageDefaults;
import com.npdev.dsl.v1.ast.PanelActionAst;
import com.npdev.dsl.v1.ast.PanelAst;
import com.npdev.dsl.v1.ast.PanelDataSourceAst;
import com.npdev.dsl.v1.ast.PresentationMetadataAst;
import com.npdev.dsl.v1.ast.ProcedureAst;
import com.npdev.dsl.v1.ast.ProcedureParameterAst;
import com.npdev.dsl.v1.ast.ProcedureStepAst;
import com.npdev.dsl.v1.query.GroupByJoinGrammar;
import com.npdev.dsl.v1.query.QueryPredicateGrammar;
import com.npdev.dsl.v1.ast.QueryAst;
import com.npdev.dsl.v1.ast.ReferenceSemanticsAst;
import com.npdev.dsl.v1.ast.RuleProfileAst;
import com.npdev.dsl.v1.ast.TruthLevel;
import com.npdev.dsl.v1.ast.SchemaAst;
import com.npdev.dsl.v1.ast.StateMachineStateAst;
import com.npdev.dsl.v1.ast.StateTransitionAst;
import com.npdev.dsl.v1.ast.StepAst;
import com.npdev.dsl.v1.resolution.ModelResolutionException;
import com.npdev.dsl.v1.resolution.ModelResolver;
import com.npdev.dsl.v1.resolution.ResolvedModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;
import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;
import static com.npdev.dsl.v1.validation.ExpressionValidation.validateParameterNames;
import static com.npdev.dsl.v1.validation.ConceptValidation.BUILTIN_CAPABILITIES;

/**
 * Semantic validation for queries, rule profiles, procedures (and their steps), external-AI
 * egress, and the shared capability-policy / capability-binding checks used by both procedures
 * and flows.
 *
 * <p>Split out of {@code SemanticValidator} (T1.15).
 */
final class PackValidation {

    private PackValidation() {
    }

    private static final Set<String> POLICY_CLASSIFICATIONS =
            Set.of("transient", "permanent", "contract");

    private static final Set<String> RULE_PROFILE_NAMES =
            Set.of("always", "interactive", "interactiveonly", "headless", "headlessonly", "query", "beforecommit", "aftercommit");

    private static final Set<String> PROCEDURE_STEP_TYPES =
            Set.of("assign", "mapvalue", "map_value", "condition", "if", "loop", "foreach",
                    "maplist", "map_list", "listtransform", "computevalue", "compute_value", "compute", "arithmetic",
                    "conceptquery", "readconcept", "read_concept", "listconcepts", "list_concepts",
                    "runquery", "run_query", "conceptcreate", "conceptupdate", "saveconcept", "save_concept",
                    "conceptdelete", "deleteconcept", "delete_concept", "procedurecall", "callprocedure",
                    "call_procedure", "capabilitycall", "callcapability", "call_capability",
                    "eventpublish", "publishevent", "publish_event", "patchconcept", "return");
    private static final Set<String> PROCEDURE_MAP_LIST_STEP_TYPES =
            Set.of("maplist", "map_list", "listtransform");
    private static final Set<String> PROCEDURE_COMPUTE_VALUE_STEP_TYPES =
            Set.of("computevalue", "compute_value", "compute", "arithmetic");
    private static final Set<String> PROCEDURE_COMPUTE_VALUE_OPERATORS =
            Set.of("add", "subtract");
    private static final Set<String> PROCEDURE_CONCEPT_STEP_TYPES =
            Set.of("conceptquery", "readconcept", "read_concept", "listconcepts", "list_concepts",
                    "runquery", "run_query", "conceptcreate", "conceptupdate", "saveconcept", "save_concept",
                    "conceptdelete", "deleteconcept", "delete_concept", "patchconcept");
    private static final Set<String> PROCEDURE_PATCH_STEP_TYPES =
            Set.of("patchconcept");
    private static final Set<String> PROCEDURE_QUERY_STEP_TYPES =
            Set.of("conceptquery", "runquery", "run_query");
    private static final Set<String> PROCEDURE_CALL_STEP_TYPES =
            Set.of("procedurecall", "callprocedure", "call_procedure");
    private static final Set<String> PROCEDURE_CAPABILITY_CALL_STEP_TYPES =
            Set.of("capabilitycall", "callcapability", "call_capability");
    private static final Set<String> PROCEDURE_BRANCH_STEP_TYPES =
            Set.of("condition", "if");
    private static final Set<String> PROCEDURE_LOOP_STEP_TYPES =
            Set.of("foreach", "loop", "maplist", "map_list", "listtransform");

    static void validateQueries(ModelAst modelAst, Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        Set<String> queryNames = new HashSet<>();
        for (QueryAst query : modelAst.getQueries()) {
            if (!queryNames.add(normalize(query.name()))) {
                errors.add("Query " + query.name() + ": duplicate query name");
            }
            ConceptAst concept = entitiesByLower.get(normalize(query.concept()));
            if (concept == null) {
                errors.add("Query " + query.name() + ": concept not found: " + query.concept());
            }
            validateParameterNames("Query " + query.name(), query.parameters(), errors);
            validateQueryWhereCompiles(query, concept, entitiesByLower, errors);
            if (query.isAggregate() && concept != null) {
                validateAggregateQuery(query, concept, entitiesByLower, errors);
            }
        }
    }

    /**
     * Move 12 P1.4 (item 2 / REG-101, fix shape (c)): {@code where} is now refused at AUTHORING
     * time, not just at runtime -- the durable fix the ledger item's own detail names
     * ("move the compiler to a module both the DSL validator and the kernel can use"). Reuses the
     * SAME grammar the kernel's {@code ConceptQueryPredicateCompiler} compiles with
     * ({@link QueryPredicateGrammar}, lifted into this module for exactly this reuse), so a
     * predicate refused here is refused identically at runtime -- one grammar, not two drifting
     * copies (the honest limitation {@code check-query-predicate-compilable.py}'s own docstring
     * named and this move retires).
     *
     * <p>A {@code :name} bind placeholder is valid grammar (REG-101 fix shape (b)/(c)), but only
     * when {@code name} is one of the query's own declared {@code parameters[]} -- a placeholder
     * naming nothing declared is a typo or a forgotten declaration, and X0's rule applies here too:
     * refuse it rather than silently compare every row against the seven-character literal
     * {@code ":name"} (REG-101's own corpus witness, before this fix).
     *
     * <p><b>R4.3 lockstep fix (Roadmap Wave 1 gap closure): now {@link QueryPredicateGrammar#parseGroups},
     * not {@link QueryPredicateGrammar#parse}.</b> R4.3 wired the v2 grammar (OR-groups, {@code in},
     * {@code contains}/{@code startsWith}, {@code is null}/{@code is not null}, a reference-path left
     * side) all the way to real SQL in {@code JdbcBusinessConceptStore} (query()/aggregate(), via
     * {@code ConceptQueryPredicateCompiler#compileToConceptQueryFilters} and {@code
     * ConceptQuery.Operator#OR_GROUPS}) -- proven by a direct H2 integration test ({@code
     * JdbcBusinessConceptStorePredicateV2Test}), but originally left this method on the v1-only
     * grammar because its only production runtime consumer for a declared {@code queries[].where},
     * {@code DefaultProcedureExecutor}'s {@code runQuery} step ({@code com.npdev.kernel.procedures}),
     * still compiled {@code where} with the v1-only {@code ConceptQueryPredicateCompiler#compile}.
     * That call site now uses {@code compileToConceptQueryFilters} (the SAME change that landed this
     * widening), so this method accepting v2 syntax and {@code runQuery} executing it are back in
     * lockstep -- REG-101's validates-clean-then-throws-at-runtime trap does not reopen.
     *
     * <p>A reference-path clause gets the SAME join-path field/reference/{@code access.read} checks
     * {@link #validateGroupByField} already applies to a {@code groupBy} join (see
     * {@link #validatePredicatePath}) -- a predicate join carries the identical information-disclosure
     * shape, but with NO runtime backstop the way {@code groupBy} has one ({@code
     * DefaultConceptGateway#aggregate}'s hard stop): {@code DefaultConceptGateway#query}'s row-level
     * {@code access.read} filter only ever inspects the BASE concept's own fetched records (see that
     * method's own comments), never a concept merely NAMED inside a {@code where} predicate string, and
     * {@code JdbcBusinessConceptStore}'s join rendering has no {@code access.read} awareness at all.
     * So unlike {@code groupBy} (where the compile-time refusal is a documented, liftable "accepted
     * boundary" until {@code access.read} gains a SQL translation), a predicate join into a restricted
     * concept has no runtime enforcement to lift TO -- this refusal is the only enforcement point,
     * full stop.
     *
     * <p>A plain (non-join) field on the left is NOT checked for existence on the concept -- matching
     * both this method's own pre-existing v1 behavior (which never checked either) and what the
     * runtime enforces (neither {@code ConceptQueryPredicateCompiler} nor the stores validate a field
     * name against the schema; an unknown plain field is simply a filter that never matches, exactly
     * as before this widening). Widening validation to accept v2 syntax must not silently become
     * stricter than the v1 contract already was for the shapes v1 could already express.
     */
    private static void validateQueryWhereCompiles(
            QueryAst query, ConceptAst concept, Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        if (!hasText(query.where())) {
            return;
        }
        String here = "Query " + query.name();
        List<List<QueryPredicateGrammar.PredicateClause>> groups;
        try {
            groups = QueryPredicateGrammar.parseGroups(query.where());
        } catch (QueryPredicateGrammar.UnsupportedPredicateException unsupported) {
            errors.add(here + ": where cannot be compiled -- " + unsupported.getMessage());
            return;
        }
        Set<String> declaredParameters = query.parameters().stream()
                .map(ProcedureParameterAst::name)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        Map<String, FieldAst> fieldsByLower = null;
        if (concept != null) {
            fieldsByLower = new HashMap<>();
            for (FieldAst field : concept.getFields()) {
                fieldsByLower.put(normalize(field.getName()), field);
            }
        }
        for (List<QueryPredicateGrammar.PredicateClause> group : groups) {
            for (QueryPredicateGrammar.PredicateClause clause : group) {
                validatePredicateLiteralPlaceholders(here, clause, declaredParameters, errors);
                if (concept != null) {
                    validatePredicatePath(here, clause.path(), concept, fieldsByLower, entitiesByLower, errors);
                }
            }
        }
    }

    /**
     * R4.3 lockstep fix: the v2 sibling of the placeholder check {@code validateQueryWhereCompiles}'s
     * v1 body used to run inline -- a {@code :name} literal must name a declared parameter, same
     * REG-101 rule, extended to check every value inside an {@code in (...)} list ({@link
     * QueryPredicateGrammar.PredicateLiteral.Values}), matching exactly what {@code
     * ConceptQueryPredicateCompiler#resolvePredicateLiteral} enforces at runtime for the same shape.
     */
    private static void validatePredicateLiteralPlaceholders(
            String here, QueryPredicateGrammar.PredicateClause clause, Set<String> declaredParameters, List<String> errors) {
        QueryPredicateGrammar.PredicateLiteral literal = clause.literal();
        if (literal instanceof QueryPredicateGrammar.PredicateLiteral.Values values) {
            for (QueryPredicateGrammar.PredicateLiteral element : values.values()) {
                validateOnePredicatePlaceholder(here, element, declaredParameters, errors);
            }
        } else {
            validateOnePredicatePlaceholder(here, literal, declaredParameters, errors);
        }
    }

    private static void validateOnePredicatePlaceholder(
            String here, QueryPredicateGrammar.PredicateLiteral literal, Set<String> declaredParameters, List<String> errors) {
        if (literal instanceof QueryPredicateGrammar.PredicateLiteral.Placeholder placeholder
                && !declaredParameters.contains(normalize(placeholder.name()))) {
            errors.add(here + ": where references bind placeholder :" + placeholder.name()
                    + ", which is not declared in this query's parameters[] (declared: "
                    + (declaredParameters.isEmpty() ? "none" : new TreeSet<>(declaredParameters)) + ")"
                    + " -- suggestedFix: add a parameter named '" + placeholder.name() + "' to this "
                    + "query's parameters[], or change the placeholder to one already declared there");
        }
    }

    /**
     * R4.3 lockstep fix: validates ONE {@code where} predicate clause's left-hand path. A plain field
     * ({@link GroupByJoinGrammar.Target.Direct}) is deliberately left unchecked -- see this method's
     * caller's own javadoc for why. A reference-path join ({@link GroupByJoinGrammar.Target.Join})
     * gets the identical hop-by-hop walk {@link #validateGroupByField} uses for a {@code groupBy}
     * join (field exists, is a declared reference field, target concept resolves, an optional
     * trailing context matches the actual target, and -- the hard stop -- no hop's target concept may
     * declare {@code access.read}), kept as its own copy rather than a shared extraction so a change
     * to one cannot silently alter the other's error text (both are validated by exact-message tests).
     */
    private static void validatePredicatePath(
            String here,
            GroupByJoinGrammar.Target path,
            ConceptAst concept,
            Map<String, FieldAst> fieldsByLower,
            Map<String, ConceptAst> entitiesByLower,
            List<String> errors
    ) {
        if (path instanceof GroupByJoinGrammar.Target.Direct) {
            return;
        }
        GroupByJoinGrammar.Target.Join join = (GroupByJoinGrammar.Target.Join) path;
        String rawPathText = renderPredicatePath(join);
        ConceptAst currentConcept = concept;
        Map<String, FieldAst> currentFieldsByLower = fieldsByLower;
        int totalHops = join.referenceFields().size();
        for (int hopIndex = 0; hopIndex < totalHops; hopIndex++) {
            String referenceFieldName = join.referenceFields().get(hopIndex);
            FieldAst referenceField = currentFieldsByLower.get(normalize(referenceFieldName));
            if (referenceField == null) {
                errors.add(here + ": where join field not found on concept " + currentConcept.getName() + ": "
                        + referenceFieldName);
                return;
            }
            if (!hasText(referenceField.getReferenceTarget())) {
                errors.add(here + ": where join field " + referenceFieldName + " on concept "
                        + currentConcept.getName() + " is not a reference field -- cannot join through it "
                        + "(named compile error, not a silently dropped clause)");
                return;
            }

            String targetConceptName = referenceField.getReferenceTarget();
            ConceptAst targetConcept = entitiesByLower.get(normalize(targetConceptName));
            if (targetConcept == null) {
                errors.add(here + ": where join field " + referenceFieldName + " targets unknown concept "
                        + targetConceptName + " -- unresolvable join path");
                return;
            }

            boolean isLastHop = hopIndex == totalHops - 1;
            if (isLastHop && join.context() != null) {
                String expectedPrefix = join.context() + "::";
                if (!targetConceptName.startsWith(expectedPrefix)) {
                    errors.add(here + ": where join \"" + rawPathText + "\" declares context '"
                            + join.context() + "', but reference field " + referenceFieldName
                            + "'s actual target is " + targetConceptName + " -- the declared context does not "
                            + "match where the joined concept actually lives");
                    return;
                }
            }

            // No runtime backstop exists for this (unlike groupBy's DefaultConceptGateway#aggregate
            // hard stop) -- see this method's caller's own javadoc. Refusing here is the only
            // enforcement point, not a liftable "accepted boundary".
            if (targetConcept.getAccess() != null && hasText(targetConcept.getAccess().getRead())) {
                errors.add(here + ": where join \"" + rawPathText + "\" crosses into concept "
                        + targetConcept.getName() + ", which declares access.read -- a predicate join has "
                        + "no row-level enforcement at runtime the way this query's own base concept does "
                        + "(DefaultConceptGateway#query filters only the base concept's fetched records), so "
                        + "leaving this unrefused would let a caller infer " + targetConcept.getName()
                        + "'s restricted row values through repeated queries -- suggestedFix: drop the join "
                        + "into " + targetConcept.getName() + " from this query's where, or remove "
                        + "access.read from " + targetConcept.getName() + " if its rows are not actually "
                        + "restricted");
                return;
            }

            currentConcept = targetConcept;
            currentFieldsByLower = new HashMap<>();
            for (FieldAst field : currentConcept.getFields()) {
                currentFieldsByLower.put(normalize(field.getName()), field);
            }
        }

        FieldAst targetField = currentFieldsByLower.get(normalize(join.targetField()));
        if (targetField == null) {
            errors.add(here + ": where join target field not found on concept " + currentConcept.getName()
                    + ": " + join.targetField() + " -- unresolvable join path");
        }
    }

    /** Renders a {@link GroupByJoinGrammar.Target.Join} back to the dotted-path text an author wrote
     *  -- used only for error messages here. Deliberately duplicated rather than shared with {@code
     *  ConceptQueryPredicateCompiler#predicatePathText} (kernel): this module cannot depend on the
     *  kernel, the same reason {@code QueryPredicateGrammar} itself was lifted into this module. */
    private static String renderPredicatePath(GroupByJoinGrammar.Target.Join join) {
        String prefix = join.context() == null ? "" : join.context() + "::";
        return prefix + String.join(".", join.referenceFields()) + "." + join.targetField();
    }

    private static final Set<String> AGGREGATE_NUMERIC_TYPES = Set.of("int", "integer", "long", "decimal");
    private static final Set<String> AGGREGATE_DATE_TYPES = Set.of("date", "datetime");
    private static final Set<String> AGGREGATE_FUNCTIONS = Set.of("count", "sum", "avg", "min", "max");

    /**
     * Move 10 B1 (LC-B1, MOVE10_AI_LOWCODE_PLAN Part B): compile-time checks for a {@code groupBy}/
     * {@code aggregates} query. The HARD STOP here (refusing a concept that declares {@code
     * access.read}) is a security boundary, not a convenience check -- {@code DefaultConceptGateway}
     * enforces row-level {@code access.read} in the JVM, AFTER {@code store.query(...)} returns
     * (see its own {@code isRowReadable} filter, applied post-fetch). A {@code GROUP BY} pushed to
     * SQL computes its totals BEFORE that filter could ever run, so a group total would leak
     * aggregated information about rows the caller is not authorized to read individually -- exactly
     * the information-disclosure shape {@code access.read} exists to prevent. The correct fix
     * (translating {@code access.read} expressions to SQL) is a second expression compiler and is
     * explicitly out of scope for v1; refusing loudly at compile time is the accepted boundary
     * instead (matches the platform's own X0 "an input the evaluator cannot handle is an error"
     * rule) -- lift this the day {@code access.read} gains a SQL translation, not before.
     *
     * <p>S4 (roadmap B27, ADR-0011 D1): a {@code groupBy} field may now be a JOIN
     * ({@link GroupByJoinGrammar}) through a declared {@code reference} field -- {@code
     * "lote.produtoId"}, optionally context-qualified ({@code "inventory::lote.produtoId"}). The
     * {@code access.read} hard stop above widens to the WHOLE join path (C3): a join makes the
     * information-disclosure shape strictly worse, since now ANY concept the join crosses into with
     * {@code access.read} taints the result, not just the query's own base concept.
     *
     * <p>S8 W1.1 (roadmap deferred item #1): the join may now chain up to
     * {@link GroupByJoinGrammar#MAX_JOIN_HOPS} reference-field hops -- C3's widened guard applies to
     * EVERY concept the chain crosses, not just the first/only one, since a longer chain is just
     * more ways to leak a total over rows the caller could not read individually.
     */
    private static void validateAggregateQuery(
            QueryAst query, ConceptAst concept, Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        String here = "Query " + query.name();
        if (concept.getAccess() != null && hasText(concept.getAccess().getRead())) {
            errors.add(here + ": groupBy/aggregates are not supported on concept " + concept.getName()
                    + ", which declares access.read -- a pushed-down GROUP BY would compute totals over "
                    + "rows the row-level access.read scope exists to hide (accepted boundary; lift when "
                    + "access.read gains a SQL translation)");
            return;
        }

        Map<String, FieldAst> fieldsByLower = new HashMap<>();
        for (FieldAst field : concept.getFields()) {
            fieldsByLower.put(normalize(field.getName()), field);
        }

        for (GroupByFieldAst groupByField : query.groupBy()) {
            validateGroupByField(here, concept, groupByField, fieldsByLower, entitiesByLower, errors);
        }

        Set<String> aggregateNames = new HashSet<>();
        for (AggregateFunctionAst aggregate : query.aggregates()) {
            if (!hasText(aggregate.name())) {
                errors.add(here + ": an aggregate is missing its own output name");
            } else if (!aggregateNames.add(normalize(aggregate.name()))) {
                errors.add(here + ": duplicate aggregate output name: " + aggregate.name());
            }
            String fn = normalize(aggregate.fn());
            if (!AGGREGATE_FUNCTIONS.contains(fn)) {
                errors.add(here + ": aggregate " + aggregate.name() + " has an unsupported fn: "
                        + aggregate.fn() + " (supported: " + AGGREGATE_FUNCTIONS + ")");
                continue;
            }
            if ("count".equals(fn)) {
                continue;
            }
            if (!hasText(aggregate.field())) {
                errors.add(here + ": aggregate " + aggregate.name() + " (fn=" + fn + ") requires a field");
                continue;
            }
            FieldAst field = fieldsByLower.get(normalize(aggregate.field()));
            if (field == null) {
                errors.add(here + ": aggregate " + aggregate.name() + " field not found on concept "
                        + concept.getName() + ": " + aggregate.field());
                continue;
            }
            if (("sum".equals(fn) || "avg".equals(fn))
                    && !AGGREGATE_NUMERIC_TYPES.contains(normalize(field.getType()))) {
                errors.add(here + ": aggregate " + aggregate.name() + " (fn=" + fn + ") requires a numeric "
                        + "field, but " + aggregate.field() + " has type " + field.getType());
            }
        }
    }

    /**
     * S4 (roadmap B27, ADR-0011 D1/C1-C3) + S8 W1.1: validates ONE {@code groupBy} entry -- a plain
     * field (unchanged from Move 10 B1) or a 1-to-{@link GroupByJoinGrammar#MAX_JOIN_HOPS}-hop join.
     * X0 applies throughout: a join path this method cannot resolve is a named error via
     * {@code return} (this entry's own further checks are skipped, but the REST of the query is
     * still validated -- one bad field must not hide every other finding), never a silently-accepted
     * or partially-applied clause.
     */
    private static void validateGroupByField(
            String here,
            ConceptAst concept,
            GroupByFieldAst groupByField,
            Map<String, FieldAst> fieldsByLower,
            Map<String, ConceptAst> entitiesByLower,
            List<String> errors
    ) {
        GroupByJoinGrammar.Target target;
        try {
            target = GroupByJoinGrammar.parse(groupByField.field());
        } catch (GroupByJoinGrammar.UnsupportedGroupByPathException unsupported) {
            errors.add(here + ": groupBy field cannot be parsed -- " + unsupported.getMessage());
            return;
        }

        if (target instanceof GroupByJoinGrammar.Target.Direct direct) {
            FieldAst field = fieldsByLower.get(normalize(direct.field()));
            if (field == null) {
                errors.add(here + ": groupBy field not found on concept " + concept.getName() + ": " + direct.field());
                return;
            }
            if (hasText(groupByField.bucket()) && !AGGREGATE_DATE_TYPES.contains(normalize(field.getType()))) {
                errors.add(here + ": groupBy field " + direct.field() + " has bucket \""
                        + groupByField.bucket() + "\" but its type (" + field.getType()
                        + ") is not date/datetime"
                        + " -- suggestedFix: remove the bucket from this groupBy entry, or group by a "
                        + "date/datetime field instead -- bucketing is a calendar operation and has no "
                        + "meaning on " + field.getType());
            }
            return;
        }

        // S8 W1.1: walk the whole reference-field chain, one hop at a time. C3's access.read hard
        // stop is re-checked at EVERY hop's target concept, not just the last -- a longer chain is
        // just more ways to leak a total over rows the caller could not read individually.
        GroupByJoinGrammar.Target.Join join = (GroupByJoinGrammar.Target.Join) target;
        ConceptAst currentConcept = concept;
        Map<String, FieldAst> currentFieldsByLower = fieldsByLower;
        int totalHops = join.referenceFields().size();
        for (int hopIndex = 0; hopIndex < totalHops; hopIndex++) {
            String referenceFieldName = join.referenceFields().get(hopIndex);
            FieldAst referenceField = currentFieldsByLower.get(normalize(referenceFieldName));
            if (referenceField == null) {
                errors.add(here + ": groupBy join field not found on concept " + currentConcept.getName() + ": "
                        + referenceFieldName);
                return;
            }
            if (!hasText(referenceField.getReferenceTarget())) {
                errors.add(here + ": groupBy join field " + referenceFieldName + " on concept "
                        + currentConcept.getName() + " is not a reference field -- cannot join through it "
                        + "(named compile error, not a silently dropped clause)");
                return;
            }

            String targetConceptName = referenceField.getReferenceTarget();
            ConceptAst targetConcept = entitiesByLower.get(normalize(targetConceptName));
            if (targetConcept == null) {
                errors.add(here + ": groupBy join field " + referenceFieldName + " targets unknown concept "
                        + targetConceptName + " -- unresolvable join path");
                return;
            }

            boolean isLastHop = hopIndex == totalHops - 1;
            if (isLastHop && join.context() != null) {
                String expectedPrefix = join.context() + "::";
                if (!targetConceptName.startsWith(expectedPrefix)) {
                    errors.add(here + ": groupBy join \"" + groupByField.field() + "\" declares context '"
                            + join.context() + "', but reference field " + referenceFieldName
                            + "'s actual target is " + targetConceptName + " -- the declared context does not "
                            + "match where the joined concept actually lives");
                    return;
                }
            }

            // C3: the access.read hard stop widens to the WHOLE join path (every hop, not just the
            // first or last) -- a group total computed by joining through a field is exactly as much
            // of a leak as one computed directly on a restricted concept (see this method's javadoc
            // and the class-level one above it).
            if (targetConcept.getAccess() != null && hasText(targetConcept.getAccess().getRead())) {
                errors.add(here + ": groupBy join \"" + groupByField.field() + "\" crosses into concept "
                        + targetConcept.getName() + ", which declares access.read -- a pushed-down GROUP BY "
                        + "would compute totals over rows the row-level access.read scope exists to hide, the "
                        + "same leak whether the restricted concept is queried directly or reached through a "
                        + "join (accepted boundary; lift when access.read gains a SQL translation)"
                        + " -- suggestedFix: drop the join into " + targetConcept.getName() + " from this "
                        + "query's groupBy, or remove access.read from " + targetConcept.getName()
                        + " if its rows are not actually restricted");
                return;
            }

            currentConcept = targetConcept;
            currentFieldsByLower = new HashMap<>();
            for (FieldAst field : currentConcept.getFields()) {
                currentFieldsByLower.put(normalize(field.getName()), field);
            }
        }

        FieldAst targetField = currentFieldsByLower.get(normalize(join.targetField()));
        if (targetField == null) {
            errors.add(here + ": groupBy join target field not found on concept " + currentConcept.getName()
                    + ": " + join.targetField() + " -- unresolvable join path");
            return;
        }
        if (hasText(groupByField.bucket()) && !AGGREGATE_DATE_TYPES.contains(normalize(targetField.getType()))) {
            errors.add(here + ": groupBy join \"" + groupByField.field() + "\" has bucket \""
                    + groupByField.bucket() + "\" but its target field's type (" + targetField.getType()
                    + ") is not date/datetime"
                    + " -- suggestedFix: remove the bucket from this groupBy entry, or point the join at a "
                    + "date/datetime field on the joined concept -- bucketing has no meaning on "
                    + targetField.getType());
        }
    }

    static void validateRuleProfiles(ModelAst modelAst, Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        Set<String> names = new HashSet<>();
        Set<String> knownTargets = new HashSet<>(entitiesByLower.keySet());
        for (QueryAst query : modelAst.getQueries()) {
            knownTargets.add(normalize(query.name()));
        }
        for (ProcedureAst procedure : modelAst.getProcedures()) {
            knownTargets.add(normalize(procedure.name()));
        }
        for (PanelAst panel : modelAst.getPanels()) {
            knownTargets.add(normalize(panel.name()));
        }

        for (RuleProfileAst profile : modelAst.getRuleProfiles()) {
            String name = normalize(profile.name());
            if (!names.add(name)) {
                errors.add("RuleProfile " + profile.name() + ": duplicate rule profile name");
            }
            if (!RULE_PROFILE_NAMES.contains(name)) {
                errors.add("RuleProfile " + profile.name() + ": unsupported rule profile name");
            }
            for (String target : profile.appliesTo()) {
                if (!knownTargets.contains(normalize(target))) {
                    errors.add("RuleProfile " + profile.name() + ": appliesTo target not found: " + target);
                }
            }
        }
    }

    static void validateProcedures(ModelAst modelAst, Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        Set<String> procedureNames = modelAst.getProcedures().stream()
                .map(ProcedureAst::name)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        Set<String> queryNames = modelAst.getQueries().stream()
                .map(QueryAst::name)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        Map<String, CapabilityAst> capabilitiesByLower = new HashMap<>();
        for (CapabilityAst capability : modelAst.getCapabilities()) {
            capabilitiesByLower.put(normalize(capability.getName()), capability);
        }
        Set<String> seen = new HashSet<>();
        for (ProcedureAst procedure : modelAst.getProcedures()) {
            String procedureName = procedure.name();
            if (!seen.add(normalize(procedureName))) {
                errors.add("Procedure " + procedureName + ": duplicate procedure name");
            }
            validateParameterNames("Procedure " + procedureName, procedure.parameters(), errors);
            if (procedure.steps().isEmpty()) {
                errors.add("Procedure " + procedureName + ": steps must not be empty");
            }
            validateProcedureSteps(
                    procedureName,
                    "procedures[" + procedureName + "].steps",
                    procedure.steps(),
                    entitiesByLower,
                    queryNames,
                    procedureNames,
                    capabilitiesByLower,
                    errors
            );
        }
    }

    private static void validateProcedureSteps(
            String procedureName,
            String path,
            List<ProcedureStepAst> steps,
            Map<String, ConceptAst> entitiesByLower,
            Set<String> queryNames,
            Set<String> procedureNames,
            Map<String, CapabilityAst> capabilitiesByLower,
            List<String> errors
    ) {
        int index = 0;
        for (ProcedureStepAst step : steps) {
            String stepPath = path + "[" + index + "]";
            String type = normalize(step.type());
            if (!PROCEDURE_STEP_TYPES.contains(type)) {
                errors.add("Procedure " + procedureName + " step " + stepPath + ": unsupported step type " + step.type());
            }
            if (PROCEDURE_CONCEPT_STEP_TYPES.contains(type)
                    && !entitiesByLower.containsKey(normalize(step.concept()))) {
                errors.add("Procedure " + procedureName + " step " + stepPath + ": concept not found: " + step.concept());
            }
            if (PROCEDURE_QUERY_STEP_TYPES.contains(type)
                    && hasText(step.query())
                    && !queryNames.contains(normalize(step.query()))) {
                errors.add("Procedure " + procedureName + " step " + stepPath + ": query not found: " + step.query());
            }
            if (PROCEDURE_CALL_STEP_TYPES.contains(type)
                    && !procedureNames.contains(normalize(step.procedure()))) {
                errors.add("Procedure " + procedureName + " step " + stepPath + ": procedure not found: " + step.procedure());
            }
            if (PROCEDURE_CAPABILITY_CALL_STEP_TYPES.contains(type)) {
                validateProcedureCapabilityCall(procedureName, stepPath, step, capabilitiesByLower, errors);
            }
            if (PROCEDURE_PATCH_STEP_TYPES.contains(type)) {
                validateProcedurePatchConcept(procedureName, stepPath, step, entitiesByLower, errors);
            }
            if (PROCEDURE_MAP_LIST_STEP_TYPES.contains(type)) {
                validateProcedureMapList(procedureName, stepPath, step, errors);
            }
            if (PROCEDURE_COMPUTE_VALUE_STEP_TYPES.contains(type)) {
                validateProcedureComputeValue(procedureName, stepPath, step, errors);
            }
            if (PROCEDURE_BRANCH_STEP_TYPES.contains(type) && !hasText(step.condition())) {
                errors.add("Procedure " + procedureName + " step " + stepPath + ": condition is required");
            }
            if (PROCEDURE_LOOP_STEP_TYPES.contains(type) && !hasText(step.items())) {
                errors.add("Procedure " + procedureName + " step " + stepPath + ": " + step.type() + " requires items");
            }
            validateProcedureSteps(procedureName, stepPath + ".then", step.thenSteps(), entitiesByLower, queryNames, procedureNames, capabilitiesByLower, errors);
            validateProcedureSteps(procedureName, stepPath + ".else", step.elseSteps(), entitiesByLower, queryNames, procedureNames, capabilitiesByLower, errors);
            validateProcedureSteps(procedureName, stepPath + ".steps", step.steps(), entitiesByLower, queryNames, procedureNames, capabilitiesByLower, errors);
            index++;
        }
    }

    /**
     * LIFT-QUERY-P3: a {@code callCapability} procedure step references a declared capability +
     * operation, and its arg count matches that operation's declared {@code input} arity -- the
     * dispatcher itself matches by name+arity only (no type-checking, per LIFT-QUERY-P2's
     * research), so this is the only place a mismatched arity gets caught before runtime.
     */
    private static void validateProcedureCapabilityCall(
            String procedureName,
            String stepPath,
            ProcedureStepAst step,
            Map<String, CapabilityAst> capabilitiesByLower,
            List<String> errors
    ) {
        if (!hasText(step.capability())) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": capability is required for callCapability");
            return;
        }
        CapabilityAst capability = capabilitiesByLower.get(normalize(step.capability()));
        if (capability == null) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": capability not found: " + step.capability());
            return;
        }
        if (!hasText(step.operation())) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": operation is required for callCapability");
            return;
        }
        Optional<CapabilityOperationAst> operation = capability.getOperations().stream()
                .filter(op -> normalize(op.getName()).equals(normalize(step.operation())))
                .findFirst();
        if (operation.isEmpty()) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": capability " + step.capability()
                    + " has no operation named " + step.operation());
            return;
        }
        // Arity is declared one of two ways depending on authoring style: the legacy plain-array
        // `input: ["a","b"]` shorthand (CapabilityOperationAst.getInput()), or the schemaObject
        // form (`input: {type: object, properties: {a: {...}, b: {...}}}`) that's the only shape
        // the JSON Schema's capabilityOperation.input actually accepts today. The bare-string
        // `"operations": ["save", "unique"]` shorthand declares neither -- no contract to check
        // arity against, so it's skipped rather than flagged (consistent with treating an
        // underspecified operation as accepting anything, its existing behavior everywhere else).
        Integer declaredArity = null;
        if (!operation.get().getInput().isEmpty()) {
            declaredArity = operation.get().getInput().size();
        } else if (operation.get().getInputSchema() != null && !operation.get().getInputSchema().getProperties().isEmpty()) {
            declaredArity = operation.get().getInputSchema().getProperties().size();
        }
        if (declaredArity == null) {
            return;
        }
        int actualArity = step.args() == null ? 0 : step.args().size();
        if (!declaredArity.equals(actualArity)) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": capability " + step.capability() + "."
                    + step.operation() + " expects " + declaredArity + " arg(s) but this call supplies " + actualArity
                    + " -- suggestedFix: supply exactly " + declaredArity + " arg(s) here, or change the "
                    + "operation's declared parameters on capability " + step.capability() + " to take "
                    + actualArity);
        }
    }

    /**
     * Move 4 (docs/MOVE4_CROSS_RECORD_WRITE_PLAN.md): a {@code patchConcept} step names a real
     * concept and id (already checked generically by {@code PROCEDURE_CONCEPT_STEP_TYPES}), plus a
     * non-empty {@code set} whose every key is a declared field of that concept -- catching a typo'd
     * field name at author time (the REG-71 class of bug) instead of at runtime.
     *
     * <p>REG-89: {@code id} is required only for a genuine PATCH. {@code createIfMissing} (Move 5
     * Wave 1B, REG-77's create half) explicitly supports a create-only call with no id to look up
     * -- {@code DefaultProcedureExecutor.patchConcept}'s own doc comment: "tolerates a
     * blank/unresolved idRef (nothing to look up yet) and, on a miss, builds a brand-new record
     * from {@code set} alone with a freshly generated id ... so a caller that queried for a match
     * first and found none can still invoke this with a blank idRef." This rule was written before
     * that flag existed and never relaxed for it, making the runtime feature unreachable from any
     * model: the only way past it was to declare a deliberately dangling ref and rely on it
     * resolving to null (what {@code dsl-conformance-max}'s own fixture comment describes as "id
     * references a key nothing populates"). Everything else below is unchanged -- a create still
     * needs a non-empty {@code set}, and its field names are still checked.
     */
    private static void validateProcedurePatchConcept(
            String procedureName,
            String stepPath,
            ProcedureStepAst step,
            Map<String, ConceptAst> entitiesByLower,
            List<String> errors
    ) {
        if (!hasText(step.id()) && !step.createIfMissing()) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": id is required for patchConcept");
        }
        if (step.set() == null || step.set().isEmpty()) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": set is required for patchConcept and must not be empty");
            return;
        }
        ConceptAst concept = entitiesByLower.get(normalize(step.concept()));
        if (concept == null) {
            return; // already reported by the generic PROCEDURE_CONCEPT_STEP_TYPES check
        }
        Set<String> declaredFields = concept.getFields().stream()
                .map(FieldAst::getName)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        for (String field : step.set().keySet()) {
            if (!declaredFields.contains(normalize(field))) {
                errors.add("Procedure " + procedureName + " step " + stepPath + ": set names a field not declared on "
                        + step.concept() + ": " + field
                        + " -- suggestedFix: declare a field named '" + field + "' on concept "
                        + step.concept() + ", or correct the key in this step's set{} to one it already has");
            }
        }
    }

    /**
     * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 3A / Gap 6): a {@code mapList} step needs a
     * non-empty {@code select} (the per-item field map, resolved via the same convention as
     * {@code patchConcept}'s {@code set}) and a {@code target} naming the output list -- unlike
     * {@code patchConcept}, there is no concept to check field names against, since the produced
     * list is not itself a persisted record.
     */
    private static void validateProcedureMapList(
            String procedureName,
            String stepPath,
            ProcedureStepAst step,
            List<String> errors
    ) {
        if (step.select() == null || step.select().isEmpty()) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": select is required for mapList and must not be empty");
        }
        if (!hasText(step.target())) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": target is required for mapList (names the output list)");
        }
    }

    /**
     * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, final item / REG-78): {@code computeValue} needs a
     * known operator ("add"/"subtract" -- the minimum REG-78 named, matching {@code
     * DefaultProcedureExecutor}'s own switch), both operands present (a literal or a {@code $ref},
     * either is fine -- {@code null} is not), and a target to write the result to.
     */
    private static void validateProcedureComputeValue(
            String procedureName,
            String stepPath,
            ProcedureStepAst step,
            List<String> errors
    ) {
        String operator = step.operation() == null ? "" : step.operation().trim().toLowerCase(java.util.Locale.ROOT);
        if (!PROCEDURE_COMPUTE_VALUE_OPERATORS.contains(operator)) {
            errors.add("Procedure " + procedureName + " step " + stepPath
                    + ": computeValue requires operation to be one of " + PROCEDURE_COMPUTE_VALUE_OPERATORS
                    + ", got: " + step.operation());
        }
        if (step.left() == null) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": left is required for computeValue");
        }
        if (step.right() == null) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": right is required for computeValue");
        }
        if (!hasText(step.target())) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": target is required for computeValue (names where the result is written)");
        }
    }

    static void validateCapabilityPolicy(
            String prefix,
            CapabilityPolicyAst policy,
            List<String> errors
    ) {
        if (policy == null) {
            return;
        }
        if (policy.getRetryCount() != null && policy.getRetryCount() < 1) {
            errors.add(prefix + "policy.retryCount must be >= 1");
        }
        if (policy.getRetryDelayMs() != null && policy.getRetryDelayMs() < 0) {
            errors.add(prefix + "policy.retryDelayMs must be >= 0");
        }
        if (policy.getTimeoutMs() != null && policy.getTimeoutMs() < 0) {
            errors.add(prefix + "policy.timeoutMs must be >= 0");
        }
        if (policy.getCircuitOpenAfterFailures() != null && policy.getCircuitOpenAfterFailures() < 1) {
            errors.add(prefix + "policy.circuitOpenAfterFailures must be >= 1");
        }
        if (policy.getCircuitOpenMs() != null && policy.getCircuitOpenMs() < 0) {
            errors.add(prefix + "policy.circuitOpenMs must be >= 0");
        }
        if (policy.getBulkheadMaxConcurrent() != null && policy.getBulkheadMaxConcurrent() < 1) {
            errors.add(prefix + "policy.bulkheadMaxConcurrent must be >= 1");
        }
        String idempotencyKeyField = normalize(policy.getIdempotencyKeyField());
        if (policy.getIdempotencyKeyField() != null && idempotencyKeyField.isBlank()) {
            errors.add(prefix + "policy.idempotencyKeyField must be non-blank when provided");
        }
        String classification = normalize(policy.getFailureClassification());
        if (!classification.isBlank() && !POLICY_CLASSIFICATIONS.contains(classification)) {
            errors.add(prefix + "policy.failureClassification must be one of TRANSIENT, PERMANENT, CONTRACT");
        }
    }

    static void validateReferencedCapabilityBindings(
            ModelAst modelAst,
            Set<String> referencedCapabilities,
            List<String> errors
    ) {
        if (referencedCapabilities == null || referencedCapabilities.isEmpty()) {
            return;
        }
        Set<String> boundCapabilities = modelAst.getBindings().stream()
                .map(CapabilityBindingAst::getCapability)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        for (String capability : referencedCapabilities) {
            if (BUILTIN_CAPABILITIES.contains(capability)) {
                continue;
            }
            if (!boundCapabilities.contains(capability)) {
                errors.add("Flow references capability without binding: " + capability
                        + " -- suggestedFix: add a bindings[] entry for capability '" + capability
                        + "' naming the adapter that implements it; a flow may only call a capability "
                        + "the model has bound to something runnable");
            }
        }
    }

    /**
     * ADR-0009: egress must not be enabled with no vendor configured. The model-level analogue of
     * {@code ExternalAiCapabilityContract}'s fail-closed runtime default (a contract with no adapter
     * opted in denies) -- an author who sets {@code egress} to anything but {@code denied} without
     * naming at least one vendor in {@code externalAi.vendors} is caught here, at author time,
     * instead of only discovering the gap when {@code external-ai-http} has no vendor profile to
     * resolve against at runtime.
     */
    static void validateExternalAiEgress(ModelAst modelAst, List<String> errors) {
        ExternalAiAst externalAi = modelAst.getExternalAi();
        if (externalAi == null || "denied".equalsIgnoreCase(externalAi.getEgress())) {
            return;
        }
        if (externalAi.getVendors().isEmpty()) {
            errors.add("externalAi.egress is '" + externalAi.getEgress() + "' but no vendors are declared -- "
                    + "egress requires at least one vendor in externalAi.vendors (see ADR-0009 D1).");
        }
    }

}
