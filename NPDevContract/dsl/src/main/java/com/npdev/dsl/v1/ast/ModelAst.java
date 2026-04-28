package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModelAst {
    public static final String DEFAULT_DSL_VERSION = "1.0.0";

    private final String namespace;
    private final String dslVersion;
    private final String version;
    private final List<ConceptAst> concepts;
    private final List<DomainTypeAst> domainTypes;
    private final List<CapabilityAst> capabilities;
    private final List<CapabilityBindingAst> bindings;
    private final List<EventAst> events;
    private final List<FlowAst> flows;
    private final List<OrchestrationAst> orchestrationRules;
    private final List<QueryAst> queries;
    private final List<RuleProfileAst> ruleProfiles;
    private final List<ProcedureAst> procedures;
    private final List<PanelAst> panels;
    private final List<String> parserWarnings;

    public ModelAst(String namespace, String version, List<? extends EntityAst> entities) {
        this(namespace, DEFAULT_DSL_VERSION, version, entities, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public ModelAst(String namespace, String dslVersion, String version, List<? extends EntityAst> entities) {
        this(namespace, dslVersion, version, entities, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public ModelAst(
            String namespace,
            String dslVersion,
            String version,
            List<? extends EntityAst> entities,
            List<CapabilityAst> capabilities,
            List<CapabilityBindingAst> bindings,
            List<EventAst> events,
            List<FlowAst> flows
    ) {
        this(namespace, dslVersion, version, entities, List.of(), capabilities, bindings, events, flows, List.of(), List.of());
    }

    public ModelAst(
            String namespace,
            String version,
            List<? extends EntityAst> entities,
            List<CapabilityAst> capabilities,
            List<CapabilityBindingAst> bindings,
            List<EventAst> events,
            List<FlowAst> flows,
            List<String> parserWarnings
    ) {
        this(namespace, DEFAULT_DSL_VERSION, version, entities, List.of(), capabilities, bindings, events, flows, List.of(), parserWarnings);
    }

    public ModelAst(
            String namespace,
            String dslVersion,
            String version,
            List<? extends EntityAst> entities,
            List<CapabilityAst> capabilities,
            List<CapabilityBindingAst> bindings,
            List<EventAst> events,
            List<FlowAst> flows,
            List<OrchestrationAst> orchestrationRules,
            List<String> parserWarnings
    ) {
        this(namespace, dslVersion, version, entities, List.of(), capabilities, bindings, events, flows, orchestrationRules,
                List.of(), List.of(), List.of(), List.of(), parserWarnings);
    }

    public ModelAst(
            String namespace,
            String version,
            List<? extends EntityAst> entities,
            List<CapabilityAst> capabilities,
            List<CapabilityBindingAst> bindings,
            List<EventAst> events,
            List<FlowAst> flows,
            List<OrchestrationAst> orchestrationRules,
            List<String> parserWarnings
    ) {
        this(namespace, DEFAULT_DSL_VERSION, version, entities, List.of(), capabilities, bindings, events, flows,
                orchestrationRules, List.of(), List.of(), List.of(), List.of(), parserWarnings);
    }

    public ModelAst(
            String namespace,
            String dslVersion,
            String version,
            List<? extends EntityAst> entities,
            List<DomainTypeAst> domainTypes,
            List<CapabilityAst> capabilities,
            List<CapabilityBindingAst> bindings,
            List<EventAst> events,
            List<FlowAst> flows,
            List<OrchestrationAst> orchestrationRules,
            List<String> parserWarnings
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, List.of(), List.of(), List.of(), List.of(), parserWarnings);
    }

    public ModelAst(
            String namespace,
            String dslVersion,
            String version,
            List<? extends EntityAst> entities,
            List<DomainTypeAst> domainTypes,
            List<CapabilityAst> capabilities,
            List<CapabilityBindingAst> bindings,
            List<EventAst> events,
            List<FlowAst> flows,
            List<OrchestrationAst> orchestrationRules,
            List<QueryAst> queries,
            List<RuleProfileAst> ruleProfiles,
            List<ProcedureAst> procedures,
            List<PanelAst> panels,
            List<String> parserWarnings
    ) {
        this.namespace = namespace;
        this.dslVersion = dslVersion;
        this.version = version;
        this.concepts = toConcepts(entities);
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
        this.parserWarnings = new ArrayList<>(parserWarnings);
    }

    public String getNamespace() { return namespace; }
    public String getDslVersion() { return dslVersion; }
    public String getVersion() { return version; }

    public List<ConceptAst> getConcepts() {
        return Collections.unmodifiableList(concepts);
    }

    /**
     * @deprecated Use {@link #getConcepts()}.
     */
    @Deprecated(forRemoval = false)
    public List<EntityAst> getEntities() {
        return Collections.unmodifiableList(new ArrayList<>(concepts));
    }

    public List<DomainTypeAst> getDomainTypes() {
        return Collections.unmodifiableList(domainTypes);
    }

    public List<CapabilityAst> getCapabilities() {
        return Collections.unmodifiableList(capabilities);
    }

    public List<CapabilityBindingAst> getBindings() {
        return Collections.unmodifiableList(bindings);
    }

    public List<EventAst> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public List<FlowAst> getFlows() {
        return Collections.unmodifiableList(flows);
    }

    public List<OrchestrationAst> getOrchestrationRules() {
        return Collections.unmodifiableList(orchestrationRules);
    }

    public List<QueryAst> getQueries() {
        return Collections.unmodifiableList(queries);
    }

    public List<RuleProfileAst> getRuleProfiles() {
        return Collections.unmodifiableList(ruleProfiles);
    }

    public List<ProcedureAst> getProcedures() {
        return Collections.unmodifiableList(procedures);
    }

    public List<PanelAst> getPanels() {
        return Collections.unmodifiableList(panels);
    }

    public List<String> getParserWarnings() {
        return Collections.unmodifiableList(parserWarnings);
    }

    private static List<ConceptAst> toConcepts(List<? extends EntityAst> source) {
        List<ConceptAst> out = new ArrayList<>();
        for (EntityAst entity : source) {
            out.add(ConceptAst.fromLegacyEntity(entity));
        }
        return out;
    }
}
