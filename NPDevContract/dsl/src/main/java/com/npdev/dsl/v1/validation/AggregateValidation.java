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
                    new HashSet<>(),
                    errors);
        }
    }

    private static void validateAggregateCollections(
            String aggregateName,
            String path,
            List<AggregateCollectionAst> collections,
            Map<String, ConceptAst> entitiesByLower,
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
                    entitiesByLower, nextChain, errors);
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
