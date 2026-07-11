package com.npdev.dsl.v1.compiled;

import java.util.*;

@SuppressWarnings("deprecation")
public final class CompiledModel {
    private final String namespace;
    private final String dslVersion;
    private final String version;
    private final Map<String, CompiledConcept> conceptsByName;
    private final List<CompiledDomainType> domainTypes;
    private final List<CompiledCapability> capabilities;
    private final List<CompiledCapabilityBinding> bindings;
    private final List<CompiledEvent> events;
    private final List<CompiledFlow> flows;
    private final List<CompiledOrchestration> orchestrationRules;
    private final List<CompiledQuery> queries;
    private final List<CompiledRuleProfile> ruleProfiles;
    private final List<CompiledProcedure> procedures;
    private final List<CompiledPanel> panels;
    private final List<CompiledGuidePage> guidePages;
    private final List<CompiledAggregate> aggregates;

    public CompiledModel(String namespace, String version, Map<String, ? extends CompiledEntity> entitiesByName) {
        this(namespace, "1.0.0", version, entitiesByName, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public CompiledModel(String namespace, String dslVersion, String version, Map<String, ? extends CompiledEntity> entitiesByName) {
        this(namespace, dslVersion, version, entitiesByName, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public CompiledModel(
            String namespace,
            String version,
            Map<String, ? extends CompiledEntity> entitiesByName,
            List<CompiledCapability> capabilities,
            List<CompiledCapabilityBinding> bindings,
            List<CompiledEvent> events,
            List<CompiledFlow> flows,
            List<CompiledOrchestration> orchestrationRules
    ) {
        this(namespace, "1.0.0", version, entitiesByName, List.of(), capabilities, bindings, events, flows, orchestrationRules,
                List.of(), List.of(), List.of(), List.of());
    }

    public CompiledModel(
            String namespace,
            String dslVersion,
            String version,
            Map<String, ? extends CompiledEntity> entitiesByName,
            List<CompiledCapability> capabilities,
            List<CompiledCapabilityBinding> bindings,
            List<CompiledEvent> events,
            List<CompiledFlow> flows
    ) {
        this(namespace, dslVersion, version, entitiesByName, List.of(), capabilities, bindings, events, flows, List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    public CompiledModel(
            String namespace,
            String dslVersion,
            String version,
            Map<String, ? extends CompiledEntity> entitiesByName,
            List<CompiledCapability> capabilities,
            List<CompiledCapabilityBinding> bindings,
            List<CompiledEvent> events,
            List<CompiledFlow> flows,
            List<CompiledOrchestration> orchestrationRules
    ) {
        this(namespace, dslVersion, version, entitiesByName, List.of(), capabilities, bindings, events, flows,
                orchestrationRules, List.of(), List.of(), List.of(), List.of());
    }

    public CompiledModel(
            String namespace,
            String dslVersion,
            String version,
            Map<String, ? extends CompiledEntity> entitiesByName,
            List<CompiledDomainType> domainTypes,
            List<CompiledCapability> capabilities,
            List<CompiledCapabilityBinding> bindings,
            List<CompiledEvent> events,
            List<CompiledFlow> flows,
            List<CompiledOrchestration> orchestrationRules
    ) {
        this(namespace, dslVersion, version, entitiesByName, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, List.of(), List.of(), List.of(), List.of());
    }

    public CompiledModel(
            String namespace,
            String dslVersion,
            String version,
            Map<String, ? extends CompiledEntity> entitiesByName,
            List<CompiledDomainType> domainTypes,
            List<CompiledCapability> capabilities,
            List<CompiledCapabilityBinding> bindings,
            List<CompiledEvent> events,
            List<CompiledFlow> flows,
            List<CompiledOrchestration> orchestrationRules,
            List<CompiledQuery> queries,
            List<CompiledRuleProfile> ruleProfiles,
            List<CompiledProcedure> procedures,
            List<CompiledPanel> panels
    ) {
        this(namespace, dslVersion, version, entitiesByName, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, List.of());
    }

    public CompiledModel(
            String namespace,
            String dslVersion,
            String version,
            Map<String, ? extends CompiledEntity> entitiesByName,
            List<CompiledDomainType> domainTypes,
            List<CompiledCapability> capabilities,
            List<CompiledCapabilityBinding> bindings,
            List<CompiledEvent> events,
            List<CompiledFlow> flows,
            List<CompiledOrchestration> orchestrationRules,
            List<CompiledQuery> queries,
            List<CompiledRuleProfile> ruleProfiles,
            List<CompiledProcedure> procedures,
            List<CompiledPanel> panels,
            List<CompiledGuidePage> guidePages
    ) {
        this(namespace, dslVersion, version, entitiesByName, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, List.of());
    }

    public CompiledModel(
            String namespace,
            String dslVersion,
            String version,
            Map<String, ? extends CompiledEntity> entitiesByName,
            List<CompiledDomainType> domainTypes,
            List<CompiledCapability> capabilities,
            List<CompiledCapabilityBinding> bindings,
            List<CompiledEvent> events,
            List<CompiledFlow> flows,
            List<CompiledOrchestration> orchestrationRules,
            List<CompiledQuery> queries,
            List<CompiledRuleProfile> ruleProfiles,
            List<CompiledProcedure> procedures,
            List<CompiledPanel> panels,
            List<CompiledGuidePage> guidePages,
            List<CompiledAggregate> aggregates
    ) {
        this.namespace = namespace;
        this.dslVersion = dslVersion;
        this.version = version;
        this.conceptsByName = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends CompiledEntity> entry : entitiesByName.entrySet()) {
            CompiledConcept concept = CompiledConcept.fromLegacyEntity(entry.getValue());
            this.conceptsByName.put(entry.getKey(), concept);
        }
        this.domainTypes = new ArrayList<>(domainTypes);
        this.capabilities = new ArrayList<>(capabilities);
        this.bindings = new ArrayList<>(bindings);
        this.events = new ArrayList<>(events);
        this.flows = new ArrayList<>(flows);
        this.orchestrationRules = new ArrayList<>(orchestrationRules);
        this.queries = new ArrayList<>(queries);
        this.ruleProfiles = new ArrayList<>(ruleProfiles);
        this.procedures = new ArrayList<>(procedures);
        this.panels = new ArrayList<>(panels);
        this.guidePages = new ArrayList<>(guidePages);
        this.aggregates = new ArrayList<>(aggregates);
    }

    public String getNamespace() { return namespace; }
    public String getDslVersion() { return dslVersion; }
    public String getVersion() { return version; }

    public Collection<CompiledConcept> getConcepts() {
        return Collections.unmodifiableCollection(conceptsByName.values());
    }

    public Optional<CompiledConcept> findConcept(String name) {
        return Optional.ofNullable(conceptsByName.get(name));
    }

    /**
     * @deprecated Use {@link #getConcepts()}.
     */
    @Deprecated(forRemoval = false)
    public Collection<CompiledEntity> getEntities() {
        return Collections.unmodifiableCollection(new ArrayList<>(conceptsByName.values()));
    }

    /**
     * @deprecated Use {@link #findConcept(String)}.
     */
    @Deprecated(forRemoval = false)
    public Optional<CompiledEntity> findEntity(String name) {
        return Optional.ofNullable(conceptsByName.get(name));
    }

    public List<CompiledDomainType> getDomainTypes() {
        return Collections.unmodifiableList(domainTypes);
    }

    public List<CompiledCapability> getCapabilities() {
        return Collections.unmodifiableList(capabilities);
    }

    public List<CompiledCapabilityBinding> getBindings() {
        return Collections.unmodifiableList(bindings);
    }

    public List<CompiledEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public Optional<CompiledEvent> findEvent(String eventName) {
        if (eventName == null || eventName.isBlank()) {
            return Optional.empty();
        }
        for (CompiledEvent event : events) {
            if (event.getName().equals(eventName)) {
                return Optional.of(event);
            }
        }
        return Optional.empty();
    }

    public List<CompiledFlow> getFlows() {
        return Collections.unmodifiableList(flows);
    }

    public List<CompiledOrchestration> getOrchestrationRules() {
        return Collections.unmodifiableList(orchestrationRules);
    }

    public List<CompiledQuery> getQueries() {
        return Collections.unmodifiableList(queries);
    }

    public List<CompiledRuleProfile> getRuleProfiles() {
        return Collections.unmodifiableList(ruleProfiles);
    }

    public List<CompiledProcedure> getProcedures() {
        return Collections.unmodifiableList(procedures);
    }

    public List<CompiledPanel> getPanels() {
        return Collections.unmodifiableList(panels);
    }

    public List<CompiledGuidePage> getGuidePages() {
        return Collections.unmodifiableList(guidePages);
    }

    public List<CompiledAggregate> getAggregates() {
        return Collections.unmodifiableList(aggregates);
    }

    public Optional<CompiledFlow> findFlow(String flowName) {
        if (flowName == null || flowName.isBlank()) {
            return Optional.empty();
        }
        for (CompiledFlow flow : flows) {
            if (flow.getName().equals(flowName)) {
                return Optional.of(flow);
            }
        }
        return Optional.empty();
    }

    /**
     * The Flow that owns a concept's CRUD mode (e.g. "create"), if the model declares one. Used by
     * the generated service's wrapper integration: permission/tenant/idempotency/optimistic-
     * concurrency/audit stay exactly as today (in the generated CRUD template), but the core
     * mutation step delegates to this Flow's own steps when one is declared, instead of the default
     * direct gateway/entity save. At most one Flow may own a given (concept, mode) pair; if more
     * than one declares the same pair, the first declared wins (mirrors findFlow(name)'s
     * first-match behavior) -- model authoring should treat that as a conflict to avoid, not a
     * supported override mechanism.
     */
    public Optional<CompiledFlow> findFlow(String conceptName, String mode) {
        if (conceptName == null || conceptName.isBlank() || mode == null || mode.isBlank()) {
            return Optional.empty();
        }
        for (CompiledFlow flow : flows) {
            if (conceptName.equals(flow.getConcept()) && mode.equalsIgnoreCase(flow.getMode())) {
                return Optional.of(flow);
            }
        }
        return Optional.empty();
    }
}
