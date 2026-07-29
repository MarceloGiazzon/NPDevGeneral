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
 * Semantic validation for panels, dataSource row-ops, guide pages, auto-panels, and selectors --
 * the panel/surface-definition cluster of {@code model.schema.json}.
 *
 * <p>Split out of {@code SemanticValidator} (T1.15). The presentation/UX-metadata diagnostics
 * that live under {@code concepts[].ui} / {@code concepts[].fields[].ui} are a separate,
 * similarly-sized sub-boundary -- see {@link UxMetadataValidation}.
 */
final class PanelValidation {

    private PanelValidation() {
    }

    private static final Set<String> PANEL_ACTION_BINDINGS =
            Set.of("conceptquery", "conceptmutation", "procedure", "flow");

    static void validateSelectors(ModelAst modelAst, Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        Set<String> selectorNames = new HashSet<>();
        for (SelectorAst selector : modelAst.getSelectors()) {
            String here = "Selector " + selector.name();
            if (!selectorNames.add(normalize(selector.name()))) {
                errors.add(here + ": duplicate selector name");
            }
            if (!hasText(selector.concept())) {
                errors.add(here + ": concept is required");
            } else if (!entitiesByLower.containsKey(normalize(selector.concept()))) {
                errors.add(here + ": concept not found: " + selector.concept());
            }
        }
    }

    static void validateAutoPanels(
            ModelAst modelAst, Map<String, ConceptAst> entitiesByLower, List<String> errors, List<String> warnings) {
        Set<String> aggregateNames = modelAst.getAggregates().stream()
                .map(AggregateAst::name)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        Set<String> autoPanelNames = new HashSet<>();
        for (AutoPanelAst autoPanel : modelAst.getAutoPanels()) {
            String label = hasText(autoPanel.name()) ? autoPanel.name()
                    : firstNonBlankBinding(autoPanel);
            String here = "AutoPanel " + label;

            if (hasText(autoPanel.name()) && !autoPanelNames.add(normalize(autoPanel.name()))) {
                errors.add(here + ": duplicate autoPanel name");
            }

            boolean hasConcept = hasText(autoPanel.concept());
            boolean hasAggregate = hasText(autoPanel.aggregate());
            if (hasConcept == hasAggregate) {
                errors.add(here + ": exactly one of concept or aggregate must be declared");
            }
            if (hasConcept && !entitiesByLower.containsKey(normalize(autoPanel.concept()))) {
                errors.add(here + ": concept not found: " + autoPanel.concept());
            }
            if (hasAggregate && !aggregateNames.contains(normalize(autoPanel.aggregate()))) {
                errors.add(here + ": aggregate not found: " + autoPanel.aggregate());
            }

            for (String surface : autoPanel.surfaces()) {
                String normalizedSurface = normalize(surface);
                if (!normalizedSurface.equals("selection")
                        && !normalizedSurface.equals("detail")
                        && !normalizedSurface.equals("transaction")
                        && !normalizedSurface.equals("prompt")) {
                    errors.add(here + ": unknown surface: " + surface);
                }
            }

            validateSurfaceComputed(here, "selection", autoPanel.selection(), errors, warnings);
            validateSurfaceComputed(here, "detail", autoPanel.detail(), errors, warnings);
            validateSurfaceComputed(here, "transaction", autoPanel.transaction(), errors, warnings);
            validateSurfaceComputed(here, "prompt", autoPanel.prompt(), errors, warnings);
        }
    }

    private static void validateSurfaceComputed(
            String panelLabel, String surface, AutoPanelSurfaceAst surfaceAst, List<String> errors, List<String> warnings) {
        if (surfaceAst == null) {
            return;
        }
        Set<String> cols = new HashSet<>();
        for (AutoPanelComputedAst computed : surfaceAst.computed()) {
            if (!cols.add(normalize(computed.col()))) {
                errors.add(panelLabel + " " + surface + ": duplicate computed column: " + computed.col());
            }
            try {
                ComputedExpression.validate(computed.expr());
            } catch (ComputedExpression.ExpressionException ex) {
                errors.add(panelLabel + " " + surface + " computed column " + computed.col()
                        + ": invalid expression: " + ex.getMessage());
            }
        }
        // AW-P3: computed[] is compiled into the workbench descriptor's metadata for introspection,
        // but no client evaluator reads it -- the live keystroke-recompute UX is delivered entirely
        // by transaction.metadata.recompute (a server-round-trip procedure). Warn rather than let a
        // declared computed[] silently do nothing on this surface, without blocking authoring.
        if (!surfaceAst.computed().isEmpty() && !hasText(recomputeProcedureName(surfaceAst))) {
            warnings.add(panelLabel + " " + surface + ": declares computed[] but no "
                    + "transaction.metadata.recompute procedure -- computed[] stays panel metadata "
                    + "only and will NOT recompute live in the generated page unless a recompute "
                    + "procedure is also declared.");
        }
    }

    private static String recomputeProcedureName(AutoPanelSurfaceAst surfaceAst) {
        Object declared = surfaceAst.metadata().get("recompute");
        if (declared instanceof Map<?, ?> map) {
            declared = map.get("procedure");
        }
        return declared == null ? null : String.valueOf(declared).trim();
    }

    private static String firstNonBlankBinding(AutoPanelAst autoPanel) {
        if (hasText(autoPanel.concept())) {
            return autoPanel.concept();
        }
        if (hasText(autoPanel.aggregate())) {
            return autoPanel.aggregate();
        }
        return "(unbound)";
    }

    static void validatePanels(ModelAst modelAst, Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        Set<String> queryNames = modelAst.getQueries().stream()
                .map(QueryAst::name)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        Set<String> procedureNames = modelAst.getProcedures().stream()
                .map(ProcedureAst::name)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        Set<String> flowNames = modelAst.getFlows().stream()
                .map(FlowAst::getName)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        Set<String> panelNames = new HashSet<>();
        Set<String> panelRoutes = new HashSet<>();

        for (PanelAst panel : modelAst.getPanels()) {
            if (!panelNames.add(normalize(panel.name()))) {
                errors.add("Panel " + panel.name() + ": duplicate panel name");
            }
            if (!panelRoutes.add(normalize(panel.route()))) {
                errors.add("Panel " + panel.name() + ": duplicate panel route " + panel.route());
            }
            for (PanelDataSourceAst dataSource : panel.dataSources()) {
                if (hasText(dataSource.concept()) && !entitiesByLower.containsKey(normalize(dataSource.concept()))) {
                    errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                            + ": concept not found: " + dataSource.concept());
                }
                if (hasText(dataSource.query()) && !queryNames.contains(normalize(dataSource.query()))) {
                    errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                            + ": query not found: " + dataSource.query());
                }
                if (hasText(dataSource.procedure()) && !procedureNames.contains(normalize(dataSource.procedure()))) {
                    errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                            + ": procedure not found: " + dataSource.procedure());
                }
                if (hasText(dataSource.parentDataSource())) {
                    if (normalize(dataSource.parentDataSource()).equals(normalize(dataSource.name()))) {
                        errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                                + ": cannot declare itself as parentDataSource");
                    }
                    Optional<PanelDataSourceAst> parent = panel.dataSources().stream()
                            .filter(candidate -> normalize(candidate.name()).equals(normalize(dataSource.parentDataSource())))
                            .findFirst();
                    if (parent.isEmpty()) {
                        errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                                + ": parentDataSource not found among sibling dataSources: " + dataSource.parentDataSource());
                    } else if (hasText(parent.get().parentDataSource())) {
                        errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                                + ": nesting is limited to one level (parentDataSource " + dataSource.parentDataSource()
                                + " is itself a child dataSource)");
                    }
                    if (!hasText(dataSource.childField())) {
                        errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                                + ": childField is required when parentDataSource is declared");
                    }
                    if (hasText(dataSource.procedure())) {
                        errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                                + ": a child dataSource (parentDataSource declared) must be concept/query-bound, not procedure-bound");
                    }
                }
                validatePanelRowOps(panel, dataSource, entitiesByLower, errors);
            }
            for (PanelActionAst action : panel.actions()) {
                String binding = normalize(action.binding());
                if (!PANEL_ACTION_BINDINGS.contains(binding)) {
                    errors.add("Panel " + panel.name() + " action " + action.name()
                            + ": unsupported action binding " + action.binding());
                    continue;
                }
                if ((binding.equals("conceptquery") || binding.equals("conceptmutation"))
                        && !entitiesByLower.containsKey(normalize(action.concept()))) {
                    errors.add("Panel " + panel.name() + " action " + action.name()
                            + ": concept not found: " + action.concept());
                }
                if (binding.equals("conceptmutation") && !Set.of("create", "update", "delete").contains(normalize(action.operation()))) {
                    errors.add("Panel " + panel.name() + " action " + action.name()
                            + ": conceptMutation operation must be create, update, or delete");
                }
                if (binding.equals("procedure") && !procedureNames.contains(normalize(action.procedure()))) {
                    errors.add("Panel " + panel.name() + " action " + action.name()
                            + ": procedure not found: " + action.procedure());
                }
                if (binding.equals("flow") && !flowNames.contains(normalize(action.flow()))) {
                    errors.add("Panel " + panel.name() + " action " + action.name()
                            + ": flow not found: " + action.flow());
                }
                validatePanelActionScope(panel, action, errors);
            }
        }
    }

    /**
     * G2 (docs/MOVE2_PANEL_ACTIONS_PLAN.md): a {@code scope: "row"} action is rendered once per row
     * of a declared dataSource and invoked with that row's id -- it needs {@code dataSource} to name
     * which one. Default ({@code scope} absent or {@code "panel"}) needs nothing new, so every action
     * declared before this field existed validates unchanged.
     */
    private static void validatePanelActionScope(PanelAst panel, PanelActionAst action, List<String> errors) {
        String scope = hasText(action.scope()) ? normalize(action.scope()) : "panel";
        if (!scope.equals("panel") && !scope.equals("row")) {
            errors.add("Panel " + panel.name() + " action " + action.name()
                    + ": unsupported action scope " + action.scope() + " (must be panel or row)");
            return;
        }
        if (!scope.equals("row")) {
            return;
        }
        if (!hasText(action.dataSource())) {
            errors.add("Panel " + panel.name() + " action " + action.name()
                    + ": scope \"row\" requires dataSource");
            return;
        }
        boolean dataSourceExists = panel.dataSources().stream()
                .anyMatch(candidate -> normalize(candidate.name()).equals(normalize(action.dataSource())));
        if (!dataSourceExists) {
            errors.add("Panel " + panel.name() + " action " + action.name()
                    + ": dataSource not found among panel's dataSources: " + action.dataSource());
        }
    }

    private static final Set<String> PANEL_ROW_OPS = Set.of("add", "delete");

    /**
     * LIFT-ROWOPS-P1: a declared Panel dataSource may opt into {@code rowOps: [add, delete]} (an
     * optional header add-row form via {@code addFormFields}). Row mutation writes through the
     * generic CRUD gateway (LIFT-ROWOPS-P3), so it needs a concept target -- not a query/procedure
     * dataSource -- and, for a child (nested) dataSource, the parent-FK {@code childField} that the
     * existing nesting validation above already requires.
     */
    private static void validatePanelRowOps(
            PanelAst panel,
            PanelDataSourceAst dataSource,
            Map<String, ConceptAst> entitiesByLower,
            List<String> errors
    ) {
        if (dataSource.rowOps() == null || dataSource.rowOps().isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (String op : dataSource.rowOps()) {
            String normalizedOp = normalize(op);
            if (!PANEL_ROW_OPS.contains(normalizedOp)) {
                errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                        + ": unsupported rowOps value '" + op + "' (must be add or delete)");
            } else if (!seen.add(normalizedOp)) {
                errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                        + ": duplicate rowOps value '" + op + "'");
            }
        }
        if (!hasText(dataSource.concept())) {
            errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                    + ": rowOps requires a concept-bound dataSource (query/procedure dataSources can't be mutated)");
            return;
        }
        ConceptAst concept = entitiesByLower.get(normalize(dataSource.concept()));
        if (concept == null || dataSource.addFormFields() == null || dataSource.addFormFields().isEmpty()) {
            return;
        }
        Set<String> conceptFieldNames = concept.getFields().stream()
                .map(FieldAst::getName)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        for (String fieldName : dataSource.addFormFields()) {
            if (!conceptFieldNames.contains(normalize(fieldName))) {
                errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                        + ": addFormFields references unknown field " + fieldName + " on concept " + dataSource.concept());
            }
        }
    }

    static void validateGuidePages(ModelAst modelAst, List<String> errors) {
        Set<String> guidePageNames = new HashSet<>();
        boolean sawDefault = false;
        for (GuidePageAst guidePage : modelAst.getGuidePages()) {
            if (!guidePageNames.add(normalize(guidePage.name()))) {
                errors.add("GuidePage " + guidePage.name() + ": duplicate guide page name");
            }
            if (guidePage.isDefault()) {
                if (sawDefault) {
                    errors.add("GuidePage " + guidePage.name() + ": more than one guide page marked default");
                }
                sawDefault = true;
            }
            for (GuidePageGadgetAst gadget : guidePage.gadgets()) {
                if (!hasText(gadget.name())) {
                    errors.add("GuidePage " + guidePage.name() + ": gadget is missing a name");
                }
                if (!hasText(gadget.type())) {
                    errors.add("GuidePage " + guidePage.name() + " gadget " + gadget.name() + ": gadget is missing a type");
                }
            }
        }

        Set<String> knownGuidePageNames = new HashSet<>(guidePageNames);
        for (String builtinName : GuidePageDefaults.BUILTIN_NAMES) {
            knownGuidePageNames.add(normalize(builtinName));
        }
        for (PanelAst panel : modelAst.getPanels()) {
            if (hasText(panel.guidePage()) && !knownGuidePageNames.contains(normalize(panel.guidePage()))) {
                errors.add("Panel " + panel.name() + ": guidePage not found: " + panel.guidePage());
            }
        }
    }

}
