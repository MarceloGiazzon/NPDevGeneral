package com.npdev.dsl.v1.resolution;

import com.npdev.dsl.v1.ast.AggregateAst;
import com.npdev.dsl.v1.ast.AutoPanelAst;
import com.npdev.dsl.v1.ast.CapabilityAst;
import com.npdev.dsl.v1.ast.CapabilityBindingAst;
import com.npdev.dsl.v1.ast.ConceptAccessAst;
import com.npdev.dsl.v1.ast.CapabilityOperationAst;
import com.npdev.dsl.v1.ast.ActionMetadataAst;
import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.DocumentAst;
import com.npdev.dsl.v1.ast.DomainTypeAst;
import com.npdev.dsl.v1.ast.DomainTypeUiAst;
import com.npdev.dsl.v1.ast.EventAst;
import com.npdev.dsl.v1.ast.EventPayloadAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.FlowHookAst;
import com.npdev.dsl.v1.ast.FlowScheduleAst;
import com.npdev.dsl.v1.ast.IndexAst;
import com.npdev.dsl.v1.ast.InvariantAst;
import com.npdev.dsl.v1.ast.LifecycleAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.OrchestrationActionAst;
import com.npdev.dsl.v1.ast.OrchestrationAst;
import com.npdev.dsl.v1.ast.OrchestrationTriggerAst;
import com.npdev.dsl.v1.ast.PresentationMetadataAst;
import com.npdev.dsl.v1.ast.PanelAst;
import com.npdev.dsl.v1.ast.GuidePageAst;
import com.npdev.dsl.v1.ast.ProcedureAst;
import com.npdev.dsl.v1.ast.QueryAst;
import com.npdev.dsl.v1.ast.RuleProfileAst;
import com.npdev.dsl.v1.ast.StateMachineStateAst;
import com.npdev.dsl.v1.ast.StateTransitionAst;
import com.npdev.dsl.v1.ast.StepAst;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic specialization resolver.
 * Produces a fully-expanded model used as compiler input.
 */
public final class ModelResolver {

    public ResolvedModel resolve(ModelAst source) {
        Objects.requireNonNull(source, "source");
        Map<String, ConceptAst> conceptsByName = indexByName(source.getConcepts(), ConceptAst::getName, "concept");
        Map<String, CapabilityAst> capabilitiesByName = indexByName(source.getCapabilities(), CapabilityAst::getName, "capability");
        Map<String, EventAst> eventsByName = indexByName(source.getEvents(), EventAst::getName, "event");
        Map<String, FlowAst> flowsByName = indexByName(source.getFlows(), FlowAst::getName, "flow");
        Map<String, OrchestrationAst> orchestrationByName = indexByName(
                source.getOrchestrationRules(),
                OrchestrationAst::getName,
                "orchestration rule"
        );

        List<ConceptAst> resolvedConcepts = resolveConcepts(conceptsByName);
        List<DomainTypeAst> resolvedDomainTypes = resolveDomainTypes(source.getDomainTypes());
        List<CapabilityAst> resolvedCapabilities = resolveCapabilities(capabilitiesByName);
        List<EventAst> resolvedEvents = resolveEvents(eventsByName);
        List<FlowAst> resolvedFlows = resolveFlows(flowsByName);
        List<OrchestrationAst> resolvedOrchestrationRules = resolveOrchestrationRules(orchestrationByName);
        List<QueryAst> resolvedQueries = new ArrayList<>(source.getQueries());
        resolvedQueries.sort(Comparator.comparing(query -> normalize(query.name())));
        List<RuleProfileAst> resolvedRuleProfiles = new ArrayList<>(source.getRuleProfiles());
        resolvedRuleProfiles.sort(Comparator.comparing(profile -> normalize(profile.name())));
        List<ProcedureAst> resolvedProcedures = new ArrayList<>(source.getProcedures());
        resolvedProcedures.sort(Comparator.comparing(procedure -> normalize(procedure.name())));
        List<PanelAst> resolvedPanels = new ArrayList<>(source.getPanels());
        resolvedPanels.sort(Comparator.comparing(panel -> normalize(panel.name())));
        List<GuidePageAst> resolvedGuidePages = new ArrayList<>(source.getGuidePages());
        resolvedGuidePages.sort(Comparator.comparing(page -> normalize(page.name())));
        List<CapabilityBindingAst> resolvedBindings = new ArrayList<>(source.getBindings());
        resolvedBindings.sort(Comparator
                .comparing((CapabilityBindingAst binding) -> normalize(binding.getCapability()))
                .thenComparing(binding -> normalize(binding.getAdapter())));
        List<DocumentAst> resolvedDocuments = new ArrayList<>(source.getDocuments());
        resolvedDocuments.sort(Comparator.comparing(document -> normalize(document.name())));

        ModelAst resolvedAst = new ModelAst(
                source.getNamespace(),
                source.getDslVersion(),
                source.getVersion(),
                resolvedConcepts,
                resolvedDomainTypes,
                resolvedCapabilities,
                resolvedBindings,
                resolvedEvents,
                resolvedFlows,
                resolvedOrchestrationRules,
                resolvedQueries,
                resolvedRuleProfiles,
                resolvedProcedures,
                resolvedPanels,
                resolvedGuidePages,
                source.getAggregates(),
                source.getAutoPanels(),
                source.getSelectors(),
                resolvedDocuments,
                source.getParserWarnings()
        );
        return ResolvedModel.from(resolvedAst);
    }

    private List<ConceptAst> resolveConcepts(Map<String, ConceptAst> conceptsByName) {
        Map<String, ConceptAst> cache = new LinkedHashMap<>();
        for (String conceptKey : sortedKeys(conceptsByName)) {
            resolveConcept(conceptKey, conceptsByName, cache, new LinkedHashSet<>());
        }
        return new ArrayList<>(cache.values());
    }

    private List<DomainTypeAst> resolveDomainTypes(List<DomainTypeAst> sourceDomainTypes) {
        List<DomainTypeAst> resolved = new ArrayList<>();
        for (DomainTypeAst domainType : sourceDomainTypes) {
            if (domainType == null) {
                continue;
            }
            DomainTypeUiAst ui = domainType.getUi() == null
                    ? null
                    : new DomainTypeUiAst(
                    domainType.getUi().getLabel(),
                    domainType.getUi().getPlaceholder(),
                    domainType.getUi().getHelpText(),
                    domainType.getUi().getWidget()
            );
            resolved.add(new DomainTypeAst(
                    domainType.getName(),
                    domainType.getBaseType(),
                    domainType.getValidationSchema(),
                    domainType.getNormalizationRules(),
                    domainType.getFormatHint(),
                    domainType.getExamples(),
                    ui
            ));
        }
        resolved.sort(Comparator.comparing(domainType -> normalize(domainType.getName())));
        return resolved;
    }

    private ConceptAst resolveConcept(
            String conceptKey,
            Map<String, ConceptAst> conceptsByName,
            Map<String, ConceptAst> cache,
            Set<String> stack
    ) {
        ConceptAst cached = cache.get(conceptKey);
        if (cached != null) {
            return cached;
        }
        ConceptAst concept = conceptsByName.get(conceptKey);
        if (concept == null) {
            throw diagnostic(ResolutionDiagnosticCode.BASE_NOT_FOUND, "Concept not found: " + conceptKey);
        }
        if (!stack.add(conceptKey)) {
            throw diagnostic(ResolutionDiagnosticCode.ILLEGAL_OVERRIDE,
                    "Concept specialization cycle detected: " + concept.getName());
        }

        String baseRef = firstNonBlank(concept.getSpecializesName(), concept.getExtendsName());
        ConceptAst resolved;
        if (baseRef == null) {
            resolved = sanitizeConcept(concept);
        } else {
            String baseKey = normalize(baseRef);
            ConceptAst base = conceptsByName.get(baseKey);
            if (base == null) {
                throw diagnostic(ResolutionDiagnosticCode.BASE_NOT_FOUND,
                        "Concept " + concept.getName() + " specializes unknown base " + baseRef);
            }
            ConceptAst resolvedBase = resolveConcept(baseKey, conceptsByName, cache, stack);
            resolved = mergeConcept(resolvedBase, concept);
        }
        stack.remove(conceptKey);
        cache.put(conceptKey, resolved);
        return resolved;
    }

    private ConceptAst sanitizeConcept(ConceptAst concept) {
        List<FieldAst> fields = uniqueFields(concept.getFields(), concept.getName());
        List<InvariantAst> invariants = sanitizeInvariants(concept.getInvariants(), concept.getName());
        List<EventAst> events = uniqueEvents(concept.getEvents(), concept.getName());
        return new ConceptAst(
                concept.getName(),
                null,
                null,
                fields,
                invariants,
                events,
                sanitizeLifecycle(concept.getLifecycle()),
                copyPresentationMetadata(concept.getUi()),
                concept.getTruthLevel(),
                concept.getModule(),
                concept.getIndexes(),
                concept.getAccess(),
                concept.getRenamedFrom()
        );
    }

    private ConceptAst mergeConcept(ConceptAst base, ConceptAst specialization) {
        List<FieldAst> mergedFields = new ArrayList<>(base.getFields());
        Map<String, FieldAst> fieldsByName = new LinkedHashMap<>();
        for (FieldAst field : mergedFields) {
            fieldsByName.put(normalize(field.getName()), field);
        }
        for (FieldAst localField : sortedFields(specialization.getFields())) {
            String fieldKey = normalize(localField.getName());
            if (fieldsByName.containsKey(fieldKey)) {
                throw diagnostic(
                        ResolutionDiagnosticCode.ILLEGAL_OVERRIDE,
                        "Concept " + specialization.getName()
                                + " cannot override base field " + localField.getName()
                                + "; specialization allows adding fields only"
                );
            }
            fieldsByName.put(fieldKey, localField);
            mergedFields.add(localField);
        }
        mergedFields = sortedFields(mergedFields);

        List<InvariantAst> mergedInvariants = mergeInvariants(base.getInvariants(), specialization.getInvariants(), specialization.getName());
        List<EventAst> mergedEvents = new ArrayList<>(base.getEvents());
        Set<String> eventNames = new LinkedHashSet<>();
        for (EventAst event : mergedEvents) {
            eventNames.add(normalize(event.getName()));
        }
        for (EventAst localEvent : sortedEvents(specialization.getEvents())) {
            String eventKey = normalize(localEvent.getName());
            if (!eventNames.add(eventKey)) {
                throw diagnostic(
                        ResolutionDiagnosticCode.CONFLICT_DUPLICATE_MEMBER,
                        "Concept " + specialization.getName()
                                + " declares duplicate event " + localEvent.getName()
                );
            }
            mergedEvents.add(sanitizeEvent(localEvent));
        }
        mergedEvents.sort(Comparator.comparing(event -> normalize(event.getName())));
        LifecycleAst mergedLifecycle = specialization.getLifecycle() != null
                ? sanitizeLifecycle(specialization.getLifecycle())
                : sanitizeLifecycle(base.getLifecycle());

        List<IndexAst> mergedIndexes = new ArrayList<>(base.getIndexes());
        mergedIndexes.addAll(specialization.getIndexes());

        // LNCH-13: specialization's own access rule wins if declared (same "specialization wins,
        // else fall back to base" pattern as module, just above); a specialization narrowing or
        // changing row-level scoping doesn't merge with the base rule -- it replaces it, since
        // ANDing an unrelated base rule in by default could silently over-restrict, and ORing it
        // in could silently under-restrict (a security-relevant default either way, so this picks
        // the least-surprising one: explicit replacement).
        ConceptAccessAst mergedAccess = specialization.getAccess() != null
                ? specialization.getAccess()
                : base.getAccess();

        return new ConceptAst(
                specialization.getName(),
                null,
                null,
                mergedFields,
                mergedInvariants,
                mergedEvents,
                mergedLifecycle,
                mergePresentationMetadata(base.getUi(), specialization.getUi()),
                specialization.getTruthLevel(),
                firstNonBlank(specialization.getModule(), base.getModule()),
                mergedIndexes,
                mergedAccess,
                specialization.getRenamedFrom()
        );
    }

    private static PresentationMetadataAst mergePresentationMetadata(
            PresentationMetadataAst base,
            PresentationMetadataAst override
    ) {
        if (base == null && override == null) {
            return null;
        }
        if (base == null) {
            return copyPresentationMetadata(override);
        }
        if (override == null) {
            return copyPresentationMetadata(base);
        }
        return new PresentationMetadataAst(
                firstNonBlank(override.getLabel(), base.getLabel()),
                firstNonBlank(override.getShortLabel(), base.getShortLabel()),
                firstNonBlank(override.getDescription(), base.getDescription()),
                firstNonBlank(override.getHelpText(), base.getHelpText()),
                firstNonBlank(override.getPlaceholder(), base.getPlaceholder()),
                firstNonBlank(override.getGroup(), base.getGroup()),
                firstNonBlank(override.getSection(), base.getSection()),
                override.getOrder() != null ? override.getOrder() : base.getOrder(),
                override.getAdvanced() != null ? override.getAdvanced() : base.getAdvanced(),
                override.getDeprecated() != null ? override.getDeprecated() : base.getDeprecated(),
                !override.getExamples().isEmpty() ? override.getExamples() : base.getExamples(),
                firstNonBlank(override.getWidget(), base.getWidget()),
                firstNonBlank(override.getVisibleWhen(), base.getVisibleWhen()),
                firstNonBlank(override.getEnabledWhen(), base.getEnabledWhen()),
                firstNonBlank(override.getReadonlyWhen(), base.getReadonlyWhen()),
                firstNonBlank(override.getRequiredWhen(), base.getRequiredWhen()),
                firstNonBlank(override.getPickerType(), base.getPickerType()),
                override.getAllowInlineCreate() != null ? override.getAllowInlineCreate() : base.getAllowInlineCreate(),
                !override.getSearchFields().isEmpty() ? override.getSearchFields() : base.getSearchFields(),
                firstNonBlank(override.getFilterPreset(), base.getFilterPreset()),
                firstNonBlank(override.getTab(), base.getTab()),
                override.getColumn() != null ? override.getColumn() : base.getColumn(),
                override.getColumnSpan() != null ? override.getColumnSpan() : base.getColumnSpan(),
                firstNonBlank(override.getWidth(), base.getWidth()),
                override.getSummaryCard() != null ? override.getSummaryCard() : base.getSummaryCard(),
                override.getListColumn() != null ? override.getListColumn() : base.getListColumn(),
                override.getShowInDefaultWebUi() != null ? override.getShowInDefaultWebUi() : base.getShowInDefaultWebUi(),
                override.getListColumnOrder() != null ? override.getListColumnOrder() : base.getListColumnOrder(),
                override.getFormColumns() != null ? override.getFormColumns() : base.getFormColumns(),
                firstNonBlank(override.getDisplayMode(), base.getDisplayMode()),
                firstNonBlank(override.getFormPresentation(), base.getFormPresentation()),
                firstNonBlank(override.getDefaultSort(), base.getDefaultSort()),
                firstNonBlank(override.getDefaultGroup(), base.getDefaultGroup()),
                firstNonBlank(override.getImageField(), base.getImageField()),
                firstNonBlank(override.getCustomWidgetRef(), base.getCustomWidgetRef())
        );
    }

    private static PresentationMetadataAst copyPresentationMetadata(PresentationMetadataAst metadata) {
        if (metadata == null) {
            return null;
        }
        return new PresentationMetadataAst(
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

    private List<InvariantAst> mergeInvariants(
            List<InvariantAst> baseInvariants,
            List<InvariantAst> localInvariants,
            String conceptName
    ) {
        List<InvariantAst> merged = new ArrayList<>();
        for (InvariantAst invariant : baseInvariants) {
            merged.add(sanitizeInvariant(invariant));
        }

        Map<String, Integer> indexByReference = new LinkedHashMap<>();
        rebuildInvariantIndex(merged, indexByReference);

        for (InvariantAst localInvariant : sanitizeInvariants(localInvariants, conceptName)) {
            String specializesRef = localInvariant.getSpecializesName();
            if (specializesRef != null && !specializesRef.isBlank()) {
                if (!localInvariant.isOverride()) {
                    throw diagnostic(
                            ResolutionDiagnosticCode.ILLEGAL_OVERRIDE,
                            "Concept " + conceptName
                                    + " invariant " + invariantDisplayName(localInvariant)
                                    + " must declare override=true when specializing " + specializesRef
                    );
                }
                String baseRefKey = normalize(specializesRef);
                Integer index = indexByReference.get(baseRefKey);
                if (index == null) {
                    throw diagnostic(
                            ResolutionDiagnosticCode.BASE_NOT_FOUND,
                            "Concept " + conceptName
                                    + " invariant override base not found: " + specializesRef
                    );
                }
                merged.set(index, stripInvariantSpecialization(localInvariant));
                rebuildInvariantIndex(merged, indexByReference);
                continue;
            }

            if (localInvariant.isOverride()) {
                throw diagnostic(
                        ResolutionDiagnosticCode.ILLEGAL_OVERRIDE,
                        "Concept " + conceptName
                                + " invariant " + invariantDisplayName(localInvariant)
                                + " sets override=true without specializes"
                );
            }
            String identity = normalize(invariantReference(localInvariant));
            if (!identity.isBlank() && indexByReference.containsKey(identity)) {
                throw diagnostic(
                        ResolutionDiagnosticCode.CONFLICT_DUPLICATE_MEMBER,
                        "Concept " + conceptName
                                + " declares duplicate invariant " + invariantDisplayName(localInvariant)
                );
            }
            merged.add(stripInvariantSpecialization(localInvariant));
            rebuildInvariantIndex(merged, indexByReference);
        }

        merged.sort(Comparator.comparing(ModelResolver::invariantSortKey));
        return List.copyOf(merged);
    }

    private static void rebuildInvariantIndex(List<InvariantAst> invariants, Map<String, Integer> indexByReference) {
        indexByReference.clear();
        for (int index = 0; index < invariants.size(); index++) {
            InvariantAst invariant = invariants.get(index);
            for (String ref : invariantLookupRefs(invariant)) {
                if (!ref.isBlank()) {
                    indexByReference.put(ref, index);
                }
            }
        }
    }

    private static List<String> invariantLookupRefs(InvariantAst invariant) {
        List<String> refs = new ArrayList<>();
        if (invariant == null) {
            return refs;
        }
        if (invariant.getName() != null && !invariant.getName().isBlank()) {
            refs.add(normalize(invariant.getName()));
        }
        String canonicalRef = invariantReference(invariant);
        if (canonicalRef != null && !canonicalRef.isBlank()) {
            refs.add(normalize(canonicalRef));
        }
        return refs;
    }

    private static String invariantReference(InvariantAst invariant) {
        if (invariant == null) {
            return "";
        }
        if (invariant.getName() != null && !invariant.getName().isBlank()) {
            return invariant.getName().trim();
        }
        if ("unique".equalsIgnoreCase(invariant.getType())
                && invariant.getFields() != null
                && invariant.getFields().size() == 1) {
            return "unique(" + invariant.getFields().get(0) + ")";
        }
        if ("expression".equalsIgnoreCase(invariant.getType())
                && invariant.getExpression() != null
                && !invariant.getExpression().isBlank()) {
            return invariant.getExpression().trim();
        }
        return invariant.getType() == null ? "" : invariant.getType().trim();
    }

    private static String invariantDisplayName(InvariantAst invariant) {
        String ref = invariantReference(invariant);
        return ref == null || ref.isBlank() ? "<unnamed>" : ref;
    }

    private static String invariantSortKey(InvariantAst invariant) {
        return normalize(invariantReference(invariant));
    }

    private List<CapabilityAst> resolveCapabilities(Map<String, CapabilityAst> capabilitiesByName) {
        Map<String, CapabilityAst> cache = new LinkedHashMap<>();
        for (String capabilityKey : sortedKeys(capabilitiesByName)) {
            resolveCapability(capabilityKey, capabilitiesByName, cache, new LinkedHashSet<>());
        }
        return new ArrayList<>(cache.values());
    }

    private CapabilityAst resolveCapability(
            String capabilityKey,
            Map<String, CapabilityAst> capabilitiesByName,
            Map<String, CapabilityAst> cache,
            Set<String> stack
    ) {
        CapabilityAst cached = cache.get(capabilityKey);
        if (cached != null) {
            return cached;
        }
        CapabilityAst capability = capabilitiesByName.get(capabilityKey);
        if (capability == null) {
            throw diagnostic(ResolutionDiagnosticCode.BASE_NOT_FOUND, "Capability not found: " + capabilityKey);
        }
        if (!stack.add(capabilityKey)) {
            throw diagnostic(
                    ResolutionDiagnosticCode.ILLEGAL_OVERRIDE,
                    "Capability specialization cycle detected: " + capability.getName()
            );
        }

        String baseRef = capability.getSpecializesName();
        CapabilityAst resolved;
        if (baseRef == null || baseRef.isBlank()) {
            resolved = sanitizeCapability(capability);
        } else {
            String baseKey = normalize(baseRef);
            CapabilityAst base = capabilitiesByName.get(baseKey);
            if (base == null) {
                throw diagnostic(
                        ResolutionDiagnosticCode.BASE_NOT_FOUND,
                        "Capability " + capability.getName() + " specializes unknown base " + baseRef
                );
            }
            CapabilityAst resolvedBase = resolveCapability(baseKey, capabilitiesByName, cache, stack);
            resolved = mergeCapability(resolvedBase, capability);
        }
        stack.remove(capabilityKey);
        cache.put(capabilityKey, resolved);
        return resolved;
    }

    private CapabilityAst sanitizeCapability(CapabilityAst capability) {
        List<CapabilityOperationAst> operations = uniqueOperations(capability.getOperations(), capability.getName());
        return new CapabilityAst(
                capability.getName(),
                capability.getType(),
                null,
                operations
        );
    }

    private CapabilityAst mergeCapability(CapabilityAst base, CapabilityAst specialization) {
        String baseType = safe(base.getType());
        String localType = safe(specialization.getType());
        if (!localType.isBlank() && !baseType.isBlank() && !normalize(localType).equals(normalize(baseType))) {
            throw diagnostic(
                    ResolutionDiagnosticCode.ILLEGAL_OVERRIDE,
                    "Capability " + specialization.getName()
                            + " cannot change base type from " + base.getType() + " to " + specialization.getType()
            );
        }
        String resolvedType = !localType.isBlank() ? specialization.getType() : base.getType();

        List<CapabilityOperationAst> mergedOperations = new ArrayList<>(base.getOperations());
        Set<String> operationNames = new LinkedHashSet<>();
        for (CapabilityOperationAst operation : mergedOperations) {
            operationNames.add(normalize(operation.getName()));
        }
        for (CapabilityOperationAst localOperation : sortedOperations(specialization.getOperations())) {
            String operationKey = normalize(localOperation.getName());
            if (operationNames.contains(operationKey)) {
                throw diagnostic(
                        ResolutionDiagnosticCode.ILLEGAL_OVERRIDE,
                        "Capability " + specialization.getName()
                                + " cannot override base operation " + localOperation.getName()
                                + "; specialization allows adding operations only"
                );
            }
            operationNames.add(operationKey);
            mergedOperations.add(copyOperation(localOperation));
        }
        mergedOperations.sort(Comparator.comparing(operation -> normalize(operation.getName())));
        return new CapabilityAst(
                specialization.getName(),
                resolvedType,
                null,
                mergedOperations
        );
    }

    private List<EventAst> resolveEvents(Map<String, EventAst> eventsByName) {
        Map<String, EventAst> cache = new LinkedHashMap<>();
        for (String eventKey : sortedKeys(eventsByName)) {
            resolveEvent(eventKey, eventsByName, cache, new LinkedHashSet<>());
        }
        return new ArrayList<>(cache.values());
    }

    private EventAst resolveEvent(
            String eventKey,
            Map<String, EventAst> eventsByName,
            Map<String, EventAst> cache,
            Set<String> stack
    ) {
        EventAst cached = cache.get(eventKey);
        if (cached != null) {
            return cached;
        }
        EventAst event = eventsByName.get(eventKey);
        if (event == null) {
            throw diagnostic(ResolutionDiagnosticCode.BASE_NOT_FOUND, "Event not found: " + eventKey);
        }
        if (!stack.add(eventKey)) {
            throw diagnostic(
                    ResolutionDiagnosticCode.ILLEGAL_OVERRIDE,
                    "Event specialization cycle detected: " + event.getName()
            );
        }

        String baseRef = event.getSpecializesName();
        EventAst resolved;
        if (baseRef == null || baseRef.isBlank()) {
            resolved = sanitizeEvent(event);
        } else {
            String baseKey = normalize(baseRef);
            EventAst base = eventsByName.get(baseKey);
            if (base == null) {
                throw diagnostic(
                        ResolutionDiagnosticCode.BASE_NOT_FOUND,
                        "Event " + event.getName() + " specializes unknown base " + baseRef
                );
            }
            EventAst resolvedBase = resolveEvent(baseKey, eventsByName, cache, stack);
            resolved = mergeEvent(resolvedBase, event);
        }
        stack.remove(eventKey);
        cache.put(eventKey, resolved);
        return resolved;
    }

    private static EventAst sanitizeEvent(EventAst event) {
        List<EventPayloadAst> payload = uniquePayloadFields(event.getPayloadFields(), event.getName());
        return new EventAst(
                event.getName(),
                event.getConceptName(),
                null,
                event.getVersion(),
                payload,
                event.getTriggerMode()
        );
    }

    private EventAst mergeEvent(EventAst base, EventAst specialization) {
        String baseConcept = safe(base.getConceptName());
        String localConcept = safe(specialization.getConceptName());
        if (!localConcept.isBlank() && !baseConcept.isBlank()
                && !normalize(localConcept).equals(normalize(baseConcept))) {
            throw diagnostic(
                    ResolutionDiagnosticCode.ILLEGAL_OVERRIDE,
                    "Event " + specialization.getName() + " cannot change base concept from "
                            + base.getConceptName() + " to " + specialization.getConceptName()
            );
        }
        String resolvedConcept = !localConcept.isBlank() ? specialization.getConceptName() : base.getConceptName();

        List<EventPayloadAst> basePayload = uniquePayloadFields(base.getPayloadFields(), base.getName());
        List<EventPayloadAst> localPayload = uniquePayloadFields(specialization.getPayloadFields(), specialization.getName());
        boolean payloadChanged = !localPayload.isEmpty() && !samePayload(basePayload, localPayload);
        String localVersion = safe(specialization.getVersion());
        String baseVersion = safe(base.getVersion());
        if (payloadChanged && (localVersion.isBlank() || normalize(localVersion).equals(normalize(baseVersion)))) {
            throw diagnostic(
                    ResolutionDiagnosticCode.VERSION_REQUIRED,
                    "Event " + specialization.getName()
                            + " changed payload schema and must bump version"
            );
        }
        List<EventPayloadAst> resolvedPayload = localPayload.isEmpty() ? basePayload : localPayload;
        String resolvedVersion = !localVersion.isBlank() ? specialization.getVersion() : base.getVersion();
        String localTriggerMode = safe(specialization.getTriggerMode());
        String resolvedTriggerMode = !localTriggerMode.isBlank() ? specialization.getTriggerMode() : base.getTriggerMode();

        return new EventAst(
                specialization.getName(),
                resolvedConcept,
                null,
                resolvedVersion,
                resolvedPayload,
                resolvedTriggerMode
        );
    }

    private static boolean samePayload(List<EventPayloadAst> left, List<EventPayloadAst> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            EventPayloadAst leftField = left.get(index);
            EventPayloadAst rightField = right.get(index);
            if (!normalize(leftField.getName()).equals(normalize(rightField.getName()))) {
                return false;
            }
            if (!normalize(leftField.getType()).equals(normalize(rightField.getType()))) {
                return false;
            }
        }
        return true;
    }

    private List<FlowAst> resolveFlows(Map<String, FlowAst> flowsByName) {
        Map<String, FlowAst> cache = new LinkedHashMap<>();
        for (String flowKey : sortedKeys(flowsByName)) {
            resolveFlow(flowKey, flowsByName, cache, new LinkedHashSet<>());
        }
        return new ArrayList<>(cache.values());
    }

    private List<OrchestrationAst> resolveOrchestrationRules(Map<String, OrchestrationAst> rulesByName) {
        List<OrchestrationAst> rules = new ArrayList<>();
        for (String key : sortedKeys(rulesByName)) {
            OrchestrationAst rule = rulesByName.get(key);
            if (rule == null) {
                continue;
            }
            OrchestrationTriggerAst trigger = rule.getTrigger() == null
                    ? null
                    : new OrchestrationTriggerAst(
                    rule.getTrigger().getType(),
                    rule.getTrigger().getEvent()
            );
            OrchestrationActionAst action = rule.getAction() == null
                    ? null
                    : new OrchestrationActionAst(
                    rule.getAction().getType(),
                    rule.getAction().getConcept(),
                    rule.getAction().getCapability(),
                    rule.getAction().getOperation(),
                    rule.getAction().getEvent(),
                    rule.getAction().getDelaySeconds(),
                    rule.getAction().getMap(),
                    cloneActionMetadata(rule.getAction().getAction())
            );
            List<OrchestrationActionAst> actions = new ArrayList<>();
            for (OrchestrationActionAst candidate : rule.getActions()) {
                if (candidate == null) {
                    continue;
                }
                actions.add(new OrchestrationActionAst(
                        candidate.getType(),
                        candidate.getConcept(),
                        candidate.getCapability(),
                        candidate.getOperation(),
                        candidate.getEvent(),
                        candidate.getDelaySeconds(),
                        candidate.getMap(),
                        cloneActionMetadata(candidate.getAction())
                ));
            }
            rules.add(new OrchestrationAst(rule.getName(), rule.getCondition(), trigger, action, actions));
        }
        return rules;
    }

    private FlowAst resolveFlow(
            String flowKey,
            Map<String, FlowAst> flowsByName,
            Map<String, FlowAst> cache,
            Set<String> stack
    ) {
        FlowAst cached = cache.get(flowKey);
        if (cached != null) {
            return cached;
        }
        FlowAst flow = flowsByName.get(flowKey);
        if (flow == null) {
            throw diagnostic(ResolutionDiagnosticCode.BASE_NOT_FOUND, "Flow not found: " + flowKey);
        }
        if (!stack.add(flowKey)) {
            throw diagnostic(
                    ResolutionDiagnosticCode.ILLEGAL_OVERRIDE,
                    "Flow specialization cycle detected: " + flow.getName()
            );
        }

        String baseRef = flow.getSpecializesName();
        FlowAst resolved;
        if (baseRef == null || baseRef.isBlank()) {
            resolved = sanitizeFlow(flow);
        } else {
            String baseKey = normalize(baseRef);
            FlowAst base = flowsByName.get(baseKey);
            if (base == null) {
                throw diagnostic(
                        ResolutionDiagnosticCode.BASE_NOT_FOUND,
                        "Flow " + flow.getName() + " specializes unknown base " + baseRef
                );
            }
            FlowAst resolvedBase = resolveFlow(baseKey, flowsByName, cache, stack);
            resolved = mergeFlow(resolvedBase, flow);
        }
        stack.remove(flowKey);
        cache.put(flowKey, resolved);
        return resolved;
    }

    private FlowAst sanitizeFlow(FlowAst flow) {
        if (!flow.getHooks().isEmpty()) {
            throw diagnostic(
                    ResolutionDiagnosticCode.ILLEGAL_OVERRIDE,
                    "Flow " + flow.getName() + " defines hooks without specializes"
            );
        }
        List<StepAst> steps = cloneSteps(flow.getSteps());
        validateStepNameUniqueness(steps, flow.getName());
        return new FlowAst(
                flow.getName(),
                flow.getConcept(),
                flow.getMode(),
                null,
                List.of(),
                steps,
                flow.getInputSchema(),
                flow.getOutputSchema(),
                cloneActionMetadata(flow.getAction()),
                flow.isStartEndpoint(),
                flow.getSchedule()
        );
    }

    private FlowAst mergeFlow(FlowAst base, FlowAst specialization) {
        if (!specialization.getSteps().isEmpty()) {
            throw diagnostic(
                    ResolutionDiagnosticCode.ILLEGAL_OVERRIDE,
                    "Flow " + specialization.getName()
                            + " cannot redefine steps when specializing "
                            + base.getName()
                            + "; use hooks"
            );
        }
        if (!safe(specialization.getConcept()).isBlank()) {
            throw diagnostic(
                    ResolutionDiagnosticCode.ILLEGAL_OVERRIDE,
                    "Flow " + specialization.getName()
                            + " cannot override concept of specialized flow"
            );
        }
        if (!safe(specialization.getMode()).isBlank()) {
            throw diagnostic(
                    ResolutionDiagnosticCode.ILLEGAL_OVERRIDE,
                    "Flow " + specialization.getName()
                            + " cannot override mode of specialized flow"
            );
        }
        if (specialization.getInputSchema() != null || specialization.getOutputSchema() != null) {
            throw diagnostic(
                    ResolutionDiagnosticCode.ILLEGAL_OVERRIDE,
                    "Flow " + specialization.getName()
                            + " cannot override schemas of specialized flow"
            );
        }

        List<StepAst> mergedSteps = applyHooks(base.getSteps(), specialization.getHooks(), specialization.getName());
        validateStepNameUniqueness(mergedSteps, specialization.getName());

        // LNCH-12: same "specialization wins, else base" rationale as LNCH-13's access field --
        // a specialization narrowing/changing the schedule replaces it rather than merging, since
        // there's no sensible way to combine two cron expressions.
        FlowScheduleAst mergedSchedule = specialization.getSchedule() != null
                ? specialization.getSchedule() : base.getSchedule();
        return new FlowAst(
                specialization.getName(),
                base.getConcept(),
                base.getMode(),
                null,
                List.of(),
                mergedSteps,
                base.getInputSchema(),
                base.getOutputSchema(),
                firstNonNullAction(specialization.getAction(), base.getAction()),
                specialization.isStartEndpoint() || base.isStartEndpoint(),
                mergedSchedule
        );
    }

    private List<StepAst> applyHooks(List<StepAst> baseSteps, List<FlowHookAst> hooks, String flowName) {
        List<StepAst> normalizedBaseSteps = cloneSteps(baseSteps);
        if (hooks == null || hooks.isEmpty()) {
            return normalizedBaseSteps;
        }

        Map<String, List<FlowHookAst>> beforeByTarget = new LinkedHashMap<>();
        Map<String, List<FlowHookAst>> afterByTarget = new LinkedHashMap<>();
        Set<String> baseStepNames = new LinkedHashSet<>();
        for (StepAst step : normalizedBaseSteps) {
            baseStepNames.add(normalize(step.getName()));
        }

        for (FlowHookAst hook : hooks) {
            if (hook == null) {
                continue;
            }
            String position = normalize(hook.getPosition());
            if (!"before".equals(position) && !"after".equals(position)) {
                throw diagnostic(
                        ResolutionDiagnosticCode.ILLEGAL_OVERRIDE,
                        "Flow " + flowName + " hook position must be before|after"
                );
            }
            String targetKey = normalize(hook.getTargetStep());
            if (targetKey.isBlank() || !baseStepNames.contains(targetKey)) {
                throw diagnostic(
                        ResolutionDiagnosticCode.BASE_NOT_FOUND,
                        "Flow " + flowName + " hook target step not found: " + hook.getTargetStep()
                );
            }
            Map<String, List<FlowHookAst>> targetMap = "before".equals(position) ? beforeByTarget : afterByTarget;
            targetMap.computeIfAbsent(targetKey, key -> new ArrayList<>()).add(hook);
        }

        List<StepAst> merged = new ArrayList<>();
        for (StepAst baseStep : normalizedBaseSteps) {
            String baseKey = normalize(baseStep.getName());
            for (FlowHookAst hook : beforeByTarget.getOrDefault(baseKey, List.of())) {
                merged.addAll(cloneSteps(hook.getSteps()));
            }
            merged.add(baseStep);
            for (FlowHookAst hook : afterByTarget.getOrDefault(baseKey, List.of())) {
                merged.addAll(cloneSteps(hook.getSteps()));
            }
        }
        return merged;
    }

    private void validateStepNameUniqueness(List<StepAst> steps, String flowName) {
        Set<String> names = new LinkedHashSet<>();
        for (StepAst step : steps) {
            if (step == null) {
                continue;
            }
            String key = normalize(step.getName());
            if (key.isBlank()) {
                continue;
            }
            if (!names.add(key)) {
                throw diagnostic(
                        ResolutionDiagnosticCode.CONFLICT_DUPLICATE_MEMBER,
                        "Flow " + flowName + " contains duplicate step name " + step.getName()
                );
            }
        }
    }

    private List<StepAst> cloneSteps(List<StepAst> steps) {
        List<StepAst> out = new ArrayList<>();
        for (StepAst step : steps) {
            out.add(cloneStep(step));
        }
        return out;
    }

    private StepAst cloneStep(StepAst step) {
        if (step == null) {
            return null;
        }
        return new StepAst(
                step.getName(),
                step.getType(),
                step.getCheckpoint(),
                step.getScope(),
                step.getInvariants(),
                step.getCapability(),
                step.getOperation(),
                step.getCapabilityPolicy(),
                step.getInput(),
                step.getOutput(),
                step.getArgs(),
                step.getEvent(),
                step.getPayload(),
                step.getData(),
                step.getCondition(),
                cloneSteps(step.getThenSteps()),
                cloneSteps(step.getElseSteps()),
                step.getAwaitEvent(),
                step.getAwaitRef(),
                step.getAwaitMatchCorrelation(),
                step.getAwaitPayloadMatch(),
                step.getDelaySeconds(),
                step.getReturnValue(),
                cloneActionMetadata(step.getAction()),
                step.getGeneratedActionName(),
                step.getCollectionRef(),
                step.getItemKey(),
                cloneSteps(step.getLoopSteps()),
                step.getMaxLoopIterations(),
                cloneSteps(step.getOnFailureSteps())
        );
    }

    private static ActionMetadataAst firstNonNullAction(ActionMetadataAst preferred, ActionMetadataAst fallback) {
        return preferred != null ? cloneActionMetadata(preferred) : cloneActionMetadata(fallback);
    }

    private static ActionMetadataAst cloneActionMetadata(ActionMetadataAst action) {
        if (action == null) {
            return null;
        }
        return new ActionMetadataAst(
                action.getLabel(),
                action.getConfirmationText(),
                action.getSuccessMessage(),
                action.getFailureHint(),
                action.getDangerLevel(),
                action.getVisibleWhen(),
                action.getPermissionHint(),
                action.getInputFormHint()
        );
    }

    private static List<FieldAst> uniqueFields(List<FieldAst> fields, String conceptName) {
        Map<String, FieldAst> unique = new LinkedHashMap<>();
        for (FieldAst field : sortedFields(fields)) {
            String key = normalize(field.getName());
            if (unique.containsKey(key)) {
                throw diagnostic(
                        ResolutionDiagnosticCode.CONFLICT_DUPLICATE_MEMBER,
                        "Concept " + conceptName + " declares duplicate field " + field.getName()
                );
            }
            unique.put(key, field);
        }
        return List.copyOf(unique.values());
    }

    private static List<InvariantAst> sanitizeInvariants(List<InvariantAst> invariants, String conceptName) {
        List<InvariantAst> out = new ArrayList<>();
        for (InvariantAst invariant : invariants) {
            out.add(sanitizeInvariant(invariant));
        }
        out.sort(Comparator.comparing(ModelResolver::invariantSortKey));
        Set<String> seen = new LinkedHashSet<>();
        for (InvariantAst invariant : out) {
            String ref = normalize(invariantReference(invariant));
            if (ref.isBlank()) {
                continue;
            }
            if (!seen.add(ref)) {
                throw diagnostic(
                        ResolutionDiagnosticCode.CONFLICT_DUPLICATE_MEMBER,
                        "Concept " + conceptName + " declares duplicate invariant " + invariantDisplayName(invariant)
                );
            }
        }
        return List.copyOf(out);
    }

    private static InvariantAst sanitizeInvariant(InvariantAst invariant) {
        if (invariant == null) {
            return new InvariantAst(null, null, List.of(), null);
        }
        return new InvariantAst(
                invariant.getName(),
                invariant.getType(),
                invariant.getFields(),
                invariant.getExpression(),
                invariant.getSpecializesName(),
                invariant.isOverride()
        );
    }

    private static InvariantAst stripInvariantSpecialization(InvariantAst invariant) {
        return new InvariantAst(
                invariant.getName(),
                invariant.getType(),
                invariant.getFields(),
                invariant.getExpression()
        );
    }

    private static LifecycleAst sanitizeLifecycle(LifecycleAst lifecycle) {
        if (lifecycle == null) {
            return null;
        }
        String statusField = lifecycle.getStatusField();
        List<StateMachineStateAst> states = new ArrayList<>();
        for (StateMachineStateAst state : lifecycle.getStates()) {
            if (state == null) {
                continue;
            }
            states.add(new StateMachineStateAst(
                    state.getValue(),
                    state.getLabel(),
                    state.isInitial(),
                    state.isTerminal(),
                    state.getMetadata()
            ));
        }
        List<StateTransitionAst> transitions = new ArrayList<>();
        for (StateTransitionAst transition : lifecycle.getTransitions()) {
            if (transition == null) {
                continue;
            }
            transitions.add(new StateTransitionAst(
                    transition.getFrom(),
                    transition.getTo(),
                    transition.getRequiredPayload(),
                    transition.getEvent(),
                    transition.getGuard(),
                    transition.getActionLabel(),
                    transition.getMetadata(),
                    cloneActionMetadata(transition.getAction())
            ));
        }
        transitions.sort(Comparator
                .comparing((StateTransitionAst transition) -> normalize(transition.getFrom()))
                .thenComparing(transition -> normalize(transition.getTo())));
        return new LifecycleAst(statusField, states, transitions);
    }

    private static List<EventAst> uniqueEvents(List<EventAst> events, String conceptName) {
        Map<String, EventAst> unique = new LinkedHashMap<>();
        for (EventAst event : sortedEvents(events)) {
            String key = normalize(event.getName());
            if (unique.containsKey(key)) {
                throw diagnostic(
                        ResolutionDiagnosticCode.CONFLICT_DUPLICATE_MEMBER,
                        "Concept " + conceptName + " declares duplicate event " + event.getName()
                );
            }
            unique.put(key, sanitizeEvent(event));
        }
        return List.copyOf(unique.values());
    }

    private static List<CapabilityOperationAst> uniqueOperations(List<CapabilityOperationAst> operations, String capabilityName) {
        Map<String, CapabilityOperationAst> unique = new LinkedHashMap<>();
        for (CapabilityOperationAst operation : sortedOperations(operations)) {
            String key = normalize(operation.getName());
            if (unique.containsKey(key)) {
                throw diagnostic(
                        ResolutionDiagnosticCode.CONFLICT_DUPLICATE_MEMBER,
                        "Capability " + capabilityName + " declares duplicate operation " + operation.getName()
                );
            }
            unique.put(key, copyOperation(operation));
        }
        return List.copyOf(unique.values());
    }

    private static CapabilityOperationAst copyOperation(CapabilityOperationAst operation) {
        return new CapabilityOperationAst(
                operation.getName(),
                operation.getInput(),
                operation.getOutput(),
                operation.getInputSchema(),
                operation.getOutputSchema(),
                operation.getExecutionPolicy()
        );
    }

    private static List<EventPayloadAst> uniquePayloadFields(List<EventPayloadAst> payloadFields, String eventName) {
        Map<String, EventPayloadAst> unique = new LinkedHashMap<>();
        for (EventPayloadAst payloadField : sortedPayload(payloadFields)) {
            String key = normalize(payloadField.getName());
            if (unique.containsKey(key)) {
                throw diagnostic(
                        ResolutionDiagnosticCode.CONFLICT_DUPLICATE_MEMBER,
                        "Event " + eventName + " declares duplicate payload field " + payloadField.getName()
                );
            }
            unique.put(key, new EventPayloadAst(payloadField.getName(), payloadField.getType()));
        }
        return List.copyOf(unique.values());
    }

    private static List<FieldAst> sortedFields(List<FieldAst> fields) {
        List<FieldAst> out = new ArrayList<>(fields);
        out.sort(Comparator.comparing(field -> normalize(field.getName())));
        return out;
    }

    private static List<EventAst> sortedEvents(List<EventAst> events) {
        List<EventAst> out = new ArrayList<>(events);
        out.sort(Comparator.comparing(event -> normalize(event.getName())));
        return out;
    }

    private static List<CapabilityOperationAst> sortedOperations(List<CapabilityOperationAst> operations) {
        List<CapabilityOperationAst> out = new ArrayList<>(operations);
        out.sort(Comparator.comparing(operation -> normalize(operation.getName())));
        return out;
    }

    private static List<EventPayloadAst> sortedPayload(List<EventPayloadAst> payloadFields) {
        List<EventPayloadAst> out = new ArrayList<>(payloadFields);
        out.sort(Comparator.comparing(payload -> normalize(payload.getName())));
        return out;
    }

    private static List<String> sortedKeys(Map<String, ?> indexed) {
        List<String> keys = new ArrayList<>(indexed.keySet());
        keys.sort(String::compareTo);
        return keys;
    }

    private static <T> Map<String, T> indexByName(
            List<T> assets,
            java.util.function.Function<T, String> nameAccessor,
            String label
    ) {
        Map<String, T> indexed = new LinkedHashMap<>();
        for (T asset : assets) {
            String name = nameAccessor.apply(asset);
            String key = normalize(name);
            if (key.isBlank()) {
                throw diagnostic(
                        ResolutionDiagnosticCode.CONFLICT_DUPLICATE_MEMBER,
                        "Blank " + label + " name is not allowed"
                );
            }
            if (indexed.containsKey(key)) {
                throw diagnostic(
                        ResolutionDiagnosticCode.CONFLICT_DUPLICATE_MEMBER,
                        "Duplicate " + label + " name: " + name
                );
            }
            indexed.put(key, asset);
        }
        return indexed;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static ModelResolutionException diagnostic(ResolutionDiagnosticCode code, String message) {
        return new ModelResolutionException(code, message);
    }
}
