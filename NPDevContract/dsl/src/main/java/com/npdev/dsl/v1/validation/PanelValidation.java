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
import com.npdev.dsl.v1.ast.AggregateFunctionAst;
import com.npdev.dsl.v1.ast.GroupByFieldAst;
import com.npdev.dsl.v1.ast.AutoPanelAst;
import com.npdev.dsl.v1.ast.AutoPanelComputedAst;
import com.npdev.dsl.v1.ast.AutoPanelSurfaceAst;
import com.npdev.dsl.v1.ast.SelectorAst;
import com.npdev.dsl.v1.ast.RegionMountAst;
import com.npdev.dsl.v1.ast.WorkbenchActionAst;
import com.npdev.dsl.v1.ast.WorkbenchBandPickerAst;
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
import com.npdev.dsl.v1.ast.UiStateControlAst;
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
        Map<String, AggregateAst> aggregatesByNormalizedName = modelAst.getAggregates().stream()
                .collect(Collectors.toMap(a -> normalize(a.name()), a -> a, (first, second) -> first));
        Set<String> aggregateNames = aggregatesByNormalizedName.keySet();
        Set<String> procedureNames = modelAst.getProcedures().stream()
                .map(ProcedureAst::name)
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
            if (hasText(autoPanel.route()) && !autoPanel.route().startsWith("/")) {
                errors.add(here + ": route must start with '/': " + autoPanel.route());
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
            validateSelectionDataSourceProcedure(here, autoPanel.selection(), procedureNames, errors);

            if (hasAggregate) {
                AggregateAst aggregate = aggregatesByNormalizedName.get(normalize(autoPanel.aggregate()));
                validateRegions(here, autoPanel, aggregate, errors);
                validateWorkbenchActions(here, autoPanel, procedureNames, errors);
                validateVisibleWhen(here, autoPanel, aggregate, entitiesByLower, errors);
                validateBandPickers(here, autoPanel, aggregate, errors);
            }
        }
    }

    /**
     * Move 8 D3 (item G6, docs/MOVE8_CLOSE_TABLE_SPEC.md / Move 6 §B.7): {@code
     * selection.dataSource.procedure} REPLACES the generated Selection surface's row source with a
     * procedure's output instead of the bound concept's table -- the {@code produce} disposition
     * {@code PanelRuntime} already executes for hand-authored panels. Only meaningful on
     * {@code selection} (the surface {@link AutoPanelExpander#expand} wires it into today); the
     * declared name must resolve to a real procedure, same class of check every other procedure
     * reference on an AutoPanel already gets.
     */
    private static void validateSelectionDataSourceProcedure(
            String panelLabel, AutoPanelSurfaceAst selection, Set<String> procedureNames, List<String> errors) {
        if (selection == null || selection.dataSource() == null) {
            return;
        }
        String procedure = selection.dataSource().procedure();
        if (!hasText(procedure)) {
            errors.add(panelLabel + " selection.dataSource: procedure is required when dataSource is declared");
        } else if (!procedureNames.contains(normalize(procedure))) {
            errors.add(panelLabel + " selection.dataSource: procedure not found: " + procedure);
        }
    }

    /**
     * Move 7 W1 (docs/MOVE7_IMPLEMENTATION_SPEC.md): typed replacement for
     * {@code transaction.metadata.actions} -- {@code procedure} and (when declared) {@code
     * afterAction} must both name a real declared procedure, same as {@code panelAction.procedure}
     * and dataSource {@code onRowLoad} are already checked in {@link #validatePanels}.
     */
    private static void validateWorkbenchActions(
            String panelLabel, AutoPanelAst autoPanel, Set<String> procedureNames, List<String> errors) {
        AutoPanelSurfaceAst transaction = autoPanel.transaction();
        if (transaction == null || transaction.actions().isEmpty()) {
            return;
        }
        for (WorkbenchActionAst action : transaction.actions()) {
            if (!hasText(action.procedure())) {
                errors.add(panelLabel + " transaction.actions: an action is missing procedure");
            } else if (!procedureNames.contains(normalize(action.procedure()))) {
                errors.add(panelLabel + " transaction.actions: procedure not found: " + action.procedure());
            }
            if (hasText(action.afterAction()) && !procedureNames.contains(normalize(action.afterAction()))) {
                errors.add(panelLabel + " transaction.actions: afterAction names a procedure not found: "
                        + action.afterAction());
            }
        }
    }

    /**
     * Move 7 W1: typed replacement for {@code transaction.metadata.visibleWhen} -- reuses the same
     * derived-address universe {@link #validateRegions} validates {@code transaction.regions}
     * against ("header", a declared collection name, or a declared "&lt;collection&gt;.&lt;band&gt;"
     * pair), since both key off the same aggregate composition tree.
     */
    private static void validateVisibleWhen(
            String panelLabel, AutoPanelAst autoPanel, AggregateAst aggregate,
            Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        AutoPanelSurfaceAst transaction = autoPanel.transaction();
        if (transaction == null || transaction.visibleWhen().isEmpty() || aggregate == null) {
            return;
        }
        Set<String> validAddresses = derivedAddresses(aggregate);
        for (String address : transaction.visibleWhen().keySet()) {
            if (!validAddresses.contains(normalize(address))) {
                errors.add(panelLabel + " transaction.visibleWhen: unrecognized address '" + address
                        + "' -- must be \"header\", a declared collection name, or a declared "
                        + "\"<collection>.<band>\" pair");
            }
        }
        ConceptAst rootConcept = aggregate.root() == null
                ? null : entitiesByLower.get(normalize(aggregate.root()));
        for (Map.Entry<String, String> entry : transaction.visibleWhen().entrySet()) {
            String label = panelLabel + " transaction.visibleWhen['" + entry.getKey() + "']";
            validateUiStateReference(label, entry.getValue(), transaction, errors);
            validateRootFieldReference(label, entry.getValue(), rootConcept, errors);
        }
    }

    /**
     * Move 11 W6 (C1): a {@code $ui.<name>} predicate must name a DECLARED
     * {@code transaction.uiState} control, and compare against one of that control's declared
     * values. Without this, a typo'd toggle name is silently unsatisfiable: {@code evaluateVisibleWhen}
     * fails OPEN (the surface just stays visible), so the author sees a toggle that does nothing and
     * no error anywhere -- the same silent-nothing failure mode the untyped metadata keys had before
     * Move 7 gave them typed replacements.
     *
     * <p>Anything that is not a {@code $ui.} predicate is left alone here: {@code $root.<field>}
     * predicates are validated separately by {@link #validateRootFieldReference}.
     */
    private static void validateUiStateReference(
            String label, String expression, AutoPanelSurfaceAst transaction, List<String> errors) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        Matcher matcher = UI_STATE_PREDICATE.matcher(expression.trim());
        if (!matcher.matches()) {
            return;
        }
        String name = matcher.group(1);
        String literal = matcher.group(3);
        UiStateControlAst control = transaction.uiState().get(name);
        if (control == null) {
            errors.add(label + ": predicate references $ui." + name
                    + ", which is not declared in transaction.uiState (declared: "
                    + (transaction.uiState().isEmpty() ? "none" : transaction.uiState().keySet()) + ")");
            return;
        }
        if (!control.values().isEmpty() && !control.values().contains(literal)) {
            errors.add(label + ": predicate compares $ui." + name + " against '" + literal
                    + "', which is not one of its declared values " + control.values()
                    + " -- this predicate can never be true");
        }
    }

    /** The SAME grammar visibleWhen already carries, with {@code ui} as the root instead of {@code root}. */
    private static final Pattern UI_STATE_PREDICATE =
            Pattern.compile("^\\$?ui\\.([A-Za-z_][A-Za-z0-9_]*)\\s*(==|!=)\\s*'([^']*)'$");

    /**
     * Move 12 P1.3 (item 1 / REG-100 X0-8): the near-copy of {@link #validateUiStateReference} the
     * ledger item asked for -- a {@code $root.<field>} predicate must name a field declared on the
     * aggregate's root concept. {@code evaluateVisibleWhen} fails OPEN by design (a hidden surface
     * whose rows still commit is the worse failure than a wrongly-visible one), and that is only a
     * safe default if a wrong predicate is caught here, at authoring time -- otherwise a typo like
     * {@code $root.tpio == 'X'} validates clean and then silently shows everything forever, which is
     * exactly the failure {@code $ui.<name>} was closed against in Move 11 W6. Unlike {@code $ui},
     * there is no fixed value set to check literals against -- root concept fields are typed, open
     * domains -- so this only checks that the field itself is declared, not the literal.
     */
    private static void validateRootFieldReference(
            String label, String expression, ConceptAst rootConcept, List<String> errors) {
        if (expression == null || expression.isBlank() || rootConcept == null) {
            return;
        }
        Matcher matcher = ROOT_FIELD_PREDICATE.matcher(expression.trim());
        if (!matcher.matches()) {
            return;
        }
        String name = matcher.group(1);
        Set<String> fieldNames = rootConcept.getFields().stream()
                .map(FieldAst::getName)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        if (!fieldNames.contains(normalize(name))) {
            errors.add(label + ": predicate references $root." + name
                    + ", which is not a declared field on root concept " + rootConcept.getName()
                    + " (declared: " + (fieldNames.isEmpty() ? "none" : new TreeSet<>(fieldNames)) + ")");
        }
    }

    /** The SAME grammar visibleWhen already carries, with {@code root} as the root instead of {@code ui}. */
    private static final Pattern ROOT_FIELD_PREDICATE =
            Pattern.compile("^\\$?root\\.([A-Za-z_][A-Za-z0-9_]*)\\s*(==|!=)\\s*'([^']*)'$");

    /**
     * Move 7 W1: typed replacement for {@code transaction.metadata.bandPickers} -- keys must name a
     * real declared band (a nested collection one level under a top-level collection). {@code panel}
     * is an opaque reference to an authored Selection surface with no closed universe to validate its
     * VALUE against, but its PRESENCE is required (B19, docs/ACCEPTED_BOUNDARIES.md): without a
     * panel, a {@code filter}/{@code multiSelect} declared on the picker has nothing to apply to and
     * would be silently inert at runtime -- {@link WorkbenchBandPickerAst}'s own class javadoc floats
     * a future "targets its own collection's concept directly" no-panel path, but that path was never
     * built, so this refuses up front instead. The B19-prefixed message is the boundaryId link
     * {@code ValidationDiagnosticNormalizer}'s {@code BOUNDARY_PREFIX_IDS} map strips before
     * pattern-matching -- same convention {@code B1}/{@code B13} already use.
     */
    private static void validateBandPickers(
            String panelLabel, AutoPanelAst autoPanel, AggregateAst aggregate, List<String> errors) {
        AutoPanelSurfaceAst transaction = autoPanel.transaction();
        if (transaction == null || transaction.bandPickers().isEmpty() || aggregate == null) {
            return;
        }
        Set<String> bandNames = new HashSet<>();
        for (AggregateCollectionAst collection : aggregate.collections()) {
            for (AggregateCollectionAst band : collection.collections()) {
                bandNames.add(normalize(band.name()));
            }
        }
        for (Map.Entry<String, WorkbenchBandPickerAst> entry : transaction.bandPickers().entrySet()) {
            if (!bandNames.contains(normalize(entry.getKey()))) {
                errors.add(panelLabel + " transaction.bandPickers: unrecognized band '" + entry.getKey()
                        + "' -- must be a declared nested (band) collection name");
            } else if (!hasText(entry.getValue().panel())) {
                errors.add("B19:band_picker_requires_panel:" + panelLabel + " transaction.bandPickers."
                        + entry.getKey() + ": panel is required");
            }
        }
    }

    private static Set<String> derivedAddresses(AggregateAst aggregate) {
        Set<String> validAddresses = new HashSet<>();
        validAddresses.add("header");
        for (AggregateCollectionAst collection : aggregate.collections()) {
            validAddresses.add(normalize(collection.name()));
            for (AggregateCollectionAst band : collection.collections()) {
                validAddresses.add(normalize(collection.name()) + "." + normalize(band.name()));
            }
        }
        return validAddresses;
    }

    /**
     * Move 6 Move D (docs/MOVE6_TYPED_SURFACE_PLAN.md §5): a transaction.regions key must name a
     * REAL address derived from the aggregate's own composition tree ("header", a declared
     * top-level collection name, or a declared "<collection>.<band>" pair) -- an unrecognized
     * address (a typo, or a stale address left after a collection was renamed/removed) is rejected
     * rather than silently doing nothing. render:"component" must also declare a component name.
     */
    private static void validateRegions(
            String panelLabel, AutoPanelAst autoPanel, AggregateAst aggregate, List<String> errors) {
        AutoPanelSurfaceAst transaction = autoPanel.transaction();
        if (transaction == null || transaction.regions().isEmpty()) {
            return;
        }
        if (aggregate == null) {
            return; // the aggregate-not-found error above already covers this case
        }
        Set<String> validAddresses = derivedAddresses(aggregate);
        for (Map.Entry<String, RegionMountAst> entry : transaction.regions().entrySet()) {
            String address = entry.getKey();
            if (!validAddresses.contains(normalize(address))) {
                errors.add(panelLabel + " transaction.regions: unrecognized region address '" + address
                        + "' -- must be \"header\", a declared collection name, or a declared "
                        + "\"<collection>.<band>\" pair");
                continue;
            }
            RegionMountAst region = entry.getValue();
            if ("component".equals(region.render()) && !hasText(region.component())) {
                errors.add(panelLabel + " transaction.regions." + address
                        + ": render is \"component\" but no component name is declared"
                        + " -- suggestedFix: declare component with the component's name on this region, "
                        + "or change render away from \"component\" to a render mode that needs no name");
            }
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
        // Move 6 Move B (docs/MOVE6_TYPED_SURFACE_PLAN.md §B.5): transaction.metadata.recompute and
        // .derived are retired in favor of the typed transaction.hooks.onFieldChange /
        // .derivedFields -- both still work (one release of dual support), but warn so authors
        // migrate rather than discover this silently later. Run `npdev migrate dsl-2` to rewrite
        // existing usages automatically.
        if (surfaceAst.metadata().get("recompute") != null) {
            warnings.add(panelLabel + " " + surface + ": transaction.metadata.recompute is deprecated -- "
                    + "use transaction.hooks.onFieldChange instead (run `npdev migrate dsl-2` to rewrite it).");
        }
        if (surfaceAst.metadata().get("derived") != null) {
            warnings.add(panelLabel + " " + surface + ": transaction.metadata.derived is deprecated -- "
                    + "use transaction.derivedFields instead (run `npdev migrate dsl-2` to rewrite it).");
        }
        // Move 8 D2 (item G4, docs/MOVE8_CLOSE_TABLE_SPEC.md): the remaining four untyped
        // metadata keys Move 6/7 gave typed replacements to (transaction.actions, .visibleWhen,
        // .bandPickers, and the surface-level computed[] field) had no deprecation signal at all --
        // an author's old key would silently do nothing instead of warning them to migrate.
        if (surfaceAst.metadata().get("computed") != null) {
            warnings.add(panelLabel + " " + surface + ": transaction.metadata.computed is deprecated -- "
                    + "use the surface's own computed[] field instead (run `npdev migrate dsl-2` to rewrite it).");
        }
        if (surfaceAst.metadata().get("actions") != null) {
            warnings.add(panelLabel + " " + surface + ": transaction.metadata.actions is deprecated -- "
                    + "use transaction.actions instead (run `npdev migrate dsl-2` to rewrite it).");
        }
        if (surfaceAst.metadata().get("visibleWhen") != null) {
            warnings.add(panelLabel + " " + surface + ": transaction.metadata.visibleWhen is deprecated -- "
                    + "use transaction.visibleWhen instead (run `npdev migrate dsl-2` to rewrite it).");
        }
        if (surfaceAst.metadata().get("bandPickers") != null) {
            warnings.add(panelLabel + " " + surface + ": transaction.metadata.bandPickers is deprecated -- "
                    + "use transaction.bandPickers instead (run `npdev migrate dsl-2` to rewrite it).");
        }
    }

    private static String recomputeProcedureName(AutoPanelSurfaceAst surfaceAst) {
        // Move 6 Move B: transaction.hooks.onFieldChange is the typed spelling of the retired
        // transaction.metadata.recompute (docs/MOVE6_TYPED_SURFACE_PLAN.md §B.4).
        if (surfaceAst.hooks() != null && hasText(surfaceAst.hooks().onFieldChange())) {
            return surfaceAst.hooks().onFieldChange().trim();
        }
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
            if (hasText(panel.route()) && !panel.route().startsWith("/")) {
                errors.add("Panel " + panel.name() + ": route must start with '/': " + panel.route());
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
                // Move 6 Move C (docs/MOVE6_TYPED_SURFACE_PLAN.md §4): onRowLoad enriches rows this
                // data source produced -- distinct from `procedure` above, which replaces the row
                // source entirely; a data source may declare both, or either alone.
                if (hasText(dataSource.onRowLoad()) && !procedureNames.contains(normalize(dataSource.onRowLoad()))) {
                    errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                            + ": onRowLoad names a procedure not found: " + dataSource.onRowLoad());
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
                validateAddFormFields(panel, dataSource, entitiesByLower, errors);
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
                validatePanelActionResultAs(panel, action, binding, errors);
            }
        }
    }

    /**
     * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 4 / Gap 7): {@code resultAs: "download"} is
     * only meaningful on a {@code procedure}-binding action (its own return value IS the file
     * content) and needs both {@code filename} and {@code contentType} declared alongside it --
     * PanelRuntime has no default to fall back on for either.
     */
    private static void validatePanelActionResultAs(
            PanelAst panel, PanelActionAst action, String binding, List<String> errors) {
        if (!hasText(action.resultAs())) {
            return;
        }
        if (!binding.equals("procedure")) {
            errors.add("Panel " + panel.name() + " action " + action.name()
                    + ": resultAs is only supported on a procedure-binding action");
            return;
        }
        if (!hasText(action.filename())) {
            errors.add("Panel " + panel.name() + " action " + action.name()
                    + ": resultAs \"download\" requires filename");
        }
        if (!hasText(action.contentType())) {
            errors.add("Panel " + panel.name() + " action " + action.name()
                    + ": resultAs \"download\" requires contentType");
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
    }

    /**
     * REG-185: {@code addFormFields} names fields of the dataSource's concept, and a name that does
     * not exist there is wrong whether or not the dataSource also declares {@code rowOps}.
     *
     * <p>This check used to live INSIDE {@link #validatePanelRowOps}, after its
     * `rowOps == null -> return` guard, so it only ever ran for a dataSource that opted into row
     * mutation. The plan that produced REG-185 recorded `panelDataSource.addFormFields` as one of
     * the sites that DID error -- true, but only in the shape its probe happened to use. Measured
     * 2026-08-17: with `rowOps` absent, a ghost `addFormFields` entry produced zero diagnostics.
     * Hoisted here so the one message comes from one place -- {@code ReferenceIntegrityValidation}
     * excludes this site precisely because this check owns it, and an exclusion covering a check
     * that does not always run is a hole, not a de-duplication.
     */
    private static void validateAddFormFields(
            PanelAst panel,
            PanelDataSourceAst dataSource,
            Map<String, ConceptAst> entitiesByLower,
            List<String> errors
    ) {
        if (dataSource.addFormFields() == null || dataSource.addFormFields().isEmpty()) {
            return;
        }
        ConceptAst concept = entitiesByLower.get(normalize(dataSource.concept()));
        if (concept == null) {
            // The dataSource has no resolvable concept (query/procedure-bound, or a concept that
            // does not exist). Both are reported by their own checks; blaming the fields here would
            // report one mistake twice under two names.
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

    /** Move 10 B2 (LC-B2): the chart/KPI gadget types -- bind to a named query, per {@link
     *  #validateGuidePageGadgetQueryBinding}. The pre-existing rail types ({@code recent-items},
     *  {@code context-info}, {@code page-fragment}) need no query and are left untouched. */
    private static final Set<String> QUERY_BOUND_GADGET_TYPES = Set.of("kpi", "bar", "line", "table");
    /** {@code x}/{@code series} need a categorical axis, so only these two chart shapes require it. */
    private static final Set<String> AXIS_GADGET_TYPES = Set.of("bar", "line");

    static void validateGuidePages(ModelAst modelAst, List<String> errors) {
        Map<String, QueryAst> queriesByName = new HashMap<>();
        for (QueryAst query : modelAst.getQueries()) {
            queriesByName.put(normalize(query.name()), query);
        }

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
                    continue;
                }
                if (QUERY_BOUND_GADGET_TYPES.contains(normalize(gadget.type()))) {
                    validateGuidePageGadgetQueryBinding(guidePage.name(), gadget, queriesByName, errors);
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

    /**
     * Move 10 B2 (LC-B2, MOVE10_AI_LOWCODE_PLAN Part B): a chart/KPI gadget ({@code kpi}/
     * {@code bar}/{@code line}/{@code table}) must bind to a named, EXISTING aggregate query --
     * "the query must exist, must declare groupBy, and x/y/series must name one of its groupBy
     * fields or aggregates outputs" (the plan's own "How" item 3). "A dashboard that validates and
     * renders empty is the failure mode to design against" -- refusing at compile time here is what
     * makes that impossible: an AI author gets a named error instead of a live blank chart.
     *
     * <p>Deviation from the plan's literal wording: {@code kpi} does NOT require {@code groupBy}.
     * B1 itself designed the zero-{@code groupBy} shape specifically for "a single KPI total" (see
     * {@code ConceptAggregateEngine}'s own javadoc) -- requiring {@code groupBy} on every gadget
     * type would make that shape unusable by the one gadget type it exists for. {@code groupBy} is
     * required only for {@code bar}/{@code line} (they need a categorical/bucketed x-axis);
     * {@code table} renders whatever columns the query produces either way.
     */
    private static void validateGuidePageGadgetQueryBinding(
            String guidePageName, GuidePageGadgetAst gadget, Map<String, QueryAst> queriesByName, List<String> errors) {
        String here = "GuidePage " + guidePageName + " gadget " + gadget.name();
        if (!hasText(gadget.query())) {
            errors.add(here + " (type=" + gadget.type() + "): query-bound gadgets must declare a query");
            return;
        }
        QueryAst query = queriesByName.get(normalize(gadget.query()));
        if (query == null) {
            errors.add(here + ": query not found: " + gadget.query());
            return;
        }
        if (!query.isAggregate()) {
            errors.add(here + ": query " + query.name()
                    + " has no groupBy/aggregates -- gadgets require an aggregate query (see Move 10 B1)");
            return;
        }
        String type = normalize(gadget.type());
        if (AXIS_GADGET_TYPES.contains(type) && query.groupBy().isEmpty()) {
            errors.add(here + ": query " + query.name() + " has no groupBy -- " + gadget.type()
                    + " gadgets need a categorical/bucketed axis");
        }

        Set<String> groupByFieldNames = new HashSet<>();
        for (GroupByFieldAst groupByField : query.groupBy()) {
            groupByFieldNames.add(normalize(groupByField.field()));
        }
        Set<String> aggregateOutputNames = new HashSet<>();
        for (AggregateFunctionAst aggregate : query.aggregates()) {
            aggregateOutputNames.add(normalize(aggregate.name()));
        }

        if ("kpi".equals(type)) {
            requireAggregateOutput(here, "y", gadget.y(), query, aggregateOutputNames, errors);
        } else if (AXIS_GADGET_TYPES.contains(type)) {
            requireGroupByField(here, "x", gadget.x(), query, groupByFieldNames, errors);
            requireAggregateOutput(here, "y", gadget.y(), query, aggregateOutputNames, errors);
            if (hasText(gadget.series()) && !groupByFieldNames.contains(normalize(gadget.series()))) {
                errors.add(here + ": series \"" + gadget.series() + "\" does not name a groupBy field of query "
                        + query.name());
            }
        }
        // "table" renders whatever columns the query produces (groupBy fields + aggregate
        // outputs) -- x/y/series are not required and, if absent, are simply ignored.
    }

    private static void requireGroupByField(
            String here, String axisName, String value, QueryAst query, Set<String> groupByFieldNames, List<String> errors) {
        if (!hasText(value)) {
            errors.add(here + ": " + axisName + " is required for this gadget type");
        } else if (!groupByFieldNames.contains(normalize(value))) {
            errors.add(here + ": " + axisName + " \"" + value + "\" does not name a groupBy field of query "
                    + query.name());
        }
    }

    private static void requireAggregateOutput(
            String here, String axisName, String value, QueryAst query, Set<String> aggregateOutputNames, List<String> errors) {
        if (!hasText(value)) {
            errors.add(here + ": " + axisName + " is required for this gadget type");
        } else if (!aggregateOutputNames.contains(normalize(value))) {
            errors.add(here + ": " + axisName + " \"" + value + "\" does not name an aggregates output of query "
                    + query.name());
        }
    }

}
