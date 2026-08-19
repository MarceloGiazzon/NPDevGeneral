package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@SuppressWarnings("deprecation")
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
    private final List<GuidePageAst> guidePages;
    private final List<AggregateAst> aggregates;
    private final List<AutoPanelAst> autoPanels;
    private final List<SelectorAst> selectors;
    private final List<DocumentAst> documents;
    private final List<String> parserWarnings;
    private final ExternalAiAst externalAi;
    private final SettingsAst settings;
    private final List<RoleAst> roles;
    private final List<PropertyScopeAst> propertyScopes;
    private final List<PropertyAst> properties;
    private final List<ContextAst> contexts;
    private final List<ConversionAst> conversions;
    private final Map<String, String> physicalQualifierByConceptName;
    private final List<WebhookAst> webhooks;

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
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, List.of(), List.of(), parserWarnings);
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
            List<GuidePageAst> guidePages,
            List<String> parserWarnings
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, List.of(), parserWarnings);
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
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<String> parserWarnings
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates,
                List.of(), parserWarnings);
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
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<String> parserWarnings
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates,
                autoPanels, List.of(), parserWarnings);
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
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<SelectorAst> selectors,
            List<String> parserWarnings
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates,
                autoPanels, selectors, List.of(), parserWarnings);
    }

    /** REG-12 Slice 3: canonical constructor, adds {@code documents} (LNCH-10's `document` PAGE kind). */
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
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<SelectorAst> selectors,
            List<DocumentAst> documents,
            List<String> parserWarnings
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates, autoPanels,
                selectors, documents, parserWarnings, null);
    }

    /** ADR-0009: adds {@code externalAi} (app-level egress settings). */
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
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<SelectorAst> selectors,
            List<DocumentAst> documents,
            List<String> parserWarnings,
            ExternalAiAst externalAi
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates, autoPanels,
                selectors, documents, parserWarnings, externalAi, null);
    }

    /** Move 6 Move A: adds {@code settings} (app-level locale/strings/ui). */
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
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<SelectorAst> selectors,
            List<DocumentAst> documents,
            List<String> parserWarnings,
            ExternalAiAst externalAi,
            SettingsAst settings
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates, autoPanels,
                selectors, documents, parserWarnings, externalAi, settings, List.of());
    }

    /** Wave 3 (RC-B1): adds {@code roles} (app-defined role -> permission ceiling declarations). */
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
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<SelectorAst> selectors,
            List<DocumentAst> documents,
            List<String> parserWarnings,
            ExternalAiAst externalAi,
            SettingsAst settings,
            List<RoleAst> roles
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates, autoPanels,
                selectors, documents, parserWarnings, externalAi, settings, roles, List.of(), List.of());
    }

    /** Wave 6 (RC-A1): adds {@code propertyScopes} + {@code properties} (the scoped-property
     *  cascade's declaration layer). */
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
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<SelectorAst> selectors,
            List<DocumentAst> documents,
            List<String> parserWarnings,
            ExternalAiAst externalAi,
            SettingsAst settings,
            List<RoleAst> roles,
            List<PropertyScopeAst> propertyScopes,
            List<PropertyAst> properties
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates, autoPanels,
                selectors, documents, parserWarnings, externalAi, settings, roles, propertyScopes, properties,
                List.of());
    }

    /** B20 (S2): adds {@code contexts} (bounded-context declarations). */
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
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<SelectorAst> selectors,
            List<DocumentAst> documents,
            List<String> parserWarnings,
            ExternalAiAst externalAi,
            SettingsAst settings,
            List<RoleAst> roles,
            List<PropertyScopeAst> propertyScopes,
            List<PropertyAst> properties,
            List<ContextAst> contexts
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates, autoPanels,
                selectors, documents, parserWarnings, externalAi, settings, roles, propertyScopes, properties,
                contexts, List.of());
    }

    /** S7 Phase B (B13): canonical constructor, adds {@code conversions} (the declarative conversion
     *  vocabulary -- see {@link ConversionAst}). */
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
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<SelectorAst> selectors,
            List<DocumentAst> documents,
            List<String> parserWarnings,
            ExternalAiAst externalAi,
            SettingsAst settings,
            List<RoleAst> roles,
            List<PropertyScopeAst> propertyScopes,
            List<PropertyAst> properties,
            List<ContextAst> contexts,
            List<ConversionAst> conversions
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates, autoPanels,
                selectors, documents, parserWarnings, externalAi, settings, roles, propertyScopes, properties,
                contexts, conversions, Map.of());
    }

    /** PK-2: canonical constructor, adds {@code physicalQualifierByConceptName} -- a pack-derived
     *  concept's physical SQL identity (see {@link com.npdev.dsl.v1.compiled.SqlIdentifierSupport}),
     *  keyed by the concept's LOGICAL qualified name ({@code aliasOrPackId::Name}), value formatted
     *  as {@code realPackId_v<major>}. Side-channel only -- never round-tripped through the compiled
     *  model's canonical JSON, since {@code ModelCompiler} consumes it once, at compile time, to
     *  compute {@code CompiledConcept.tableName}, which itself already round-trips. */
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
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<SelectorAst> selectors,
            List<DocumentAst> documents,
            List<String> parserWarnings,
            ExternalAiAst externalAi,
            SettingsAst settings,
            List<RoleAst> roles,
            List<PropertyScopeAst> propertyScopes,
            List<PropertyAst> properties,
            List<ContextAst> contexts,
            List<ConversionAst> conversions,
            Map<String, String> physicalQualifierByConceptName
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates, autoPanels,
                selectors, documents, parserWarnings, externalAi, settings, roles, propertyScopes, properties,
                contexts, conversions, physicalQualifierByConceptName, List.of());
    }

    /** R6.2: canonical constructor, adds {@code webhooks} (model-declared inbound webhook doors --
     *  see {@link WebhookAst}). */
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
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<SelectorAst> selectors,
            List<DocumentAst> documents,
            List<String> parserWarnings,
            ExternalAiAst externalAi,
            SettingsAst settings,
            List<RoleAst> roles,
            List<PropertyScopeAst> propertyScopes,
            List<PropertyAst> properties,
            List<ContextAst> contexts,
            List<ConversionAst> conversions,
            Map<String, String> physicalQualifierByConceptName,
            List<WebhookAst> webhooks
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
        this.guidePages = new ArrayList<>(guidePages);
        this.aggregates = new ArrayList<>(aggregates);
        this.autoPanels = new ArrayList<>(autoPanels);
        this.selectors = new ArrayList<>(selectors);
        this.documents = new ArrayList<>(documents);
        this.parserWarnings = new ArrayList<>(parserWarnings);
        this.externalAi = externalAi;
        this.settings = settings;
        this.roles = roles == null ? new ArrayList<>() : new ArrayList<>(roles);
        this.propertyScopes = propertyScopes == null ? new ArrayList<>() : new ArrayList<>(propertyScopes);
        this.properties = properties == null ? new ArrayList<>() : new ArrayList<>(properties);
        this.contexts = contexts == null ? new ArrayList<>() : new ArrayList<>(contexts);
        this.conversions = conversions == null ? new ArrayList<>() : new ArrayList<>(conversions);
        this.physicalQualifierByConceptName = physicalQualifierByConceptName == null
                ? Map.of() : Map.copyOf(physicalQualifierByConceptName);
        this.webhooks = webhooks == null ? new ArrayList<>() : new ArrayList<>(webhooks);
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

    public List<GuidePageAst> getGuidePages() {
        return Collections.unmodifiableList(guidePages);
    }

    public List<AggregateAst> getAggregates() {
        return Collections.unmodifiableList(aggregates);
    }

    public List<AutoPanelAst> getAutoPanels() {
        return Collections.unmodifiableList(autoPanels);
    }

    public List<SelectorAst> getSelectors() {
        return Collections.unmodifiableList(selectors);
    }

    public List<DocumentAst> getDocuments() {
        return Collections.unmodifiableList(documents);
    }

    public List<String> getParserWarnings() {
        return Collections.unmodifiableList(parserWarnings);
    }

    /** ADR-0009: app-level external-AI delegation settings, or null if the model declares none (denied by default). */
    public ExternalAiAst getExternalAi() {
        return externalAi;
    }

    /** Move 6 Move A: app-level settings, or null if the model declares none (platform defaults only). */
    public SettingsAst getSettings() {
        return settings;
    }

    /** Wave 3 (RC-B1): app-defined roles, empty when the model declares none (the built-in
     *  USER/OPERATOR/ADMIN trio then behaves exactly as before this feature existed). */
    public List<RoleAst> getRoles() {
        return Collections.unmodifiableList(roles);
    }

    /** Wave 6 (RC-A1): declared scope levels of the property cascade, most specific first, empty
     *  when the model declares none. */
    public List<PropertyScopeAst> getPropertyScopes() {
        return Collections.unmodifiableList(propertyScopes);
    }

    /** Wave 6 (RC-A1): declared runtime properties, empty when the model declares none. */
    public List<PropertyAst> getProperties() {
        return Collections.unmodifiableList(properties);
    }

    /** B20 (S2): declared bounded contexts, empty when the model declares none (a model with no
     *  contexts behaves exactly as it did before this feature existed). */
    public List<ContextAst> getContexts() {
        return Collections.unmodifiableList(contexts);
    }

    /** S7 Phase B (B13): declared conversions, empty when the model declares none. */
    public List<ConversionAst> getConversions() {
        return Collections.unmodifiableList(conversions);
    }

    /** PK-2: pack-derived concepts' physical SQL identity qualifier, keyed by logical qualified name
     *  ({@code aliasOrPackId::Name}); empty when the model declares no packs. */
    public Map<String, String> getPhysicalQualifierByConceptName() {
        return physicalQualifierByConceptName;
    }

    /** R6.2: model-declared inbound webhook doors, empty when the model declares none (a model
     *  with no webhooks behaves exactly as it did before this feature existed). */
    public List<WebhookAst> getWebhooks() {
        return Collections.unmodifiableList(webhooks);
    }

    private static List<ConceptAst> toConcepts(List<? extends EntityAst> source) {
        List<ConceptAst> out = new ArrayList<>();
        for (EntityAst entity : source) {
            out.add(ConceptAst.fromLegacyEntity(entity));
        }
        return out;
    }
}
