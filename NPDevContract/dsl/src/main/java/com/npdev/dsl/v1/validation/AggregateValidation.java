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
import com.npdev.dsl.v1.ast.AggregateBalanceAst;
import com.npdev.dsl.v1.ast.AggregateCollectionAst;
import com.npdev.dsl.v1.ast.AggregateCollectionLookupFieldAst;
import com.npdev.dsl.v1.ast.AggregateInvariantAst;
import com.npdev.dsl.v1.ast.AutoPanelAst;
import com.npdev.dsl.v1.ast.AutoPanelComputedAst;
import com.npdev.dsl.v1.ast.AutoPanelSurfaceAst;
import com.npdev.dsl.v1.ast.SelectorAst;
import com.npdev.dsl.v1.ast.TransactionHooksAst;
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

/**
 * Semantic validation for aggregates: root concept resolution and the (possibly nested)
 * collections tree, including the owned-composition cycle guard.
 *
 * <p>Split out of {@code SemanticValidator} (T1.15).
 */
final class AggregateValidation {

    private AggregateValidation() {
    }

    static void validateAggregates(ModelAst modelAst, Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        Set<String> aggregateNames = new HashSet<>();
        Map<String, ProcedureAst> proceduresByLower = new HashMap<>();
        for (ProcedureAst procedure : modelAst.getProcedures()) {
            proceduresByLower.put(normalize(procedure.name()), procedure);
        }
        Map<String, QueryAst> queriesByLower = new HashMap<>();
        for (QueryAst query : modelAst.getQueries()) {
            queriesByLower.put(normalize(query.name()), query);
        }
        for (AggregateAst aggregate : modelAst.getAggregates()) {
            if (!aggregateNames.add(normalize(aggregate.name()))) {
                errors.add("Aggregate " + aggregate.name() + ": duplicate aggregate name");
            }
            if (!hasText(aggregate.root())) {
                errors.add("Aggregate " + aggregate.name() + ": root concept is required");
            } else if (!entitiesByLower.containsKey(normalize(aggregate.root()))) {
                errors.add("Aggregate " + aggregate.name() + ": root concept not found: " + aggregate.root());
            }
            validateAggregateCollections(
                    aggregate.name(),
                    "Aggregate " + aggregate.name(),
                    aggregate.collections(),
                    entitiesByLower,
                    queriesByLower,
                    new HashSet<>(),
                    errors);
            // Move 6 Move B (docs/MOVE6_TYPED_SURFACE_PLAN.md §B.2): an aggregate-bound AutoPanel's
            // transaction.hooks is an alternate spelling for onValidate/onCommit -- validate whichever
            // one actually takes effect (a direct aggregate.onValidate/onCommit always wins), and
            // separately validate the three hook positions with no aggregate-level equivalent.
            TransactionHooksAst hooks = transactionHooksFor(aggregate.name(), modelAst.getAutoPanels());
            validateOnCommit(aggregate, hooks, proceduresByLower, errors);
            validateOnValidate(aggregate, hooks, proceduresByLower, errors);
            validateHookProcedure(aggregate.name(), "onLoad",
                    hooks == null ? null : hooks.onLoad(), proceduresByLower, errors);
            validateHookProcedure(aggregate.name(), "onFieldChange",
                    hooks == null ? null : hooks.onFieldChange(), proceduresByLower, errors);
            validateHookProcedure(aggregate.name(), "beforeAction",
                    hooks == null ? null : hooks.beforeAction(), proceduresByLower, errors);
            validateAggregateInvariants(aggregate, errors);
            validateAggregateBalances(aggregate, errors);
        }
    }

    /**
     * Session 1 (NPDEV_MEGA_ROADMAP.md, 2026-09-14): a declared balance rule's {@code name} is the
     * rule identifier a commit-time API error names, so it must be present and unique within its
     * aggregate, mirroring {@link #validateAggregateInvariants}. {@code collection} must resolve to
     * a real dotted collection path within the aggregate's own composition tree (the same address
     * format {@code PanelValidation}'s {@code derivedAddresses} already produces for
     * {@code visibleWhen}/{@code regions}). {@code leftValue}/{@code rightValue} must differ -- a
     * balance between a side and itself is always trivially satisfied and is almost certainly an
     * authoring mistake.
     */
    private static void validateAggregateBalances(AggregateAst aggregate, List<String> errors) {
        Set<String> collectionPaths = new HashSet<>();
        collectCollectionPaths("", aggregate.collections(), collectionPaths);
        Set<String> seenNames = new HashSet<>();
        for (AggregateBalanceAst balance : aggregate.balances()) {
            String here = "Aggregate " + aggregate.name() + " balance";
            if (!hasText(balance.name())) {
                errors.add(here + ": name is required");
            } else {
                here = "Aggregate " + aggregate.name() + " balance '" + balance.name() + "'";
                if (!seenNames.add(normalize(balance.name()))) {
                    errors.add(here + ": duplicate balance name within this aggregate");
                }
            }
            if (!hasText(balance.collection())) {
                errors.add(here + ": collection is required");
            } else if (!collectionPaths.contains(normalize(balance.collection()))) {
                errors.add(here + ": collection not found: " + balance.collection());
            }
            if (!hasText(balance.discriminatorField())) {
                errors.add(here + ": discriminatorField is required");
            }
            if (!hasText(balance.leftValue())) {
                errors.add(here + ": leftValue is required");
            }
            if (!hasText(balance.rightValue())) {
                errors.add(here + ": rightValue is required");
            }
            if (hasText(balance.leftValue()) && hasText(balance.rightValue())
                    && normalize(balance.leftValue()).equals(normalize(balance.rightValue()))) {
                errors.add(here + ": leftValue and rightValue cannot both be the same value: "
                        + balance.leftValue());
            }
            if (!hasText(balance.quantityField())) {
                errors.add(here + ": quantityField is required");
            }
        }
    }

    /** Every dotted collection path reachable from an aggregate's root, at any depth (e.g.
     *  "itens" and "itens.posicoes") -- the same address family {@code balance.collection}
     *  references. Does not cap depth: a balance naming a path past the workbench UI's own
     *  render-depth limit is a UI-rendering concern, not something this DSL-level check owns. */
    private static void collectCollectionPaths(
            String prefix, List<AggregateCollectionAst> collections, Set<String> out) {
        for (AggregateCollectionAst collection : collections) {
            if (!hasText(collection.name())) {
                continue;
            }
            String path = prefix.isEmpty() ? collection.name() : prefix + "." + collection.name();
            out.add(normalize(path));
            collectCollectionPaths(path, collection.collections(), out);
        }
    }

    /**
     * R4.4 (Roadmap Wave 1 2026-08-19): a declared aggregate invariant's {@code name} is the rule
     * identifier a commit-time API error names (the done-when's "the failing rule named in the API
     * error"), so it must be present and unique within its aggregate -- a blank or duplicate name
     * would let a veto happen with no way for the caller to tell which rule fired. {@code
     * expression} must parse and be boolean-shaped, the same author-time check ConceptAst's own
     * expression-typed invariants already get (LIFT-EXPR-P3's {@code isBooleanShaped}); this is a
     * syntactic check only -- it does not (cannot, statically) know which collection names the
     * runtime draft tree will actually have, so an expression referencing a collection or field
     * that does not exist still parses clean here and fails only when evaluated against a real
     * draft.
     */
    private static void validateAggregateInvariants(AggregateAst aggregate, List<String> errors) {
        Set<String> seenNames = new HashSet<>();
        for (AggregateInvariantAst invariant : aggregate.invariants()) {
            String here = "Aggregate " + aggregate.name() + " invariant";
            if (!hasText(invariant.name())) {
                errors.add(here + ": name is required");
            } else {
                here = "Aggregate " + aggregate.name() + " invariant '" + invariant.name() + "'";
                if (!seenNames.add(normalize(invariant.name()))) {
                    errors.add(here + ": duplicate invariant name within this aggregate");
                }
            }
            if (!hasText(invariant.expression())) {
                errors.add(here + ": expression is required");
                continue;
            }
            try {
                ComputedExpression.validate(invariant.expression());
            } catch (ComputedExpression.ExpressionException syntaxError) {
                errors.add(here + ": expression does not parse: " + syntaxError.getMessage());
                continue;
            }
            try {
                if (!ComputedExpression.isBooleanShaped(invariant.expression())) {
                    errors.add(here + ": expression must be boolean-shaped "
                            + "(a comparison, &&/||, unary !, or a boolean literal at the top level)");
                }
            } catch (ComputedExpression.ExpressionException ignored) {
                // Already reported by the parse check above.
            }
        }
    }

    private static TransactionHooksAst transactionHooksFor(String aggregateName, List<AutoPanelAst> autoPanels) {
        if (aggregateName == null) {
            return null;
        }
        for (AutoPanelAst autoPanel : autoPanels) {
            if (aggregateName.equalsIgnoreCase(autoPanel.aggregate()) && autoPanel.transaction() != null) {
                return autoPanel.transaction().hooks();
            }
        }
        return null;
    }

    private static void validateHookProcedure(
            String aggregateName, String position, String procedureName,
            Map<String, ProcedureAst> proceduresByLower, List<String> errors) {
        if (!hasText(procedureName)) {
            return;
        }
        if (!proceduresByLower.containsKey(normalize(procedureName))) {
            errors.add("Aggregate " + aggregateName + ": transaction.hooks." + position
                    + " names a procedure not found: " + procedureName);
        }
    }

    /**
     * Move 4 (docs/MOVE4_CROSS_RECORD_WRITE_PLAN.md): {@code onCommit} must name a declared
     * procedure (run inside {@code AggregateRuntime.commitInternal}'s own transaction, after the
     * tree is written -- see the runtime for why a failure there rolls back the whole commit).
     *
     * <p>Direct self-recursion (the onCommit procedure calling itself via {@code callProcedure}, at
     * any nesting depth in its own step tree) is rejected here as an author-time linting check.
     * Procedures have no step that invokes {@code AggregateRuntime.commit} at all, so "the procedure
     * re-commits the same aggregate" cannot literally happen -- this instead catches the narrower,
     * real case: a procedure named as {@code onCommit} that calls itself. Deeper indirect cycles
     * (P calls Q calls P) are intentionally left to the kernel's existing
     * {@code maxRecursionDepth} runtime guard (DefaultProcedureExecutor) rather than solved here.
     */
    private static void validateOnCommit(
            AggregateAst aggregate, TransactionHooksAst hooks, Map<String, ProcedureAst> proceduresByLower,
            List<String> errors) {
        String onCommit = hasText(aggregate.onCommit())
                ? aggregate.onCommit() : (hooks == null ? null : hooks.onCommit());
        if (!hasText(onCommit)) {
            return;
        }
        String normalized = normalize(onCommit);
        ProcedureAst procedure = proceduresByLower.get(normalized);
        if (procedure == null) {
            errors.add("Aggregate " + aggregate.name() + ": onCommit names a procedure not found: " + onCommit);
            return;
        }
        if (callsProcedure(procedure.steps(), normalized)) {
            errors.add("Aggregate " + aggregate.name() + ": onCommit procedure " + onCommit
                    + " directly calls itself (recursive onCommit is not allowed)");
        }
    }

    /**
     * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 3B / Gap 8): {@code onValidate} must name a
     * declared procedure -- deliberately a SIBLING of {@code onCommit} rather than a flag on it
     * (different timing: before the root upsert, not after; different contract: a non-ok result
     * aborts with NO writes at all, rather than rolling back writes already made). Reuses the exact
     * same direct-self-recursion linting check as {@code onCommit}, for the same reason.
     */
    private static void validateOnValidate(
            AggregateAst aggregate, TransactionHooksAst hooks, Map<String, ProcedureAst> proceduresByLower,
            List<String> errors) {
        String onValidate = hasText(aggregate.onValidate())
                ? aggregate.onValidate() : (hooks == null ? null : hooks.onValidate());
        if (!hasText(onValidate)) {
            return;
        }
        String normalized = normalize(onValidate);
        ProcedureAst procedure = proceduresByLower.get(normalized);
        if (procedure == null) {
            errors.add("Aggregate " + aggregate.name() + ": onValidate names a procedure not found: " + onValidate);
            return;
        }
        if (callsProcedure(procedure.steps(), normalized)) {
            errors.add("Aggregate " + aggregate.name() + ": onValidate procedure " + onValidate
                    + " directly calls itself (recursive onValidate is not allowed)");
        }
    }

    private static boolean callsProcedure(List<ProcedureStepAst> steps, String targetNameNormalized) {
        for (ProcedureStepAst step : steps) {
            String type = normalize(step.type());
            if (("callprocedure".equals(type) || "procedurecall".equals(type))
                    && normalize(step.procedure()).equals(targetNameNormalized)) {
                return true;
            }
            if (callsProcedure(step.thenSteps(), targetNameNormalized)
                    || callsProcedure(step.elseSteps(), targetNameNormalized)
                    || callsProcedure(step.steps(), targetNameNormalized)) {
                return true;
            }
        }
        return false;
    }

    private static void validateAggregateCollections(
            String aggregateName,
            String path,
            List<AggregateCollectionAst> collections,
            Map<String, ConceptAst> entitiesByLower,
            Map<String, QueryAst> queriesByLower,
            Set<String> conceptChain,
            List<String> errors) {
        Set<String> siblingNames = new HashSet<>();
        for (AggregateCollectionAst collection : collections) {
            String here = path + " collection " + collection.name();
            if (!siblingNames.add(normalize(collection.name()))) {
                errors.add(here + ": duplicate collection name among siblings");
            }
            if (!hasText(collection.childField())) {
                errors.add(here + ": childField is required");
            }
            String normalizedConcept = normalize(collection.concept());
            if (!hasText(collection.concept())) {
                errors.add(here + ": concept is required");
            } else if (!entitiesByLower.containsKey(normalizedConcept)) {
                errors.add(here + ": concept not found: " + collection.concept());
            }
            if (hasText(collection.ownership())
                    && !normalize(collection.ownership()).equals("owned")
                    && !normalize(collection.ownership()).equals("referenced")) {
                errors.add(here + ": ownership must be 'owned' or 'referenced', found: " + collection.ownership());
            }
            validateAggregateCollectionLookupFields(here, collection.lookupFields(), queriesByLower, errors);
            // Guard against an owned composition cycle (a concept owning an ancestor concept).
            boolean owned = !hasText(collection.ownership()) || normalize(collection.ownership()).equals("owned");
            if (owned && hasText(collection.concept()) && conceptChain.contains(normalizedConcept)) {
                errors.add(here + ": owned composition cycle detected on concept " + collection.concept());
                continue;
            }
            Set<String> nextChain = new HashSet<>(conceptChain);
            if (hasText(collection.concept())) {
                nextChain.add(normalizedConcept);
            }
            validateAggregateCollections(aggregateName, here, collection.collections(),
                    entitiesByLower, queriesByLower, nextChain, errors);
        }
    }

    /**
     * Session 1: {@code query} must name a declared query -- the same class of reference check
     * every other procedure/query name gets elsewhere in this validator family. {@code joinField}/
     * {@code valueField} are checked for presence only (not cross-referenced against the query's or
     * concept's actual field list), matching this file's existing precedent that an invariant
     * expression's collection/field references are not statically checked against the runtime draft
     * shape either (see {@link #validateAggregateInvariants}'s javadoc) -- the deeper check would
     * need to resolve the query's bound concept's field list, which is more machinery than this
     * bounded feature needs.
     */
    private static void validateAggregateCollectionLookupFields(
            String collectionPath,
            List<AggregateCollectionLookupFieldAst> lookupFields,
            Map<String, QueryAst> queriesByLower,
            List<String> errors) {
        Set<String> seenNames = new HashSet<>();
        for (AggregateCollectionLookupFieldAst lookupField : lookupFields) {
            String here = collectionPath + " lookupField";
            if (!hasText(lookupField.name())) {
                errors.add(here + ": name is required");
            } else {
                here = collectionPath + " lookupField '" + lookupField.name() + "'";
                if (!seenNames.add(normalize(lookupField.name()))) {
                    errors.add(here + ": duplicate lookupField name within this collection");
                }
            }
            if (!hasText(lookupField.query())) {
                errors.add(here + ": query is required");
            } else if (!queriesByLower.containsKey(normalize(lookupField.query()))) {
                errors.add(here + ": query not found: " + lookupField.query());
            }
            if (!hasText(lookupField.joinField())) {
                errors.add(here + ": joinField is required");
            }
            if (!hasText(lookupField.valueField())) {
                errors.add(here + ": valueField is required");
            }
        }
    }

    /**
     * P6.1 (docs/NEXT_EXECUTION_PLAN.md): normalized concept name -&gt; owning aggregate name, for
     * every aggregate's root concept plus every OWNED (not {@code referenced}) collection concept,
     * recursively. A {@code referenced} collection is a normal cross-aggregate pointer in the DDD
     * sense (an aggregate may reference another aggregate's root by id without owning it), so it is
     * deliberately excluded -- only ownership defines a consistency boundary. Used by
     * {@link FlowValidation} to check a flow does not write across two aggregates' boundaries.
     *
     * <p>If two aggregates both (incorrectly) claim ownership of the same concept, the later
     * aggregate in declaration order wins the mapping -- a modeling error this method does not
     * itself flag; {@link #validateAggregates} is the place such a conflict would need its own check.
     */
    static Map<String, String> ownedConceptToAggregate(ModelAst modelAst) {
        Map<String, String> byConcept = new LinkedHashMap<>();
        for (AggregateAst aggregate : modelAst.getAggregates()) {
            if (hasText(aggregate.root())) {
                byConcept.put(normalize(aggregate.root()), aggregate.name());
            }
            collectOwnedConcepts(aggregate.name(), aggregate.collections(), byConcept);
        }
        return byConcept;
    }

    private static void collectOwnedConcepts(
            String aggregateName, List<AggregateCollectionAst> collections, Map<String, String> byConcept) {
        for (AggregateCollectionAst collection : collections) {
            boolean owned = !hasText(collection.ownership()) || normalize(collection.ownership()).equals("owned");
            if (owned && hasText(collection.concept())) {
                byConcept.put(normalize(collection.concept()), aggregateName);
            }
            collectOwnedConcepts(aggregateName, collection.collections(), byConcept);
        }
    }

}
