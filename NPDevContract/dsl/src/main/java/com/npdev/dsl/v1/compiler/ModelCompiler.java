package com.npdev.dsl.v1.compiler;

import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.ContextAst;
import com.npdev.dsl.v1.ast.DocumentAst;
import com.npdev.dsl.v1.ast.ExternalAiAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.FieldPickerAst;
import com.npdev.dsl.v1.ast.FileMetadataAst;
import com.npdev.dsl.v1.ast.InvariantAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.OrchestrationAst;
import com.npdev.dsl.v1.ast.SettingsAst;
import com.npdev.dsl.v1.ast.TransactionHooksAst;
import com.npdev.dsl.v1.ast.DerivedFieldAst;
import com.npdev.dsl.v1.ast.RegionMountAst;
import com.npdev.dsl.v1.ast.UiStateControlAst;
import com.npdev.dsl.v1.ast.WorkbenchActionAst;
import com.npdev.dsl.v1.ast.WorkbenchActionApplyToAst;
import com.npdev.dsl.v1.ast.WorkbenchBandPickerAst;
import com.npdev.dsl.v1.ast.DomainTypeAst;
import com.npdev.dsl.v1.ast.CapabilityAst;
import com.npdev.dsl.v1.ast.CapabilityBindingAst;
import com.npdev.dsl.v1.ast.CapabilityOperationAst;
import com.npdev.dsl.v1.ast.CapabilityPolicyAst;
import com.npdev.dsl.v1.ast.ActionMetadataAst;
import com.npdev.dsl.v1.ast.DomainTypeUiAst;
import com.npdev.dsl.v1.ast.EventAst;
import com.npdev.dsl.v1.ast.EventPayloadAst;
import com.npdev.dsl.v1.ast.EnumOptionAst;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.FlowScheduleAst;
import com.npdev.dsl.v1.ast.GeneratedActionDescriptorAst;
import com.npdev.dsl.v1.ast.AggregateAst;
import com.npdev.dsl.v1.ast.AggregateCollectionAst;
import com.npdev.dsl.v1.ast.AutoPanelAst;
import com.npdev.dsl.v1.ast.AutoPanelComputedAst;
import com.npdev.dsl.v1.ast.AutoPanelDataSourceAst;
import com.npdev.dsl.v1.ast.AutoPanelSurfaceAst;
import com.npdev.dsl.v1.ast.SelectorAst;
import com.npdev.dsl.v1.ast.GuidePageAst;
import com.npdev.dsl.v1.ast.GuidePageGadgetAst;
import com.npdev.dsl.v1.ast.GuidePageRegionAst;
import com.npdev.dsl.v1.ast.GuidePageRegionsAst;
import com.npdev.dsl.v1.ast.GuidePageThemeAst;
import com.npdev.dsl.v1.ast.IndexAst;
import com.npdev.dsl.v1.ast.LifecycleAst;
import com.npdev.dsl.v1.ast.OrchestrationActionAst;
import com.npdev.dsl.v1.ast.SchemaAst;
import com.npdev.dsl.v1.ast.OrchestrationTriggerAst;
import com.npdev.dsl.v1.ast.PanelActionAst;
import com.npdev.dsl.v1.ast.PanelAst;
import com.npdev.dsl.v1.ast.PanelDataSourceAst;
import com.npdev.dsl.v1.ast.PanelFieldBindingAst;
import com.npdev.dsl.v1.ast.PanelLayoutAst;
import com.npdev.dsl.v1.ast.PresentationMetadataAst;
import com.npdev.dsl.v1.ast.ProcedureAst;
import com.npdev.dsl.v1.ast.ProcedureParameterAst;
import com.npdev.dsl.v1.ast.ProcedureStepAst;
import com.npdev.dsl.v1.ast.ProcedureVariableAst;
import com.npdev.dsl.v1.ast.QueryAst;
import com.npdev.dsl.v1.ast.ReferenceSemanticsAst;
import com.npdev.dsl.v1.ast.RuleProfileAst;
import com.npdev.dsl.v1.ast.StateMachineStateAst;
import com.npdev.dsl.v1.ast.StateTransitionAst;
import com.npdev.dsl.v1.ast.StepAst;
import com.npdev.dsl.v1.compiled.CompiledFileMetadata;
import com.npdev.dsl.v1.compiled.CompiledCapability;
import com.npdev.dsl.v1.compiled.CompiledCapabilityCall;
import com.npdev.dsl.v1.compiled.CompiledCapabilityBinding;
import com.npdev.dsl.v1.compiled.CompiledActionMetadata;
import com.npdev.dsl.v1.compiled.CompiledCapabilityExecutionPolicy;
import com.npdev.dsl.v1.compiled.CompiledCapabilityOperation;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledIndex;
import com.npdev.dsl.v1.compiled.CompiledConceptAccess;
import com.npdev.dsl.v1.compiled.CompiledDomainType;
import com.npdev.dsl.v1.compiled.CompiledDomainTypeUi;
import com.npdev.dsl.v1.compiled.CompiledEnumOption;
import com.npdev.dsl.v1.compiled.CompiledEvent;
import com.npdev.dsl.v1.compiled.CompiledEventField;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledFieldPicker;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowSchedule;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledGeneratedActionDescriptorSpec;
import com.npdev.dsl.v1.compiled.CompiledGuidePage;
import com.npdev.dsl.v1.compiled.CompiledGuidePageGadget;
import com.npdev.dsl.v1.compiled.CompiledGuidePageRegion;
import com.npdev.dsl.v1.compiled.CompiledGuidePageRegions;
import com.npdev.dsl.v1.compiled.CompiledGuidePageTheme;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledLifecycle;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledOrchestration;
import com.npdev.dsl.v1.compiled.CompiledOrchestrationAction;
import com.npdev.dsl.v1.compiled.CompiledOrchestrationTrigger;
import com.npdev.dsl.v1.compiled.CompiledAggregate;
import com.npdev.dsl.v1.compiled.CompiledAggregateCollection;
import com.npdev.dsl.v1.compiled.CompiledAutoPanel;
import com.npdev.dsl.v1.compiled.CompiledAutoPanelComputed;
import com.npdev.dsl.v1.compiled.CompiledAutoPanelDataSource;
import com.npdev.dsl.v1.compiled.CompiledAutoPanelSurface;
import com.npdev.dsl.v1.compiled.CompiledDocument;
import com.npdev.dsl.v1.compiled.CompiledExternalAi;
import com.npdev.dsl.v1.compiled.CompiledSettings;
import com.npdev.dsl.v1.compiled.CompiledTransactionHooks;
import com.npdev.dsl.v1.compiled.CompiledDerivedField;
import com.npdev.dsl.v1.compiled.CompiledRegionMount;
import com.npdev.dsl.v1.compiled.CompiledUiStateControl;
import com.npdev.dsl.v1.compiled.CompiledWorkbenchAction;
import com.npdev.dsl.v1.compiled.CompiledWorkbenchActionApplyTo;
import com.npdev.dsl.v1.compiled.CompiledWorkbenchBandPicker;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPanelAction;
import com.npdev.dsl.v1.compiled.CompiledPanelDataSource;
import com.npdev.dsl.v1.compiled.CompiledPanelFieldBinding;
import com.npdev.dsl.v1.compiled.CompiledPanelLayout;
import com.npdev.dsl.v1.compiled.CompiledPresentationMetadata;
import com.npdev.dsl.v1.compiled.CompiledProcedure;
import com.npdev.dsl.v1.compiled.CompiledProcedureParameter;
import com.npdev.dsl.v1.compiled.CompiledProcedureStep;
import com.npdev.dsl.v1.compiled.CompiledProcedureVariable;
import com.npdev.dsl.v1.compiled.CompiledQuery;
import com.npdev.dsl.v1.compiled.CompiledReferenceSemantics;
import com.npdev.dsl.v1.compiled.CompiledRuleProfile;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import com.npdev.dsl.v1.compiled.CompiledStateMachineState;
import com.npdev.dsl.v1.compiled.CompiledStateTransition;
import com.npdev.dsl.v1.compiled.JavaIdentifierSupport;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.dsl.v1.resolution.ModelResolver;
import com.npdev.dsl.v1.resolution.ResolvedModel;

import java.util.*;

/**
 * AST -> CompiledModel (normalized, ready for generators).
 */
public final class ModelCompiler {

    public CompiledModel compile(ModelAst modelAst) {
        ResolvedModel resolvedModel = new ModelResolver().resolve(modelAst);
        return compileResolved(resolvedModel.modelAst());
    }

    private CompiledModel compileResolved(ModelAst modelAst) {
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        List<CompiledDomainType> domainTypes = new ArrayList<>();
        List<CompiledCapability> capabilities = new ArrayList<>();
        List<CompiledCapabilityBinding> bindings = new ArrayList<>();
        List<CompiledEvent> events = new ArrayList<>();
        List<CompiledFlow> flows = new ArrayList<>();
        List<CompiledOrchestration> orchestrationRules = new ArrayList<>();
        List<CompiledQuery> queries = new ArrayList<>();
        List<CompiledRuleProfile> ruleProfiles = new ArrayList<>();
        List<CompiledProcedure> procedures = new ArrayList<>();
        List<CompiledPanel> panels = new ArrayList<>();
        List<CompiledDocument> documents = new ArrayList<>();
        CompiledSettings settings = toCompiledSettings(modelAst.getSettings());
        Map<String, String> capabilityTypesByName = new HashMap<>();
        Map<String, Map<String, CompiledCapabilityOperation>> operationsByCapability = new HashMap<>();
        Map<String, List<String>> invariantRefsByConcept = new HashMap<>();
        Map<String, Map<String, String>> invariantRefAliasByConcept = new HashMap<>();
        Map<String, DomainTypeAst> domainTypesByLower = indexDomainTypes(modelAst.getDomainTypes());
        Map<String, ConceptAst> conceptsByLower = indexConcepts(modelAst.getConcepts());
        Map<String, EffectiveEntityDef> effectiveCache = new HashMap<>();

        List<DomainTypeAst> orderedDomainTypes = new ArrayList<>(modelAst.getDomainTypes());
        orderedDomainTypes.sort(Comparator.comparing(domainType -> normalize(domainType.getName())));
        for (DomainTypeAst domainTypeAst : orderedDomainTypes) {
            domainTypes.add(toCompiledDomainType(domainTypeAst));
        }

        Set<String> contextNames = new HashSet<>();
        for (ContextAst context : modelAst.getContexts()) {
            contextNames.add(context.name());
        }

        List<ConceptAst> orderedConcepts = new ArrayList<>(modelAst.getConcepts());
        orderedConcepts.sort(Comparator.comparing(concept -> normalize(concept.getName())));
        for (ConceptAst concept : orderedConcepts) {
            String className = JavaIdentifierSupport.className(concept.getName());
            String tableName = SqlIdentifierSupport.toSnakePlural(tableNameSource(concept.getName(), contextNames));

            EffectiveEntityDef effective = resolveEffective(
                    concept,
                    conceptsByLower,
                    effectiveCache,
                    new HashSet<>()
            );

            List<CompiledField> fields = new ArrayList<>();
            List<String> expressionInvariants = new ArrayList<>();
            LinkedHashMap<String, CompiledInvariant> invariantsByCanonicalRef = new LinkedHashMap<>();
            Map<String, String> invariantRefAlias = new LinkedHashMap<>();

            // Derive unique fields from entity invariants (type=unique). A single-field unique
            // also marks the CompiledField.unique flag; a compound (multi-field) unique is
            // carried only on the CompiledInvariant's ordered fields list (LIFT-UNIQUE-P1) since
            // it doesn't correspond to any one field's own uniqueness.
            //
            // IMPORTANT:
            // Treat field names case-insensitively so that an invariant like ["Email"]
            // still applies to a field named "email".
            Set<String> uniqueFromInvariantsLower = new HashSet<>();
            for (InvariantAst inv : effective.invariants()) {
                if ("unique".equalsIgnoreCase(inv.getType()) && inv.getFields() != null && !inv.getFields().isEmpty()) {
                    List<String> invFields = inv.getFields();
                    if (invFields.size() == 1) {
                        uniqueFromInvariantsLower.add(invFields.get(0).toLowerCase(Locale.ROOT));
                    }
                    String canonicalRef = invariantCanonicalRef(inv);
                    registerInvariant(
                            invariantsByCanonicalRef,
                            invariantRefAlias,
                            canonicalRef,
                            "unique",
                            invFields.get(0),
                            null,
                            invFields,
                            List.of("unique(" + String.join(",", invFields) + ")")
                    );
                } else if ("expression".equalsIgnoreCase(inv.getType())) {
                    String expr = inv.getExpression();
                    if (expr != null && !expr.isBlank()) {
                        expressionInvariants.add(expr.trim());
                        String canonicalRef = invariantCanonicalRef(inv);
                        registerInvariant(
                                invariantsByCanonicalRef,
                                invariantRefAlias,
                                canonicalRef,
                                "expression",
                                null,
                                expr.trim(),
                                List.of(expr.trim())
                        );
                    }
                }
            }

            for (FieldAst f : effective.fields()) {
                String fieldName = f.getName();
                String fieldNameLower = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);

                boolean unique = f.isUnique() || uniqueFromInvariantsLower.contains(fieldNameLower);
                DomainTypeAst fieldDomainType = domainTypesByLower.get(normalize(f.getDomainType()));
                String effectiveDslType = fieldDomainType == null ? f.getType() : fieldDomainType.getBaseType();
                SchemaAst effectiveSchema = fieldDomainType == null && f.getSchema() == null
                        ? null
                        : mergeSchemas(
                        fieldDomainType == null ? null : fieldDomainType.getValidationSchema(),
                        f.getSchema(),
                        effectiveDslType
                );

                fields.add(new CompiledField(
                        fieldName,
                        f.getType(),
                        toJavaType(effectiveDslType),
                        f.isId(),
                        f.isRequired(),
                        unique,
                        f.getEnumValues(),
                        f.getReferenceTarget(),
                        toCompiledReferenceSemantics(f.getReferenceSemantics()),
                        f.getDomainType(),
                        toCompiledSchema(effectiveSchema),
                        toCompiledEnumOptions(f.getEnumOptions()),
                        toCompiledPresentationMetadata(f.getUi()),
                        f.getConnectable(),
                        f.getRenamedFrom(),
                        toCompiledFileMetadata(f.getFile()),
                        f.isSensitive(),
                        toCompiledFieldPicker(f.getPicker())
                ));

                if (f.isRequired()) {
                    String requiredRef = "required(" + fieldName + ")";
                    registerInvariant(
                            invariantsByCanonicalRef,
                            invariantRefAlias,
                            requiredRef,
                            "required",
                            fieldName,
                            null,
                            List.of()
                    );
                }
            }

            fields.sort(Comparator.comparing(field -> normalize(field.getName())));
            expressionInvariants.sort(String.CASE_INSENSITIVE_ORDER);
            List<CompiledInvariant> compiledInvariants = new ArrayList<>(invariantsByCanonicalRef.values());
            compiledInvariants.sort(Comparator.comparing(invariant -> normalize(invariant.getRef())));

            List<CompiledIndex> compiledIndexes = new ArrayList<>();
            for (IndexAst index : concept.getIndexes()) {
                compiledIndexes.add(new CompiledIndex(index.getName(), index.getFields(), index.isUnique()));
            }
            CompiledConceptAccess compiledAccess = concept.getAccess() == null
                    ? null
                    : new CompiledConceptAccess(concept.getAccess().getRead(), concept.getAccess().getWrite());

            concepts.put(
                    concept.getName(),
                    new CompiledConcept(
                            concept.getName(),
                            className,
                            tableName,
                            fields,
                            expressionInvariants,
                            compiledInvariants,
                            toCompiledLifecycle(effective.lifecycle()),
                            toCompiledPresentationMetadata(concept.getUi()),
                            concept.getTruthLevel() == null ? null : concept.getTruthLevel().code(),
                            concept.getModule(),
                            compiledIndexes,
                            compiledAccess,
                            concept.getRenamedFrom()
                    )
            );
            List<String> invariantRefs = new ArrayList<>(invariantsByCanonicalRef.keySet());
            invariantRefs.sort(String.CASE_INSENSITIVE_ORDER);
            invariantRefsByConcept.put(normalize(concept.getName()), invariantRefs);
            invariantRefAliasByConcept.put(normalize(concept.getName()), Map.copyOf(invariantRefAlias));
        }

        List<CapabilityAst> orderedCapabilities = new ArrayList<>(modelAst.getCapabilities());
        orderedCapabilities.sort(Comparator.comparing(capability -> normalize(capability.getName())));
        for (CapabilityAst capabilityAst : orderedCapabilities) {
            List<CompiledCapabilityOperation> operations = new ArrayList<>();
            for (CapabilityOperationAst operationAst : capabilityAst.getOperations()) {
                operations.add(new CompiledCapabilityOperation(
                        operationAst.getName(),
                        operationAst.getInput(),
                        operationAst.getOutput(),
                        toCompiledSchema(operationAst.getInputSchema()),
                        toCompiledSchema(operationAst.getOutputSchema()),
                        toCompiledPolicy(operationAst.getExecutionPolicy())
                ));
            }
            operations.sort(Comparator.comparing(operation -> normalize(operation.getName())));
            capabilities.add(new CompiledCapability(capabilityAst.getName(), capabilityAst.getType(), operations));
            capabilityTypesByName.put(normalize(capabilityAst.getName()), capabilityAst.getType());
            Map<String, CompiledCapabilityOperation> operationMap = new LinkedHashMap<>();
            for (CompiledCapabilityOperation operation : operations) {
                operationMap.put(normalize(operation.getName()), operation);
            }
            operationsByCapability.put(normalize(capabilityAst.getName()), operationMap);
        }

        List<CapabilityBindingAst> orderedBindings = new ArrayList<>(modelAst.getBindings());
        orderedBindings.sort(Comparator
                .comparing((CapabilityBindingAst binding) -> normalize(binding.getCapability()))
                .thenComparing(binding -> normalize(binding.getAdapter())));
        for (CapabilityBindingAst bindingAst : orderedBindings) {
            bindings.add(new CompiledCapabilityBinding(bindingAst.getCapability(), bindingAst.getAdapter()));
        }

        List<EventAst> orderedEvents = new ArrayList<>(modelAst.getEvents());
        orderedEvents.sort(Comparator.comparing(event -> normalize(event.getName())));
        for (EventAst eventAst : orderedEvents) {
            List<CompiledEventField> payloadFields = new ArrayList<>();
            for (EventPayloadAst payloadField : eventAst.getPayloadFields()) {
                payloadFields.add(new CompiledEventField(payloadField.getName(), payloadField.getType()));
            }
            payloadFields.sort(Comparator.comparing(field -> normalize(field.getName())));
            events.add(new CompiledEvent(eventAst.getName(), eventAst.getConceptName(), payloadFields, eventAst.getTriggerMode()));
        }

        List<FlowAst> orderedFlows = new ArrayList<>(modelAst.getFlows());
        orderedFlows.sort(Comparator.comparing(flow -> normalize(flow.getName())));
        for (FlowAst flowAst : orderedFlows) {
            List<CompiledFlowStep> flowSteps = compileFlowSteps(
                    flowAst.getSteps(),
                    capabilityTypesByName,
                    operationsByCapability,
                    flowAst.getConcept(),
                    invariantRefsByConcept,
                    invariantRefAliasByConcept
            );
            flows.add(new CompiledFlow(
                    flowAst.getName(),
                    flowAst.getConcept(),
                    flowAst.getMode(),
                    flowSteps,
                    toCompiledSchema(flowAst.getInputSchema()),
                    toCompiledSchema(flowAst.getOutputSchema()),
                    toCompiledActionMetadata(flowAst.getAction()),
                    flowAst.isStartEndpoint(),
                    toCompiledFlowSchedule(flowAst.getSchedule())
            ));
        }

        List<OrchestrationAst> orderedOrchestrationRules = new ArrayList<>(modelAst.getOrchestrationRules());
        orderedOrchestrationRules.sort(Comparator.comparing(rule -> normalize(rule.getName())));
        for (OrchestrationAst ruleAst : orderedOrchestrationRules) {
            OrchestrationTriggerAst triggerAst = ruleAst.getTrigger();
            List<OrchestrationActionAst> actionAsts = ruleAst.getActions().isEmpty()
                    ? (ruleAst.getAction() == null ? List.of() : List.of(ruleAst.getAction()))
                    : ruleAst.getActions();
            List<CompiledOrchestrationAction> compiledActions = new ArrayList<>();
            for (OrchestrationActionAst actionAst : actionAsts) {
                if (actionAst == null) {
                    continue;
                }
                compiledActions.add(new CompiledOrchestrationAction(
                        actionAst.getType(),
                        actionAst.getConcept(),
                        actionAst.getCapability(),
                        actionAst.getOperation(),
                        actionAst.getEvent(),
                        actionAst.getDelaySeconds(),
                        sortByKey(actionAst.getMap()),
                        toCompiledActionMetadata(actionAst.getAction())
                ));
            }
            CompiledOrchestrationAction primaryAction = compiledActions.isEmpty() ? null : compiledActions.get(0);
            orchestrationRules.add(new CompiledOrchestration(
                    ruleAst.getName(),
                    ruleAst.getCondition(),
                    triggerAst == null
                            ? null
                            : new CompiledOrchestrationTrigger(triggerAst.getType(), triggerAst.getEvent()),
                    primaryAction,
                    List.copyOf(compiledActions)
            ));
        }

        List<QueryAst> orderedQueries = new ArrayList<>(modelAst.getQueries());
        orderedQueries.sort(Comparator.comparing(query -> normalize(query.name())));
        for (QueryAst queryAst : orderedQueries) {
            queries.add(new CompiledQuery(
                    queryAst.name(),
                    queryAst.concept(),
                    queryAst.where(),
                    copyStrings(queryAst.orderBy()),
                    queryAst.limit(),
                    compileProcedureParameters(queryAst.parameters()),
                    sortedStrings(queryAst.permissionRequirements()),
                    queryAst.tracePolicy(),
                    queryAst.auditPolicy(),
                    sortObjectMap(queryAst.metadata()),
                    toCompiledGroupByFields(queryAst.groupBy()),
                    toCompiledAggregateFunctions(queryAst.aggregates()),
                    queryAst.having()
            ));
        }

        List<RuleProfileAst> orderedRuleProfiles = new ArrayList<>(modelAst.getRuleProfiles());
        orderedRuleProfiles.sort(Comparator.comparing(profile -> normalize(profile.name())));
        for (RuleProfileAst profileAst : orderedRuleProfiles) {
            ruleProfiles.add(new CompiledRuleProfile(
                    profileAst.name(),
                    profileAst.description(),
                    sortedStrings(profileAst.appliesTo()),
                    profileAst.enabled(),
                    sortObjectMap(profileAst.metadata())
            ));
        }

        List<ProcedureAst> orderedProcedures = new ArrayList<>(modelAst.getProcedures());
        orderedProcedures.sort(Comparator.comparing(procedure -> normalize(procedure.name())));
        for (ProcedureAst procedureAst : orderedProcedures) {
            procedures.add(new CompiledProcedure(
                    procedureAst.name(),
                    procedureAst.description(),
                    compileProcedureParameters(procedureAst.parameters()),
                    compileProcedureVariables(procedureAst.variables()),
                    compileProcedureSteps(procedureAst.steps()),
                    toCompiledSchema(procedureAst.returns()),
                    sortedStrings(procedureAst.permissionRequirements()),
                    procedureAst.tracePolicy(),
                    procedureAst.auditPolicy(),
                    compileGeneratedActionDescriptor(procedureAst),
                    sortObjectMap(procedureAst.metadata())
            ));
        }

        List<PanelAst> orderedPanels = new ArrayList<>(modelAst.getPanels());
        orderedPanels.sort(Comparator.comparing(panel -> normalize(panel.name())));
        for (PanelAst panelAst : orderedPanels) {
            panels.add(compilePanel(panelAst));
        }

        List<DocumentAst> orderedDocuments = new ArrayList<>(modelAst.getDocuments());
        orderedDocuments.sort(Comparator.comparing(document -> normalize(document.name())));
        for (DocumentAst documentAst : orderedDocuments) {
            if (!conceptsByLower.containsKey(normalize(documentAst.concept()))) {
                throw new IllegalArgumentException("document '" + documentAst.name()
                        + "' declares concept '" + documentAst.concept() + "', which is not a declared concept");
            }
            documents.add(compileDocument(documentAst));
        }

        List<GuidePageAst> orderedGuidePages = new ArrayList<>(modelAst.getGuidePages());
        orderedGuidePages.sort(Comparator.comparing(page -> normalize(page.name())));
        List<CompiledGuidePage> guidePages = new ArrayList<>();
        for (GuidePageAst guidePageAst : orderedGuidePages) {
            guidePages.add(compileGuidePage(guidePageAst));
        }

        List<AggregateAst> orderedAggregates = new ArrayList<>(modelAst.getAggregates());
        orderedAggregates.sort(Comparator.comparing(aggregate -> normalize(aggregate.name())));
        List<CompiledAggregate> aggregates = new ArrayList<>();
        for (AggregateAst aggregateAst : orderedAggregates) {
            aggregates.add(compileAggregate(aggregateAst, modelAst.getAutoPanels()));
        }

        List<AutoPanelAst> orderedAutoPanels = new ArrayList<>(modelAst.getAutoPanels());
        orderedAutoPanels.sort(Comparator.comparing(autoPanel -> normalize(autoPanelKey(autoPanel))));
        List<CompiledAutoPanel> autoPanels = new ArrayList<>();
        for (AutoPanelAst autoPanelAst : orderedAutoPanels) {
            autoPanels.add(compileAutoPanel(autoPanelAst));
        }

        // Expand concept-bound AutoPanels into ordinary panels (Selection/Detail/Transaction/Prompt),
        // reading defaults from the concept. Aggregate-bound AutoPanels are expanded later (P4).
        Map<String, ConceptAst> conceptsByNormalizedName = new LinkedHashMap<>();
        Map<String, List<String>> fieldNamesByConcept = new LinkedHashMap<>();
        for (ConceptAst concept : modelAst.getConcepts()) {
            conceptsByNormalizedName.put(normalize(concept.getName()), concept);
            List<String> names = new ArrayList<>();
            for (FieldAst field : concept.getFields()) {
                names.add(field.getName());
            }
            fieldNamesByConcept.put(normalize(concept.getName()), names);
        }
        Map<String, CompiledAggregate> aggregatesByNormalizedName = new LinkedHashMap<>();
        for (CompiledAggregate aggregate : aggregates) {
            aggregatesByNormalizedName.put(normalize(aggregate.name()), aggregate);
        }
        // Registry of each concept's Prompt picker, so a form's FK field can auto-wire to it.
        Map<String, AutoPanelExpander.PromptRef> promptsByConcept = new LinkedHashMap<>();
        for (CompiledAutoPanel autoPanel : autoPanels) {
            if (autoPanel.concept() == null || autoPanel.concept().isBlank()) {
                continue;
            }
            ConceptAst concept = conceptsByNormalizedName.get(normalize(autoPanel.concept()));
            if (concept == null) {
                continue;
            }
            AutoPanelExpander.PromptRef ref = AutoPanelExpander.promptRefFor(autoPanel, concept.getFields());
            if (ref != null) {
                promptsByConcept.put(normalize(autoPanel.concept()), ref);
            }
        }
        for (CompiledAutoPanel autoPanel : autoPanels) {
            if (autoPanel.concept() != null && !autoPanel.concept().isBlank()) {
                ConceptAst concept = conceptsByNormalizedName.get(normalize(autoPanel.concept()));
                if (concept != null) {
                    panels.addAll(AutoPanelExpander.expand(autoPanel, concept.getFields(), promptsByConcept, settings));
                }
            } else if (autoPanel.aggregate() != null && !autoPanel.aggregate().isBlank()) {
                // Aggregate-bound: the Transaction surface becomes the multi-level Aggregate Workbench.
                CompiledAggregate aggregate = aggregatesByNormalizedName.get(normalize(autoPanel.aggregate()));
                if (aggregate != null) {
                    panels.addAll(AutoPanelExpander.expandAggregateWorkbench(
                            autoPanel, aggregate, fieldNamesByConcept, conceptsByNormalizedName, settings));
                }
            }
        }

        // Expand standalone selectors into reusable picker panels.
        for (SelectorAst selector : modelAst.getSelectors()) {
            ConceptAst concept = conceptsByNormalizedName.get(normalize(selector.concept()));
            List<String> fieldNames = new ArrayList<>();
            if (concept != null) {
                for (FieldAst field : concept.getFields()) {
                    fieldNames.add(field.getName());
                }
            }
            panels.add(AutoPanelExpander.expandSelector(selector, fieldNames));
        }

        return new CompiledModel(
                modelAst.getNamespace(),
                modelAst.getDslVersion(),
                modelAst.getVersion(),
                concepts,
                domainTypes,
                capabilities,
                bindings,
                events,
                flows,
                orchestrationRules,
                queries,
                ruleProfiles,
                procedures,
                panels,
                guidePages,
                aggregates,
                autoPanels,
                documents,
                toCompiledExternalAi(modelAst.getExternalAi()),
                settings,
                toCompiledRoles(modelAst.getRoles()),
                toCompiledPropertyScopes(modelAst.getPropertyScopes()),
                toCompiledProperties(modelAst.getProperties()),
                toCompiledContexts(modelAst.getContexts())
        );
    }

    /** ADR-0009: compiles the app-level externalAi block, or null if the model declares none. */
    private static CompiledExternalAi toCompiledExternalAi(ExternalAiAst externalAiAst) {
        if (externalAiAst == null) {
            return null;
        }
        return new CompiledExternalAi(externalAiAst.getEgress(), externalAiAst.getVendors());
    }

    /** Move 6 Move A: compiles the app-level settings block, merging platform string defaults. */
    private static CompiledSettings toCompiledSettings(SettingsAst settingsAst) {
        if (settingsAst == null) {
            return CompiledSettings.defaults();
        }
        return new CompiledSettings(
                settingsAst.getLocale(), settingsAst.getStrings(), settingsAst.getPageRows(),
                settingsAst.getDateFormat());
    }

    /** B20 (S2): compiles the declared bounded-context registry (name + $ref). The qualification
     *  each context's members already carry (contextName::Member) is produced upstream at
     *  ModelSourceResolver's composition step, not here -- this is metadata pass-through, the same
     *  shape roles/propertyScopes/properties already are. */
    private static List<com.npdev.dsl.v1.compiled.CompiledContext> toCompiledContexts(
            List<com.npdev.dsl.v1.ast.ContextAst> contextAsts) {
        List<com.npdev.dsl.v1.compiled.CompiledContext> compiled = new ArrayList<>();
        for (com.npdev.dsl.v1.ast.ContextAst contextAst : contextAsts) {
            compiled.add(new com.npdev.dsl.v1.compiled.CompiledContext(contextAst.name(), contextAst.ref()));
        }
        return compiled;
    }

    /** Wave 3 (RC-B1): compiles the app-defined role -> permission-ceiling declarations. */
    private static List<com.npdev.dsl.v1.compiled.CompiledRole> toCompiledRoles(
            List<com.npdev.dsl.v1.ast.RoleAst> roleAsts) {
        List<com.npdev.dsl.v1.compiled.CompiledRole> compiled = new ArrayList<>();
        for (com.npdev.dsl.v1.ast.RoleAst roleAst : roleAsts) {
            compiled.add(new com.npdev.dsl.v1.compiled.CompiledRole(roleAst.name(), roleAst.grants()));
        }
        return compiled;
    }

    /** Wave 6 (RC-A1): compiles the declared scope levels of the property cascade. */
    private static List<com.npdev.dsl.v1.compiled.CompiledPropertyScope> toCompiledPropertyScopes(
            List<com.npdev.dsl.v1.ast.PropertyScopeAst> scopeAsts) {
        List<com.npdev.dsl.v1.compiled.CompiledPropertyScope> compiled = new ArrayList<>();
        for (com.npdev.dsl.v1.ast.PropertyScopeAst scopeAst : scopeAsts) {
            compiled.add(new com.npdev.dsl.v1.compiled.CompiledPropertyScope(scopeAst.name(), scopeAst.from()));
        }
        return compiled;
    }

    /** Wave 6 (RC-A1): compiles the declared runtime properties. */
    private static List<com.npdev.dsl.v1.compiled.CompiledProperty> toCompiledProperties(
            List<com.npdev.dsl.v1.ast.PropertyAst> propertyAsts) {
        List<com.npdev.dsl.v1.compiled.CompiledProperty> compiled = new ArrayList<>();
        for (com.npdev.dsl.v1.ast.PropertyAst propertyAst : propertyAsts) {
            compiled.add(new com.npdev.dsl.v1.compiled.CompiledProperty(
                    propertyAst.name(), propertyAst.type(), propertyAst.defaultValue(),
                    propertyAst.settableAt(), propertyAst.label(), propertyAst.securityRelevant()));
        }
        return compiled;
    }

    /** Move 10 B1: compiles query.groupBy[]. */
    private static List<com.npdev.dsl.v1.compiled.CompiledGroupByField> toCompiledGroupByFields(
            List<com.npdev.dsl.v1.ast.GroupByFieldAst> groupByAsts) {
        List<com.npdev.dsl.v1.compiled.CompiledGroupByField> compiled = new ArrayList<>();
        for (com.npdev.dsl.v1.ast.GroupByFieldAst groupByAst : groupByAsts) {
            compiled.add(new com.npdev.dsl.v1.compiled.CompiledGroupByField(
                    groupByAst.field(), groupByAst.bucket()));
        }
        return compiled;
    }

    /** Move 10 B1: compiles query.aggregates[]. */
    private static List<com.npdev.dsl.v1.compiled.CompiledAggregateFunction> toCompiledAggregateFunctions(
            List<com.npdev.dsl.v1.ast.AggregateFunctionAst> aggregateAsts) {
        List<com.npdev.dsl.v1.compiled.CompiledAggregateFunction> compiled = new ArrayList<>();
        for (com.npdev.dsl.v1.ast.AggregateFunctionAst aggregateAst : aggregateAsts) {
            compiled.add(new com.npdev.dsl.v1.compiled.CompiledAggregateFunction(
                    aggregateAst.name(), aggregateAst.fn(), aggregateAst.field()));
        }
        return compiled;
    }

    private static CompiledDocument compileDocument(DocumentAst documentAst) {
        return new CompiledDocument(
                documentAst.name(),
                documentAst.concept(),
                documentAst.title(),
                documentAst.pageSize(),
                documentAst.marginMm(),
                sortObjectMap(documentAst.metadata())
        );
    }

    private static String autoPanelKey(AutoPanelAst autoPanel) {
        if (autoPanel.name() != null && !autoPanel.name().isBlank()) {
            return autoPanel.name();
        }
        if (autoPanel.concept() != null && !autoPanel.concept().isBlank()) {
            return autoPanel.concept();
        }
        return autoPanel.aggregate() == null ? "" : autoPanel.aggregate();
    }

    private static CompiledAutoPanel compileAutoPanel(AutoPanelAst autoPanelAst) {
        return new CompiledAutoPanel(
                autoPanelAst.name(),
                autoPanelAst.concept(),
                autoPanelAst.aggregate(),
                autoPanelAst.route(),
                new ArrayList<>(autoPanelAst.surfaces()),
                compileAutoPanelSurface(autoPanelAst.selection()),
                compileAutoPanelSurface(autoPanelAst.detail()),
                compileAutoPanelSurface(autoPanelAst.transaction()),
                compileAutoPanelSurface(autoPanelAst.prompt()),
                sortObjectMap(autoPanelAst.metadata())
        );
    }

    private static CompiledAutoPanelSurface compileAutoPanelSurface(AutoPanelSurfaceAst surface) {
        if (surface == null) {
            return null;
        }
        List<CompiledAutoPanelComputed> computed = new ArrayList<>();
        for (AutoPanelComputedAst c : surface.computed()) {
            computed.add(new CompiledAutoPanelComputed(c.col(), c.expr()));
        }
        List<CompiledDerivedField> derivedFields = new ArrayList<>();
        for (DerivedFieldAst d : surface.derivedFields()) {
            derivedFields.add(new CompiledDerivedField(d.name(), d.label(), d.tier(), d.expression(), d.procedure()));
        }
        Map<String, CompiledRegionMount> regions = new LinkedHashMap<>();
        for (Map.Entry<String, RegionMountAst> entry : surface.regions().entrySet()) {
            RegionMountAst region = entry.getValue();
            regions.put(entry.getKey(), new CompiledRegionMount(region.render(), region.component()));
        }
        List<CompiledWorkbenchAction> actions = new ArrayList<>();
        for (WorkbenchActionAst action : surface.actions()) {
            actions.add(new CompiledWorkbenchAction(
                    action.procedure(),
                    action.label(),
                    new ArrayList<>(action.inputFields()),
                    toCompiledWorkbenchActionApplyTo(action.applyTo()),
                    action.afterAction(),
                    action.visibleWhen()
            ));
        }
        Map<String, CompiledWorkbenchBandPicker> bandPickers = new LinkedHashMap<>();
        for (Map.Entry<String, WorkbenchBandPickerAst> entry : surface.bandPickers().entrySet()) {
            WorkbenchBandPickerAst picker = entry.getValue();
            bandPickers.put(entry.getKey(),
                    new CompiledWorkbenchBandPicker(
                            picker.panel(), picker.label(), new ArrayList<>(picker.columns()),
                            picker.filter(), picker.multiSelect()));
        }
        // Move 11 W6: declared transient UI state a `$ui.<name>` visibleWhen predicate can read.
        Map<String, CompiledUiStateControl> uiState = new LinkedHashMap<>();
        for (Map.Entry<String, UiStateControlAst> entry : surface.uiState().entrySet()) {
            UiStateControlAst control = entry.getValue();
            uiState.put(entry.getKey(), new CompiledUiStateControl(
                    control.name(), control.label(), new ArrayList<>(control.values()), control.defaultValue()));
        }
        return new CompiledAutoPanelSurface(
                new ArrayList<>(surface.filters()),
                new ArrayList<>(surface.columns()),
                new ArrayList<>(surface.fields()),
                computed,
                surface.labelField(),
                sortObjectMap(surface.metadata()),
                toCompiledTransactionHooks(surface.hooks()),
                derivedFields,
                regions,
                actions,
                new LinkedHashMap<>(surface.visibleWhen()),
                bandPickers,
                toCompiledAutoPanelDataSource(surface.dataSource()),
                uiState
        );
    }

    /** Move 8 D3 (item G6): compiles a surface's dataSource.procedure declaration, or null if absent. */
    private static CompiledAutoPanelDataSource toCompiledAutoPanelDataSource(AutoPanelDataSourceAst dataSource) {
        if (dataSource == null) {
            return null;
        }
        return new CompiledAutoPanelDataSource(dataSource.procedure());
    }

    /** Move 7 W1: compiles the optional transaction.actions[].applyTo shorthand, or null if absent. */
    private static CompiledWorkbenchActionApplyTo toCompiledWorkbenchActionApplyTo(WorkbenchActionApplyToAst applyTo) {
        if (applyTo == null) {
            return null;
        }
        return new CompiledWorkbenchActionApplyTo(applyTo.collection(), applyTo.mode(), new LinkedHashMap<>(applyTo.map()));
    }

    /** Move 6 Move B: compiles the optional closed-enum transaction.hooks block, or null if absent. */
    private static CompiledTransactionHooks toCompiledTransactionHooks(TransactionHooksAst hooks) {
        if (hooks == null) {
            return null;
        }
        return new CompiledTransactionHooks(
                hooks.onLoad(), hooks.onFieldChange(), hooks.beforeAction(), hooks.onValidate(), hooks.onCommit());
    }

    private static CompiledAggregate compileAggregate(AggregateAst aggregateAst, List<AutoPanelAst> autoPanels) {
        TransactionHooksAst hooks = transactionHooksFor(aggregateAst.name(), autoPanels);
        String onValidate = hasText(aggregateAst.onValidate())
                ? aggregateAst.onValidate() : (hooks == null ? null : hooks.onValidate());
        String onCommit = hasText(aggregateAst.onCommit())
                ? aggregateAst.onCommit() : (hooks == null ? null : hooks.onCommit());
        return new CompiledAggregate(
                aggregateAst.name(),
                aggregateAst.root(),
                compileAggregateCollections(aggregateAst.collections()),
                onCommit,
                sortObjectMap(aggregateAst.metadata()),
                onValidate
        );
    }

    /** Move 6 Move B: an aggregate-bound AutoPanel's transaction.hooks.onValidate/onCommit is an
     * alternate spelling for the aggregate's own onValidate/onCommit fields (unifying the two
     * dialects, docs/MOVE6_TYPED_SURFACE_PLAN.md §B.2) -- a direct aggregate.onValidate/onCommit
     * always wins when both are declared. */
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

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static List<CompiledAggregateCollection> compileAggregateCollections(
            List<AggregateCollectionAst> collections) {
        List<CompiledAggregateCollection> out = new ArrayList<>();
        for (AggregateCollectionAst collection : collections) {
            out.add(new CompiledAggregateCollection(
                    collection.name(),
                    collection.concept(),
                    collection.via(),
                    collection.childField(),
                    collection.ownership(),
                    collection.orderBy(),
                    compileAggregateCollections(collection.collections()),
                    sortObjectMap(collection.metadata())
            ));
        }
        return out;
    }

    private static CompiledGuidePage compileGuidePage(GuidePageAst guidePageAst) {
        return new CompiledGuidePage(
                guidePageAst.name(),
                guidePageAst.isDefault(),
                compileGuidePageRegions(guidePageAst.regions()),
                compileGuidePageTheme(guidePageAst.theme()),
                compileGuidePageGadgets(guidePageAst.gadgets())
        );
    }

    private static CompiledGuidePageRegions compileGuidePageRegions(GuidePageRegionsAst regionsAst) {
        if (regionsAst == null) {
            return null;
        }
        return new CompiledGuidePageRegions(
                regionsAst.top(),
                compileGuidePageRegion(regionsAst.left()),
                compileGuidePageRegion(regionsAst.right())
        );
    }

    private static CompiledGuidePageRegion compileGuidePageRegion(GuidePageRegionAst regionAst) {
        if (regionAst == null) {
            return null;
        }
        return new CompiledGuidePageRegion(
                regionAst.enabled(),
                regionAst.collapsible(),
                regionAst.defaultCollapsed(),
                regionAst.width()
        );
    }

    private static CompiledGuidePageTheme compileGuidePageTheme(GuidePageThemeAst themeAst) {
        if (themeAst == null) {
            return null;
        }
        return new CompiledGuidePageTheme(
                themeAst.mode(),
                themeAst.accent(),
                themeAst.density(),
                themeAst.logoText(),
                themeAst.logoUrl()
        );
    }

    private static List<CompiledGuidePageGadget> compileGuidePageGadgets(List<GuidePageGadgetAst> gadgetAsts) {
        List<CompiledGuidePageGadget> out = new ArrayList<>();
        for (GuidePageGadgetAst gadgetAst : gadgetAsts) {
            out.add(new CompiledGuidePageGadget(
                    gadgetAst.name(), gadgetAst.type(), gadgetAst.title(),
                    gadgetAst.query(), gadgetAst.x(), gadgetAst.y(), gadgetAst.series()));
        }
        return out;
    }

    private static Map<String, ConceptAst> indexConcepts(List<ConceptAst> concepts) {
        Map<String, ConceptAst> out = new LinkedHashMap<>();
        for (ConceptAst concept : concepts) {
            out.put(normalize(concept.getName()), concept);
        }
        return out;
    }

    private static Map<String, DomainTypeAst> indexDomainTypes(List<DomainTypeAst> domainTypes) {
        Map<String, DomainTypeAst> out = new LinkedHashMap<>();
        for (DomainTypeAst domainType : domainTypes) {
            if (domainType == null || domainType.getName() == null || domainType.getName().isBlank()) {
                continue;
            }
            out.put(normalize(domainType.getName()), domainType);
        }
        return out;
    }

    private static EffectiveEntityDef resolveEffective(
            ConceptAst entity,
            Map<String, ? extends ConceptAst> entitiesByLower,
            Map<String, EffectiveEntityDef> cache,
            Set<String> stack
    ) {
        String key = normalize(entity.getName());
        EffectiveEntityDef cached = cache.get(key);
        if (cached != null) return cached;

        if (!stack.add(key)) {
            return new EffectiveEntityDef(entity.getFields(), entity.getInvariants(), entity.getLifecycle());
        }

        LinkedHashMap<String, FieldAst> fieldsByLower = new LinkedHashMap<>();
        List<InvariantAst> invariants = new ArrayList<>();
        LifecycleAst lifecycle = null;

        String parentName = entity.getExtendsName();
        if (parentName != null && !parentName.isBlank()) {
            ConceptAst parent = entitiesByLower.get(normalize(parentName));
            if (parent != null) {
                EffectiveEntityDef parentEffective = resolveEffective(parent, entitiesByLower, cache, stack);
                for (FieldAst pf : parentEffective.fields()) {
                    fieldsByLower.put(normalize(pf.getName()), pf);
                }
                invariants.addAll(parentEffective.invariants());
                lifecycle = parentEffective.lifecycle();
            }
        }

        for (FieldAst localField : entity.getFields()) {
            fieldsByLower.put(normalize(localField.getName()), localField);
        }
        invariants.addAll(entity.getInvariants());
        if (entity.getLifecycle() != null) {
            lifecycle = entity.getLifecycle();
        }

        EffectiveEntityDef effective = new EffectiveEntityDef(
                new ArrayList<>(fieldsByLower.values()),
                invariants,
                lifecycle
        );
        cache.put(key, effective);
        stack.remove(key);
        return effective;
    }

    private static String toJavaType(String dslType) {
        if (dslType == null) {
            return "String";
        }
        return switch (dslType.trim().toLowerCase(Locale.ROOT)) {
            case "string" -> "String";
            case "uuid" -> "java.util.UUID";
            case "int", "integer" -> "Integer";
            case "long" -> "Long";
            case "boolean" -> "Boolean";
            case "date" -> "java.time.LocalDate";
            case "datetime" -> "java.time.OffsetDateTime";
            case "enum" -> "String";
            case "reference" -> "java.util.UUID";
            case "object", "array" -> "com.fasterxml.jackson.databind.JsonNode";
            // HARDEN-OBJSTORE: a file field's SQL column is JSONB (SqlTypeSupport) storing a
            // FileHandle (or list, if multiple) -- without this case it fell through to the
            // "String" default, mismatching the JSONB column and breaking entity (de)serialization
            // for any model that declares a file field through the real authoring pipeline.
            case "file" -> "com.fasterxml.jackson.databind.JsonNode";
            default -> "String";
        };
    }

    private static CompiledDomainType toCompiledDomainType(DomainTypeAst domainTypeAst) {
        if (domainTypeAst == null) {
            return null;
        }
        DomainTypeUiAst uiAst = domainTypeAst.getUi();
        CompiledDomainTypeUi ui = uiAst == null
                ? null
                : new CompiledDomainTypeUi(
                uiAst.getLabel(),
                uiAst.getPlaceholder(),
                uiAst.getHelpText(),
                uiAst.getWidget()
        );
        return new CompiledDomainType(
                domainTypeAst.getName(),
                domainTypeAst.getBaseType(),
                toJavaType(domainTypeAst.getBaseType()),
                toCompiledSchema(domainTypeAst.getValidationSchema() == null
                        ? null
                        : mergeSchemas(domainTypeAst.getValidationSchema(), null, domainTypeAst.getBaseType())),
                domainTypeAst.getNormalizationRules(),
                domainTypeAst.getFormatHint(),
                domainTypeAst.getExamples(),
                ui
        );
    }

    private static List<CompiledEnumOption> toCompiledEnumOptions(List<EnumOptionAst> enumOptions) {
        if (enumOptions == null || enumOptions.isEmpty()) {
            return List.of();
        }
        List<CompiledEnumOption> compiled = new ArrayList<>();
        for (EnumOptionAst enumOption : enumOptions) {
            if (enumOption == null) {
                continue;
            }
            compiled.add(new CompiledEnumOption(
                    enumOption.getValue(),
                    enumOption.getLabel(),
                    enumOption.getOrder(),
                    enumOption.getGroup(),
                    enumOption.isDefaultValue(),
                    enumOption.isDeprecated(),
                    enumOption.getIconHint(),
                    enumOption.getBadgeHint(),
                    enumOption.getDescription()
            ));
        }
        return List.copyOf(compiled);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** ADR-0011 D4 (B20): a context-qualified concept's physical table name ignores the context
     *  qualifier -- table names are derived exactly as before B20, from the bare concept name.
     *  Pack-qualified names ({@code packId::Name}) are untouched by this and keep prefixing exactly
     *  as they always have; only a prefix matching a name in this model's own declared
     *  {@code contexts[]} is stripped, since that is the only qualifier D4 promises is invisible to
     *  the physical schema. */
    private static String tableNameSource(String qualifiedName, Set<String> contextNames) {
        int split = qualifiedName.indexOf("::");
        if (split > 0 && contextNames.contains(qualifiedName.substring(0, split))) {
            return qualifiedName.substring(split + 2);
        }
        return qualifiedName;
    }

    private static CompiledGeneratedActionDescriptorSpec compileGeneratedActionDescriptor(ProcedureAst procedureAst) {
        GeneratedActionDescriptorAst explicit = procedureAst.actionDescriptor();
        if (explicit != null) {
            String actionName = firstNonBlank(explicit.actionName(), procedureAst.name());
            String sideEffectConcept = blankToNull(explicit.sideEffectConcept());
            List<String> affectedConcepts = copyStrings(explicit.affectedConcepts());
            if (affectedConcepts.isEmpty() && sideEffectConcept != null) {
                affectedConcepts = List.of(sideEffectConcept);
            }
            return new CompiledGeneratedActionDescriptorSpec(
                    actionName,
                    affectedConcepts,
                    sideEffectConcept,
                    firstNonBlank(explicit.eventNameOnSuccess(), defaultGeneratedActionEvent(actionName)),
                    firstNonBlank(explicit.auditResourceType(), "GENERATED_ACTION"),
                    firstNonBlank(explicit.idempotencyPolicy(), "record"),
                    firstNonBlank(explicit.tracePolicy(), "record"),
                    firstNonBlank(explicit.correlationPolicy(), "claim"),
                    true
            );
        }

        Map<String, Object> metadata = procedureAst.metadata();
        String actionName = firstNonBlank(metadataText(metadata, "actionName"), procedureAst.name());
        String sideEffectConcept = firstNonBlank(metadataText(metadata, "sideEffectConcept"), inferLegacyConceptName(procedureAst.name()));
        List<String> affectedConcepts = splitMetadataList(metadataText(metadata, "affectedConcepts"));
        if (affectedConcepts.isEmpty()) {
            affectedConcepts = List.of(sideEffectConcept);
        }
        return new CompiledGeneratedActionDescriptorSpec(
                actionName,
                affectedConcepts,
                sideEffectConcept,
                firstNonBlank(metadataText(metadata, "eventNameOnSuccess"), defaultGeneratedActionEvent(actionName)),
                firstNonBlank(metadataText(metadata, "auditResourceType"), "GENERATED_ACTION"),
                firstNonBlank(metadataText(metadata, "idempotencyPolicy"), "record"),
                firstNonBlank(metadataText(metadata, "tracePolicy"), "record"),
                firstNonBlank(metadataText(metadata, "correlationPolicy"), "claim"),
                false
        );
    }

    private static String metadataText(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return "";
        }
        Object value = metadata.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static List<String> splitMetadataList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            String item = token == null ? "" : token.trim();
            if (!item.isBlank()) {
                out.add(item);
            }
        }
        return List.copyOf(out);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String defaultGeneratedActionEvent(String actionName) {
        String token = actionName == null || actionName.isBlank() ? "action" : actionName.trim();
        token = token.replaceAll("([a-z])([A-Z])", "$1-$2")
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("^-|-$", "")
                .toLowerCase(Locale.ROOT);
        return "generated.action." + (token.isBlank() ? "action" : token) + ".completed";
    }

    private static String inferLegacyConceptName(String actionName) {
        String cleaned = actionName == null ? "" : actionName.trim();
        if (cleaned.isBlank()) {
            return "GeneratedAction";
        }
        for (String prefix : List.of("Create", "Add", "Register", "Upsert", "Update", "Save")) {
            if (cleaned.startsWith(prefix) && cleaned.length() > prefix.length()) {
                return cleaned.substring(prefix.length());
            }
        }
        return cleaned;
    }

    private static CompiledLifecycle toCompiledLifecycle(LifecycleAst lifecycleAst) {
        if (lifecycleAst == null) {
            return null;
        }
        List<CompiledStateMachineState> states = new ArrayList<>();
        for (StateMachineStateAst stateAst : lifecycleAst.getStates()) {
            if (stateAst == null) {
                continue;
            }
            states.add(new CompiledStateMachineState(
                    stateAst.getValue(),
                    stateAst.getLabel(),
                    stateAst.isInitial(),
                    stateAst.isTerminal(),
                    stateAst.getMetadata()
            ));
        }
        List<CompiledStateTransition> transitions = new ArrayList<>();
        for (StateTransitionAst transitionAst : lifecycleAst.getTransitions()) {
            if (transitionAst == null) {
                continue;
            }
            List<String> requiredPayload = new ArrayList<>(transitionAst.getRequiredPayload());
            requiredPayload.sort(String.CASE_INSENSITIVE_ORDER);
            transitions.add(new CompiledStateTransition(
                    transitionAst.getFrom(),
                    transitionAst.getTo(),
                    requiredPayload,
                    transitionAst.getEvent(),
                    transitionAst.getGuard(),
                    transitionAst.getActionLabel(),
                    transitionAst.getMetadata(),
                    toCompiledActionMetadata(transitionAst.getAction())
            ));
        }
        transitions.sort(Comparator
                .comparing((CompiledStateTransition transition) -> normalize(transition.getFrom()))
                .thenComparing(transition -> normalize(transition.getTo())));
        return new CompiledLifecycle(lifecycleAst.getStatusField(), states, transitions);
    }

    private static CompiledSchema toCompiledSchema(SchemaAst schemaAst) {
        if (schemaAst == null) {
            return null;
        }
        Map<String, CompiledSchema> properties = new LinkedHashMap<>();
        List<Map.Entry<String, SchemaAst>> propertyEntries = new ArrayList<>(schemaAst.getProperties().entrySet());
        propertyEntries.sort(Comparator.comparing(entry -> normalize(entry.getKey())));
        for (Map.Entry<String, SchemaAst> entry : propertyEntries) {
            properties.put(entry.getKey(), toCompiledSchema(entry.getValue()));
        }
        List<String> required = new ArrayList<>(schemaAst.getRequired());
        required.sort(String.CASE_INSENSITIVE_ORDER);
        List<String> enumValues = new ArrayList<>(schemaAst.getEnumValues());
        enumValues.sort(String.CASE_INSENSITIVE_ORDER);
        return new CompiledSchema(
                schemaAst.getType(),
                properties,
                toCompiledSchema(schemaAst.getItems()),
                required,
                enumValues,
                schemaAst.getDefaultValue(),
                schemaAst.getDefaultExpression(),
                schemaAst.getDerivedExpression(),
                schemaAst.getDescription(),
                schemaAst.getMinLength(),
                schemaAst.getMaxLength(),
                schemaAst.getMinItems(),
                schemaAst.getMaxItems(),
                schemaAst.getUniqueItems(),
                schemaAst.getItemIdentityField(),
                schemaAst.getDuplicationPolicy(),
                schemaAst.getMin(),
                schemaAst.getMax(),
                schemaAst.getRegex()
        );
    }

    private static SchemaAst mergeSchemas(SchemaAst base, SchemaAst override, String fallbackType) {
        if (base == null && override == null && (fallbackType == null || fallbackType.isBlank())) {
            return null;
        }
        String type = firstNonBlank(
                override == null ? null : override.getType(),
                firstNonBlank(base == null ? null : base.getType(), fallbackType)
        );
        Map<String, SchemaAst> properties = new LinkedHashMap<>();
        if (base != null) {
            properties.putAll(base.getProperties());
        }
        if (override != null) {
            for (Map.Entry<String, SchemaAst> entry : override.getProperties().entrySet()) {
                properties.put(
                        entry.getKey(),
                        mergeSchemas(
                                properties.get(entry.getKey()),
                                entry.getValue(),
                                entry.getValue() == null ? null : entry.getValue().getType()
                        )
                );
            }
        }
        SchemaAst items = null;
        if (base != null || override != null) {
            SchemaAst baseItems = base == null ? null : base.getItems();
            SchemaAst overrideItems = override == null ? null : override.getItems();
            items = mergeSchemas(baseItems, overrideItems, null);
        }
        List<String> required = chooseList(override == null ? null : override.getRequired(), base == null ? null : base.getRequired());
        List<String> enumValues = chooseList(override == null ? null : override.getEnumValues(), base == null ? null : base.getEnumValues());
        Object defaultValue = override != null && override.getDefaultValue() != null
                ? override.getDefaultValue()
                : (base == null ? null : base.getDefaultValue());
        String defaultExpression = chooseString(
                override == null ? null : override.getDefaultExpression(),
                base == null ? null : base.getDefaultExpression()
        );
        String derivedExpression = chooseString(
                override == null ? null : override.getDerivedExpression(),
                base == null ? null : base.getDerivedExpression()
        );
        return new SchemaAst(
                type,
                properties,
                items,
                required,
                enumValues,
                defaultValue,
                defaultExpression,
                derivedExpression,
                chooseString(override == null ? null : override.getDescription(), base == null ? null : base.getDescription()),
                chooseInteger(override == null ? null : override.getMinLength(), base == null ? null : base.getMinLength()),
                chooseInteger(override == null ? null : override.getMaxLength(), base == null ? null : base.getMaxLength()),
                chooseInteger(override == null ? null : override.getMinItems(), base == null ? null : base.getMinItems()),
                chooseInteger(override == null ? null : override.getMaxItems(), base == null ? null : base.getMaxItems()),
                chooseBoolean(override == null ? null : override.getUniqueItems(), base == null ? null : base.getUniqueItems()),
                chooseString(override == null ? null : override.getItemIdentityField(), base == null ? null : base.getItemIdentityField()),
                chooseString(override == null ? null : override.getDuplicationPolicy(), base == null ? null : base.getDuplicationPolicy()),
                chooseDouble(override == null ? null : override.getMin(), base == null ? null : base.getMin()),
                chooseDouble(override == null ? null : override.getMax(), base == null ? null : base.getMax()),
                chooseString(override == null ? null : override.getRegex(), base == null ? null : base.getRegex())
        );
    }

    private static List<CompiledFlowStep> compileFlowSteps(
            List<StepAst> steps,
            Map<String, String> capabilityTypesByName,
            Map<String, Map<String, CompiledCapabilityOperation>> operationsByCapability,
            String flowConcept,
            Map<String, List<String>> invariantRefsByConcept,
            Map<String, Map<String, String>> invariantRefAliasByConcept
    ) {
        List<CompiledFlowStep> out = new ArrayList<>();
        for (StepAst stepAst : steps) {
            CompiledCapabilityCall capabilityCall = null;
            String stepType = normalize(stepAst.getType());
            if (stepAst.getGeneratedActionName() != null && !stepAst.getGeneratedActionName().isBlank()) {
                stepType = "generatedAction";
            }
            String resolvedScope = stepAst.getScope();
            List<String> resolvedInvariantRefs = stepAst.getInvariants();

            if ("invariant".equals(stepType)) {
                String conceptName = (resolvedScope == null || resolvedScope.isBlank())
                        ? flowConcept
                        : resolvedScope;
                resolvedScope = conceptName;
                String conceptKey = normalize(conceptName);
                Map<String, String> aliasByRef = invariantRefAliasByConcept.getOrDefault(conceptKey, Map.of());

                if (resolvedInvariantRefs == null || resolvedInvariantRefs.isEmpty()) {
                    resolvedInvariantRefs = invariantRefsByConcept.getOrDefault(conceptKey, List.of());
                } else {
                    List<String> canonicalRefs = new ArrayList<>();
                    for (String invariantRef : resolvedInvariantRefs) {
                        String canonical = aliasByRef.getOrDefault(normalize(invariantRef), invariantRef);
                        canonicalRefs.add(canonical);
                    }
                    resolvedInvariantRefs = canonicalRefs;
                }
                List<String> sortedInvariantRefs = new ArrayList<>(resolvedInvariantRefs);
                sortedInvariantRefs.sort(String.CASE_INSENSITIVE_ORDER);
                resolvedInvariantRefs = sortedInvariantRefs;
            }

            if (isCapabilityLikeStep(stepType)) {
                String capabilityName = resolveCapabilityNameForStep(stepAst, stepType);
                String operationName = resolveOperationNameForStep(stepAst, stepType);
                List<String> argsRefs = stepAst.getArgs();
                if (argsRefs == null || argsRefs.isEmpty()) {
                    String inputRef = stepAst.getInput();
                    argsRefs = (inputRef == null || inputRef.isBlank()) ? List.of() : List.of(inputRef);
                }
                CompiledCapabilityExecutionPolicy stepPolicy = stepAst.getCapabilityPolicy() == null
                        ? null
                        : toCompiledPolicy(stepAst.getCapabilityPolicy());
                capabilityCall = new CompiledCapabilityCall(
                        capabilityName,
                        resolveCapabilityTypeForStep(stepType, capabilityName, capabilityTypesByName),
                        resolveAdapterIdForStep(stepType),
                        operationName,
                        argsRefs,
                        stepAst.getInput(),
                        stepAst.getOutput(),
                        resolveOperationSchema(
                                operationsByCapability,
                                capabilityName,
                                operationName,
                                true
                        ),
                        resolveOperationSchema(
                                operationsByCapability,
                                capabilityName,
                                operationName,
                                false
                        ),
                        mergeCapabilityPolicies(
                                resolveOperationPolicy(operationsByCapability, capabilityName, operationName),
                                stepPolicy
                        )
                );
            }

            List<CompiledFlowStep> thenSteps = compileFlowSteps(
                    stepAst.getThenSteps(),
                    capabilityTypesByName,
                    operationsByCapability,
                    flowConcept,
                    invariantRefsByConcept,
                    invariantRefAliasByConcept
            );
            List<CompiledFlowStep> elseSteps = compileFlowSteps(
                    stepAst.getElseSteps(),
                    capabilityTypesByName,
                    operationsByCapability,
                    flowConcept,
                    invariantRefsByConcept,
                    invariantRefAliasByConcept
            );
            List<CompiledFlowStep> loopSteps = compileFlowSteps(
                    stepAst.getLoopSteps(),
                    capabilityTypesByName,
                    operationsByCapability,
                    flowConcept,
                    invariantRefsByConcept,
                    invariantRefAliasByConcept
            );
            List<CompiledFlowStep> onFailureSteps = compileFlowSteps(
                    stepAst.getOnFailureSteps(),
                    capabilityTypesByName,
                    operationsByCapability,
                    flowConcept,
                    invariantRefsByConcept,
                    invariantRefAliasByConcept
            );

            out.add(new CompiledFlowStep(
                    stepAst.getName(),
                    stepAst.getType(),
                    stepAst.getCheckpoint(),
                    resolvedScope,
                    resolvedInvariantRefs,
                    stepAst.getEvent(),
                    stepAst.getPayload(),
                    sortByKey(stepAst.getData()),
                    stepAst.getCondition(),
                    thenSteps,
                    elseSteps,
                    stepAst.getAwaitEvent(),
                    stepAst.getAwaitRef(),
                    stepAst.getAwaitMatchCorrelation(),
                    sortByKey(stepAst.getAwaitPayloadMatch()),
                    stepAst.getDelaySeconds(),
                    stepAst.getInput(),
                    stepAst.getOutput(),
                    stepAst.getReturnValue(),
                    capabilityCall,
                    toCompiledActionMetadata(stepAst.getAction()),
                    stepAst.getGeneratedActionName(),
                    stepAst.getCollectionRef(),
                    stepAst.getItemKey(),
                    loopSteps,
                    stepAst.getMaxLoopIterations(),
                    onFailureSteps,
                    stepAst.getProcedure()
            ));
        }
        return out;
    }

    private static CompiledActionMetadata toCompiledActionMetadata(ActionMetadataAst actionMetadata) {
        if (actionMetadata == null) {
            return null;
        }
        return new CompiledActionMetadata(
                actionMetadata.getLabel(),
                actionMetadata.getConfirmationText(),
                actionMetadata.getSuccessMessage(),
                actionMetadata.getFailureHint(),
                actionMetadata.getDangerLevel(),
                actionMetadata.getVisibleWhen(),
                actionMetadata.getPermissionHint(),
                actionMetadata.getInputFormHint()
        );
    }

    private static CompiledFlowSchedule toCompiledFlowSchedule(FlowScheduleAst schedule) {
        if (schedule == null) {
            return null;
        }
        return new CompiledFlowSchedule(schedule.getCron(), schedule.getTenantScope());
    }

    private static List<CompiledProcedureParameter> compileProcedureParameters(List<ProcedureParameterAst> parameters) {
        List<CompiledProcedureParameter> out = new ArrayList<>();
        if (parameters == null) {
            return out;
        }
        for (ProcedureParameterAst parameter : parameters) {
            out.add(new CompiledProcedureParameter(
                    parameter.name(),
                    parameter.type(),
                    parameter.required(),
                    toCompiledSchema(parameter.schema()),
                    parameter.description()
            ));
        }
        out.sort(Comparator.comparing(parameter -> normalize(parameter.name())));
        return out;
    }

    private static List<CompiledProcedureVariable> compileProcedureVariables(List<ProcedureVariableAst> variables) {
        List<CompiledProcedureVariable> out = new ArrayList<>();
        if (variables == null) {
            return out;
        }
        for (ProcedureVariableAst variable : variables) {
            out.add(new CompiledProcedureVariable(
                    variable.name(),
                    variable.type(),
                    toCompiledSchema(variable.schema()),
                    variable.initialValue()
            ));
        }
        out.sort(Comparator.comparing(variable -> normalize(variable.name())));
        return out;
    }

    private static List<CompiledProcedureStep> compileProcedureSteps(List<ProcedureStepAst> steps) {
        List<CompiledProcedureStep> out = new ArrayList<>();
        if (steps == null) {
            return out;
        }
        for (ProcedureStepAst step : steps) {
            out.add(new CompiledProcedureStep(
                    step.name(),
                    step.type(),
                    step.target(),
                    step.value(),
                    step.condition(),
                    step.items(),
                    step.as(),
                    step.concept(),
                    step.query(),
                    sortObjectMap(step.data()),
                    step.id(),
                    step.procedure(),
                    step.flow(),
                    step.capability(),
                    step.operation(),
                    step.event(),
                    sortObjectMap(step.args()),
                    compileProcedureSteps(step.thenSteps()),
                    compileProcedureSteps(step.elseSteps()),
                    compileProcedureSteps(step.steps()),
                    step.trace(),
                    step.audit(),
                    sortObjectMap(step.metadata()),
                    sortObjectMap(step.set()),
                    step.createIfMissing(),
                    sortObjectMap(step.select()),
                    step.left(),
                    step.right()
            ));
        }
        return out;
    }

    private static CompiledPanel compilePanel(PanelAst panelAst) {
        return new CompiledPanel(
                panelAst.name(),
                panelAst.route(),
                panelAst.title(),
                compilePanelDataSources(panelAst.dataSources()),
                compilePanelLayout(panelAst.layout()),
                compilePanelFieldBindings(panelAst.fieldBindings()),
                panelAst.visibility(),
                panelAst.enabledWhen(),
                compilePanelActions(panelAst.actions()),
                sortObjectMap(panelAst.explainability()),
                sortObjectMap(panelAst.metadata()),
                panelAst.guidePage()
        );
    }

    private static List<CompiledPanelDataSource> compilePanelDataSources(List<PanelDataSourceAst> dataSources) {
        List<CompiledPanelDataSource> out = new ArrayList<>();
        if (dataSources == null) {
            return out;
        }
        for (PanelDataSourceAst dataSource : dataSources) {
            List<String> rowOps = new ArrayList<>(dataSource.rowOps());
            rowOps.replaceAll(op -> op == null ? "" : op.trim().toLowerCase(Locale.ROOT));
            rowOps.sort(String.CASE_INSENSITIVE_ORDER);
            out.add(new CompiledPanelDataSource(
                    dataSource.name(),
                    dataSource.concept(),
                    dataSource.query(),
                    dataSource.procedure(),
                    sortObjectMap(dataSource.params()),
                    dataSource.parentDataSource(),
                    dataSource.parentField(),
                    dataSource.childField(),
                    List.copyOf(rowOps),
                    List.copyOf(dataSource.addFormFields()),
                    dataSource.onRowLoad()
            ));
        }
        out.sort(Comparator.comparing(dataSource -> normalize(dataSource.name())));
        return out;
    }

    private static CompiledPanelLayout compilePanelLayout(PanelLayoutAst layout) {
        if (layout == null) {
            return null;
        }
        List<CompiledPanelLayout> children = new ArrayList<>();
        for (PanelLayoutAst child : layout.children()) {
            children.add(compilePanelLayout(child));
        }
        return new CompiledPanelLayout(
                layout.type(),
                children,
                copyStrings(layout.fields()),
                sortObjectMap(layout.metadata())
        );
    }

    private static List<CompiledPanelFieldBinding> compilePanelFieldBindings(List<PanelFieldBindingAst> bindings) {
        List<CompiledPanelFieldBinding> out = new ArrayList<>();
        if (bindings == null) {
            return out;
        }
        for (PanelFieldBindingAst binding : bindings) {
            out.add(new CompiledPanelFieldBinding(
                    binding.field(),
                    binding.source(),
                    binding.visibleWhen(),
                    binding.enabledWhen(),
                    binding.readonlyWhen(),
                    toCompiledPresentationMetadata(binding.ui()),
                    binding.editable()
            ));
        }
        out.sort(Comparator.comparing(binding -> normalize(binding.field())));
        return out;
    }

    private static List<CompiledPanelAction> compilePanelActions(List<PanelActionAst> actions) {
        List<CompiledPanelAction> out = new ArrayList<>();
        if (actions == null) {
            return out;
        }
        for (PanelActionAst action : actions) {
            out.add(new CompiledPanelAction(
                    action.name(),
                    action.label(),
                    action.binding(),
                    action.concept(),
                    action.operation(),
                    action.procedure(),
                    action.flow(),
                    action.visibleWhen(),
                    action.enabledWhen(),
                    sortedStrings(action.permissionRequirements()),
                    sortObjectMap(action.explainability()),
                    sortObjectMap(action.metadata()),
                    action.scope(),
                    action.dataSource(),
                    sortedStrings(action.inputFields()),
                    action.resultAs(),
                    action.filename(),
                    action.contentType()
            ));
        }
        out.sort(Comparator.comparing(action -> normalize(action.name())));
        return out;
    }

    private static List<String> copyStrings(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values == null || values.isEmpty()) {
            return out;
        }
        out.addAll(values);
        return out;
    }

    private static List<String> sortedStrings(List<String> values) {
        List<String> out = copyStrings(values);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static Map<String, Object> sortObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        List<Map.Entry<String, Object>> entries = new ArrayList<>(source.entrySet());
        entries.sort(Comparator.comparing(entry -> normalize(entry.getKey())));
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) {
            out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    private static CompiledReferenceSemantics toCompiledReferenceSemantics(ReferenceSemanticsAst referenceSemantics) {
        if (referenceSemantics == null) {
            return null;
        }
        return new CompiledReferenceSemantics(
                referenceSemantics.getTarget(),
                referenceSemantics.isMultiple(),
                referenceSemantics.getDisplayField(),
                referenceSemantics.getSearchFields(),
                referenceSemantics.getPreviewFields(),
                referenceSemantics.getInlineCreatePolicy(),
                referenceSemantics.getDisplayTemplate(),
                referenceSemantics.getPickerColumns(),
                referenceSemantics.getPreviewCardTemplate(),
                referenceSemantics.getDefaultFilter(),
                referenceSemantics.getVia(),
                referenceSemantics.getOnDelete()
        );
    }

    private static CompiledFileMetadata toCompiledFileMetadata(FileMetadataAst file) {
        if (file == null) {
            return null;
        }
        return new CompiledFileMetadata(file.contentTypes(), file.maxSizeBytes(), file.multiple());
    }

    /** B16/B19 (Move 9 A3): compiles a field's declared picker filter/multiSelect. */
    private static CompiledFieldPicker toCompiledFieldPicker(FieldPickerAst picker) {
        if (picker == null) {
            return null;
        }
        return new CompiledFieldPicker(picker.filter(), picker.multiSelect());
    }

    private static CompiledPresentationMetadata toCompiledPresentationMetadata(PresentationMetadataAst metadata) {
        if (metadata == null) {
            return null;
        }
        return new CompiledPresentationMetadata(
                metadata.getLabel(),
                metadata.getShortLabel(),
                metadata.getDescription(),
                metadata.getHelpText(),
                metadata.getPlaceholder(),
                metadata.getGroup(),
                metadata.getSection(),
                metadata.getOrder(),
                metadata.getAdvanced(),
                metadata.getDeprecated(),
                metadata.getExamples(),
                metadata.getWidget(),
                metadata.getVisibleWhen(),
                metadata.getEnabledWhen(),
                metadata.getReadonlyWhen(),
                metadata.getRequiredWhen(),
                metadata.getPickerType(),
                metadata.getAllowInlineCreate(),
                metadata.getSearchFields(),
                metadata.getFilterPreset(),
                metadata.getTab(),
                metadata.getColumn(),
                metadata.getColumnSpan(),
                metadata.getWidth(),
                metadata.getSummaryCard(),
                metadata.getListColumn(),
                metadata.getShowInDefaultWebUi(),
                metadata.getListColumnOrder(),
                metadata.getFormColumns(),
                metadata.getDisplayMode(),
                metadata.getFormPresentation(),
                metadata.getDefaultSort(),
                metadata.getDefaultGroup(),
                metadata.getImageField(),
                metadata.getCustomWidgetRef()
        );
    }


    private static boolean isCapabilityLikeStep(String stepType) {
        return "capability".equals(stepType)
                || "generatedAction".equalsIgnoreCase(stepType)
                || "generatedaction".equals(stepType)
                || "createEntity".equalsIgnoreCase(stepType)
                || "updateEntity".equalsIgnoreCase(stepType)
                || "createConcept".equalsIgnoreCase(stepType)
                || "updateConcept".equalsIgnoreCase(stepType);
    }

    private static String resolveCapabilityNameForStep(StepAst stepAst, String stepType) {
        if (isConceptPersistenceStep(stepType)) {
            return "persistence";
        }
        if ("generatedAction".equalsIgnoreCase(stepType) || "generatedaction".equals(stepType)) {
            return stepAst.getCapability();
        }
        return stepAst.getCapability();
    }

    private static String resolveOperationNameForStep(StepAst stepAst, String stepType) {
        if (isConceptPersistenceStep(stepType)) {
            return "save";
        }
        if ("generatedAction".equalsIgnoreCase(stepType) || "generatedaction".equals(stepType)) {
            return "run";
        }
        return stepAst.getOperation();
    }

    private static String resolveCapabilityTypeForStep(
            String stepType,
            String capabilityName,
            Map<String, String> capabilityTypesByName
    ) {
        if ("generatedAction".equalsIgnoreCase(stepType) || "generatedaction".equals(stepType)) {
            return "GeneratedActionCapability";
        }
        return capabilityTypesByName.get(normalize(capabilityName));
    }

    private static String resolveAdapterIdForStep(String stepType) {
        if ("generatedAction".equalsIgnoreCase(stepType) || "generatedaction".equals(stepType)) {
            return "generated-action";
        }
        return null;
    }

    private static boolean isConceptPersistenceStep(String stepType) {
        return "createEntity".equalsIgnoreCase(stepType)
                || "updateEntity".equalsIgnoreCase(stepType)
                || "createConcept".equalsIgnoreCase(stepType)
                || "updateConcept".equalsIgnoreCase(stepType);
    }

    private static CompiledCapabilityExecutionPolicy toCompiledPolicy(CapabilityPolicyAst policyAst) {
        if (policyAst == null) {
            return CompiledCapabilityExecutionPolicy.defaults();
        }
        int retryCount = policyAst.getRetryCount() == null ? 1 : policyAst.getRetryCount();
        long retryDelayMs = policyAst.getRetryDelayMs() == null ? 0L : policyAst.getRetryDelayMs();
        long timeoutMs = policyAst.getTimeoutMs() == null ? 0L : policyAst.getTimeoutMs();
        int circuitOpenAfterFailures = policyAst.getCircuitOpenAfterFailures() == null
                ? 0
                : policyAst.getCircuitOpenAfterFailures();
        long circuitOpenMs = policyAst.getCircuitOpenMs() == null ? 0L : policyAst.getCircuitOpenMs();
        int bulkheadMaxConcurrent = policyAst.getBulkheadMaxConcurrent() == null
                ? 0
                : policyAst.getBulkheadMaxConcurrent();
        return new CompiledCapabilityExecutionPolicy(
                retryCount,
                retryDelayMs,
                timeoutMs,
                circuitOpenAfterFailures,
                circuitOpenMs,
                bulkheadMaxConcurrent,
                policyAst.getIdempotencyKeyField(),
                policyAst.getFailureClassification()
        );
    }

    private static CompiledSchema resolveOperationSchema(
            Map<String, Map<String, CompiledCapabilityOperation>> operationsByCapability,
            String capabilityName,
            String operationName,
            boolean input
    ) {
        CompiledCapabilityOperation operation = resolveOperation(
                operationsByCapability,
                capabilityName,
                operationName
        );
        if (operation == null) {
            return null;
        }
        return input ? operation.getInputSchema() : operation.getOutputSchema();
    }

    private static CompiledCapabilityExecutionPolicy resolveOperationPolicy(
            Map<String, Map<String, CompiledCapabilityOperation>> operationsByCapability,
            String capabilityName,
            String operationName
    ) {
        CompiledCapabilityOperation operation = resolveOperation(
                operationsByCapability,
                capabilityName,
                operationName
        );
        if (operation == null) {
            return CompiledCapabilityExecutionPolicy.defaults();
        }
        return operation.getExecutionPolicy();
    }

    private static CompiledCapabilityOperation resolveOperation(
            Map<String, Map<String, CompiledCapabilityOperation>> operationsByCapability,
            String capabilityName,
            String operationName
    ) {
        if (capabilityName == null || operationName == null) {
            return null;
        }
        Map<String, CompiledCapabilityOperation> operations = operationsByCapability.get(normalize(capabilityName));
        if (operations == null || operations.isEmpty()) {
            return null;
        }
        return operations.get(normalize(operationName));
    }

    private static CompiledCapabilityExecutionPolicy mergeCapabilityPolicies(
            CompiledCapabilityExecutionPolicy operationPolicy,
            CompiledCapabilityExecutionPolicy stepPolicy
    ) {
        CompiledCapabilityExecutionPolicy op = operationPolicy == null
                ? CompiledCapabilityExecutionPolicy.defaults()
                : operationPolicy;
        CompiledCapabilityExecutionPolicy step = stepPolicy == null
                ? CompiledCapabilityExecutionPolicy.defaults()
                : stepPolicy;

        return new CompiledCapabilityExecutionPolicy(
                choosePositive(step.getRetryCount(), op.getRetryCount(), 1),
                chooseNonNegative(step.getRetryDelayMs(), op.getRetryDelayMs(), 0L),
                chooseNonNegative(step.getTimeoutMs(), op.getTimeoutMs(), 0L),
                choosePositive(step.getCircuitOpenAfterFailures(), op.getCircuitOpenAfterFailures(), 0),
                chooseNonNegative(step.getCircuitOpenMs(), op.getCircuitOpenMs(), 0L),
                choosePositive(step.getBulkheadMaxConcurrent(), op.getBulkheadMaxConcurrent(), 0),
                chooseNonBlank(step.getIdempotencyKeyField(), op.getIdempotencyKeyField()),
                chooseNonBlank(step.getFailureClassification(), op.getFailureClassification())
        );
    }

    private static int choosePositive(int primary, int secondary, int fallback) {
        if (primary > 0) {
            return primary;
        }
        if (secondary > 0) {
            return secondary;
        }
        return fallback;
    }

    private static long chooseNonNegative(long primary, long secondary, long fallback) {
        if (primary > 0L) {
            return primary;
        }
        if (secondary > 0L) {
            return secondary;
        }
        return fallback;
    }

    private static String chooseNonBlank(String primary, String secondary) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (secondary != null && !secondary.isBlank()) {
            return secondary;
        }
        return null;
    }

    private static String invariantCanonicalRef(InvariantAst invariant) {
        String namedRef = invariant.getName();
        if (namedRef != null && !namedRef.isBlank()) {
            return namedRef.trim();
        }

        if ("unique".equalsIgnoreCase(invariant.getType())
                && invariant.getFields() != null
                && !invariant.getFields().isEmpty()) {
            return "unique(" + String.join(",", invariant.getFields()) + ")";
        }

        String expression = invariant.getExpression();
        if ("expression".equalsIgnoreCase(invariant.getType())
                && expression != null
                && !expression.isBlank()) {
            return expression.trim();
        }

        return normalize(invariant.getType());
    }

    private static void registerInvariant(
            Map<String, CompiledInvariant> invariantsByCanonicalRef,
            Map<String, String> invariantRefAlias,
            String canonicalRef,
            String type,
            String field,
            String expression,
            List<String> aliases
    ) {
        registerInvariant(invariantsByCanonicalRef, invariantRefAlias, canonicalRef, type, field, expression, null, aliases);
    }

    private static void registerInvariant(
            Map<String, CompiledInvariant> invariantsByCanonicalRef,
            Map<String, String> invariantRefAlias,
            String canonicalRef,
            String type,
            String field,
            String expression,
            List<String> fields,
            List<String> aliases
    ) {
        if (canonicalRef == null || canonicalRef.isBlank()) {
            return;
        }

        invariantsByCanonicalRef.putIfAbsent(
                canonicalRef,
                new CompiledInvariant(canonicalRef, type, field, expression, fields)
        );
        invariantRefAlias.put(normalize(canonicalRef), canonicalRef);
        if (aliases == null || aliases.isEmpty()) {
            return;
        }
        for (String alias : aliases) {
            if (alias != null && !alias.isBlank()) {
                invariantRefAlias.put(normalize(alias), canonicalRef);
            }
        }
    }

    private static Map<String, String> sortByKey(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(source.entrySet());
        entries.sort(Comparator.comparing(entry -> normalize(entry.getKey())));
        Map<String, String> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries) {
            sorted.put(entry.getKey(), entry.getValue());
        }
        return sorted;
    }

    private static String chooseString(String primary, String secondary) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (secondary != null && !secondary.isBlank()) {
            return secondary;
        }
        return null;
    }

    private static Integer chooseInteger(Integer primary, Integer secondary) {
        return primary != null ? primary : secondary;
    }

    private static Boolean chooseBoolean(Boolean primary, Boolean secondary) {
        return primary != null ? primary : secondary;
    }

    private static Double chooseDouble(Double primary, Double secondary) {
        return primary != null ? primary : secondary;
    }

    private static List<String> chooseList(List<String> primary, List<String> secondary) {
        if (primary != null && !primary.isEmpty()) {
            return primary;
        }
        if (secondary != null && !secondary.isEmpty()) {
            return secondary;
        }
        return List.of();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private record EffectiveEntityDef(List<FieldAst> fields, List<InvariantAst> invariants, LifecycleAst lifecycle) {
        private EffectiveEntityDef {
            fields = fields == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(fields));
            invariants = invariants == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(invariants));
            lifecycle = lifecycle == null
                    ? null
                    : new LifecycleAst(lifecycle.getStatusField(), lifecycle.getStates(), lifecycle.getTransitions());
    }
}
}
