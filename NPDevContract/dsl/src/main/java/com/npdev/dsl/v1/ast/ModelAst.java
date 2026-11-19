package com.npdev.dsl.v1.ast;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@SuppressWarnings("deprecation")
public final class ModelAst {
    public static final String DEFAULT_DSL_VERSION = "3.0.0";

    private final String namespace;
    private final String dslVersion;
    private final String version;
    private final List<ConceptAst> concepts;
    private final List<DomainTypeAst> domainTypes;
    private final List<CapabilityAst> capabilities;
    private final List<EventAst> events;
    private final List<FlowAst> flows;
    private final List<QueryAst> queries;
    private final List<PanelAst> panels;
    private final List<GuidePageAst> guidePages;
    private final List<AggregateAst> aggregates;
    private final List<AutoPanelAst> autoPanels;
    private final List<String> parserWarnings;
    private final ExternalAiAst externalAi;
    private final SettingsAst settings;
    private final List<RoleAst> roles;
    private final List<PropertyScopeAst> propertyScopes;
    private final List<PropertyAst> properties;
    private final List<ContextAst> contexts;
    private final List<ConversionAst> conversions;
    private final Map<String, String> physicalQualifierByConceptName;
    private final List<SequenceAst> sequences;
    private final List<SeedAst> seeds;
    private final AppShellAst appShell;
    private final AgentAccessAst agentAccess;
    private final List<JsonNode> experiences;
    private final List<JsonNode> screens;

    public ModelAst(String namespace, String version, List<? extends EntityAst> entities) {
        this(namespace, DEFAULT_DSL_VERSION, version, entities, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public ModelAst(String namespace, String dslVersion, String version, List<? extends EntityAst> entities) {
        this(namespace, dslVersion, version, entities, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public ModelAst(
            String namespace,
            String dslVersion,
            String version,
            List<? extends EntityAst> entities,
            List<CapabilityAst> capabilities,
            List<EventAst> events,
            List<FlowAst> flows
    ) {
        this(namespace, dslVersion, version, entities, List.of(), capabilities, events, flows, List.of());
    }

    public ModelAst(
            String namespace,
            String version,
            List<? extends EntityAst> entities,
            List<CapabilityAst> capabilities,
            List<EventAst> events,
            List<FlowAst> flows,
            List<String> parserWarnings
    ) {
        this(namespace, DEFAULT_DSL_VERSION, version, entities, List.of(), capabilities, events, flows, parserWarnings);
    }

    public ModelAst(
            String namespace,
            String dslVersion,
            String version,
            List<? extends EntityAst> entities,
            List<CapabilityAst> capabilities,
            List<EventAst> events,
            List<FlowAst> flows,
            List<String> parserWarnings
    ) {
        this(namespace, dslVersion, version, entities, List.of(), capabilities, events, flows,
                List.of(), List.of(), List.of(), List.of(), parserWarnings);
    }


    public ModelAst(
            String namespace,
            String dslVersion,
            String version,
            List<? extends EntityAst> entities,
            List<DomainTypeAst> domainTypes,
            List<CapabilityAst> capabilities,
            List<EventAst> events,
            List<FlowAst> flows,
            List<String> parserWarnings
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, events, flows, List.of(), List.of(), List.of(), List.of(), parserWarnings);
    }

    public ModelAst(
            String namespace,
            String dslVersion,
            String version,
            List<? extends EntityAst> entities,
            List<DomainTypeAst> domainTypes,
            List<CapabilityAst> capabilities,
            List<EventAst> events,
            List<FlowAst> flows,
            List<QueryAst> queries,
            List<PanelAst> panels,
            List<String> parserWarnings
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, events, flows, queries, panels, List.of(), List.of(), parserWarnings);
    }

    public ModelAst(
            String namespace,
            String dslVersion,
            String version,
            List<? extends EntityAst> entities,
            List<DomainTypeAst> domainTypes,
            List<CapabilityAst> capabilities,
            List<EventAst> events,
            List<FlowAst> flows,
            List<QueryAst> queries,
            List<PanelAst> panels,
            List<GuidePageAst> guidePages,
            List<String> parserWarnings
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, events, flows, queries, panels, guidePages, List.of(), parserWarnings);
    }

    public ModelAst(
            String namespace,
            String dslVersion,
            String version,
            List<? extends EntityAst> entities,
            List<DomainTypeAst> domainTypes,
            List<CapabilityAst> capabilities,
            List<EventAst> events,
            List<FlowAst> flows,
            List<QueryAst> queries,
            List<PanelAst> panels,
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<String> parserWarnings
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, events, flows, queries, panels, guidePages, aggregates,
                List.of(), parserWarnings);
    }

    public ModelAst(
            String namespace,
            String dslVersion,
            String version,
            List<? extends EntityAst> entities,
            List<DomainTypeAst> domainTypes,
            List<CapabilityAst> capabilities,
            List<EventAst> events,
            List<FlowAst> flows,
            List<QueryAst> queries,
            List<PanelAst> panels,
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<String> parserWarnings
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, events, flows, queries, panels, guidePages, aggregates,
                autoPanels, parserWarnings, null);
    }

    /** ADR-0009: adds {@code externalAi} (app-level egress settings). */
    public ModelAst(
            String namespace,
            String dslVersion,
            String version,
            List<? extends EntityAst> entities,
            List<DomainTypeAst> domainTypes,
            List<CapabilityAst> capabilities,
            List<EventAst> events,
            List<FlowAst> flows,
            List<QueryAst> queries,
            List<PanelAst> panels,
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<String> parserWarnings,
            ExternalAiAst externalAi
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, events, flows, queries, panels, guidePages, aggregates, autoPanels,
                parserWarnings, externalAi, null);
    }

    /** Move 6 Move A: adds {@code settings} (app-level locale/strings/ui). */
    public ModelAst(
            String namespace,
            String dslVersion,
            String version,
            List<? extends EntityAst> entities,
            List<DomainTypeAst> domainTypes,
            List<CapabilityAst> capabilities,
            List<EventAst> events,
            List<FlowAst> flows,
            List<QueryAst> queries,
            List<PanelAst> panels,
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<String> parserWarnings,
            ExternalAiAst externalAi,
            SettingsAst settings
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, events, flows, queries, panels, guidePages, aggregates, autoPanels,
                parserWarnings, externalAi, settings, List.of());
    }

    /** Wave 3 (RC-B1): adds {@code roles} (app-defined role -> permission ceiling declarations). */
    public ModelAst(
            String namespace,
            String dslVersion,
            String version,
            List<? extends EntityAst> entities,
            List<DomainTypeAst> domainTypes,
            List<CapabilityAst> capabilities,
            List<EventAst> events,
            List<FlowAst> flows,
            List<QueryAst> queries,
            List<PanelAst> panels,
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<String> parserWarnings,
            ExternalAiAst externalAi,
            SettingsAst settings,
            List<RoleAst> roles
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, events, flows, queries, panels, guidePages, aggregates, autoPanels,
                parserWarnings, externalAi, settings, roles, List.of(), List.of());
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
            List<EventAst> events,
            List<FlowAst> flows,
            List<QueryAst> queries,
            List<PanelAst> panels,
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<String> parserWarnings,
            ExternalAiAst externalAi,
            SettingsAst settings,
            List<RoleAst> roles,
            List<PropertyScopeAst> propertyScopes,
            List<PropertyAst> properties
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, events, flows, queries, panels, guidePages, aggregates, autoPanels,
                parserWarnings, externalAi, settings, roles, propertyScopes, properties,
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
            List<EventAst> events,
            List<FlowAst> flows,
            List<QueryAst> queries,
            List<PanelAst> panels,
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<String> parserWarnings,
            ExternalAiAst externalAi,
            SettingsAst settings,
            List<RoleAst> roles,
            List<PropertyScopeAst> propertyScopes,
            List<PropertyAst> properties,
            List<ContextAst> contexts
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, events, flows, queries, panels, guidePages, aggregates, autoPanels,
                parserWarnings, externalAi, settings, roles, propertyScopes, properties,
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
            List<EventAst> events,
            List<FlowAst> flows,
            List<QueryAst> queries,
            List<PanelAst> panels,
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<String> parserWarnings,
            ExternalAiAst externalAi,
            SettingsAst settings,
            List<RoleAst> roles,
            List<PropertyScopeAst> propertyScopes,
            List<PropertyAst> properties,
            List<ContextAst> contexts,
            List<ConversionAst> conversions
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, events, flows, queries, panels, guidePages, aggregates, autoPanels,
                parserWarnings, externalAi, settings, roles, propertyScopes, properties,
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
            List<EventAst> events,
            List<FlowAst> flows,
            List<QueryAst> queries,
            List<PanelAst> panels,
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
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
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, events, flows, queries, panels, guidePages, aggregates, autoPanels,
                parserWarnings, externalAi, settings, roles, propertyScopes, properties,
                contexts, conversions, physicalQualifierByConceptName, List.of());
    }

    /** R5.3: adds {@code sequences} (model-declared document-numbering counters -- see
     *  {@link SequenceAst}). */
    public ModelAst(
            String namespace,
            String dslVersion,
            String version,
            List<? extends EntityAst> entities,
            List<DomainTypeAst> domainTypes,
            List<CapabilityAst> capabilities,
            List<EventAst> events,
            List<FlowAst> flows,
            List<QueryAst> queries,
            List<PanelAst> panels,
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<String> parserWarnings,
            ExternalAiAst externalAi,
            SettingsAst settings,
            List<RoleAst> roles,
            List<PropertyScopeAst> propertyScopes,
            List<PropertyAst> properties,
            List<ContextAst> contexts,
            List<ConversionAst> conversions,
            Map<String, String> physicalQualifierByConceptName,
            List<SequenceAst> sequences
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, events, flows, queries, panels, guidePages, aggregates, autoPanels,
                parserWarnings, externalAi, settings, roles, propertyScopes, properties,
                contexts, conversions, physicalQualifierByConceptName, sequences, List.of());
    }

    /** R8.8: adds {@code seeds} (model/pack-declared first-boot seed rows -- see {@link SeedAst}). */
    public ModelAst(
            String namespace,
            String dslVersion,
            String version,
            List<? extends EntityAst> entities,
            List<DomainTypeAst> domainTypes,
            List<CapabilityAst> capabilities,
            List<EventAst> events,
            List<FlowAst> flows,
            List<QueryAst> queries,
            List<PanelAst> panels,
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<String> parserWarnings,
            ExternalAiAst externalAi,
            SettingsAst settings,
            List<RoleAst> roles,
            List<PropertyScopeAst> propertyScopes,
            List<PropertyAst> properties,
            List<ContextAst> contexts,
            List<ConversionAst> conversions,
            Map<String, String> physicalQualifierByConceptName,
            List<SequenceAst> sequences,
            List<SeedAst> seeds
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, events, flows, queries, panels, guidePages, aggregates, autoPanels,
                parserWarnings, externalAi, settings, roles, propertyScopes, properties,
                contexts, conversions, physicalQualifierByConceptName, sequences, seeds, null);
    }

    /** Path A P6.3: canonical constructor, adds {@code appShell} (declared navigation structure and
     *  default route for the generated app's shell chrome -- see {@link AppShellAst}). */
    public ModelAst(
            String namespace,
            String dslVersion,
            String version,
            List<? extends EntityAst> entities,
            List<DomainTypeAst> domainTypes,
            List<CapabilityAst> capabilities,
            List<EventAst> events,
            List<FlowAst> flows,
            List<QueryAst> queries,
            List<PanelAst> panels,
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<String> parserWarnings,
            ExternalAiAst externalAi,
            SettingsAst settings,
            List<RoleAst> roles,
            List<PropertyScopeAst> propertyScopes,
            List<PropertyAst> properties,
            List<ContextAst> contexts,
            List<ConversionAst> conversions,
            Map<String, String> physicalQualifierByConceptName,
            List<SequenceAst> sequences,
            List<SeedAst> seeds,
            AppShellAst appShell
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, events, flows, queries, panels, guidePages, aggregates, autoPanels,
                parserWarnings, externalAi, settings, roles, propertyScopes, properties,
                contexts, conversions, physicalQualifierByConceptName, sequences, seeds, appShell, null);
    }

    /** AGENT-1: canonical constructor, adds {@code agentAccess} (what an AI agent may see and do, and
     *  on which channels -- see {@link AgentAccessAst}). */
    public ModelAst(
            String namespace,
            String dslVersion,
            String version,
            List<? extends EntityAst> entities,
            List<DomainTypeAst> domainTypes,
            List<CapabilityAst> capabilities,
            List<EventAst> events,
            List<FlowAst> flows,
            List<QueryAst> queries,
            List<PanelAst> panels,
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<String> parserWarnings,
            ExternalAiAst externalAi,
            SettingsAst settings,
            List<RoleAst> roles,
            List<PropertyScopeAst> propertyScopes,
            List<PropertyAst> properties,
            List<ContextAst> contexts,
            List<ConversionAst> conversions,
            Map<String, String> physicalQualifierByConceptName,
            List<SequenceAst> sequences,
            List<SeedAst> seeds,
            AppShellAst appShell,
            AgentAccessAst agentAccess
    ) {
        this(namespace, dslVersion, version, entities, domainTypes, capabilities, events, flows, queries, panels, guidePages, aggregates, autoPanels,
                parserWarnings, externalAi, settings, roles, propertyScopes, properties,
                contexts, conversions, physicalQualifierByConceptName, sequences, seeds, appShell, agentAccess, null, null);
    }

    /** FORM P5-T1: canonical constructor, adds {@code experiences} and {@code screens} -- carried as opaque
     *  JSON objects (see {@link #getExperiences()}); a null list means none. */
    public ModelAst(
            String namespace,
            String dslVersion,
            String version,
            List<? extends EntityAst> entities,
            List<DomainTypeAst> domainTypes,
            List<CapabilityAst> capabilities,
            List<EventAst> events,
            List<FlowAst> flows,
            List<QueryAst> queries,
            List<PanelAst> panels,
            List<GuidePageAst> guidePages,
            List<AggregateAst> aggregates,
            List<AutoPanelAst> autoPanels,
            List<String> parserWarnings,
            ExternalAiAst externalAi,
            SettingsAst settings,
            List<RoleAst> roles,
            List<PropertyScopeAst> propertyScopes,
            List<PropertyAst> properties,
            List<ContextAst> contexts,
            List<ConversionAst> conversions,
            Map<String, String> physicalQualifierByConceptName,
            List<SequenceAst> sequences,
            List<SeedAst> seeds,
            AppShellAst appShell,
            AgentAccessAst agentAccess,
            List<JsonNode> experiences,
            List<JsonNode> screens
    ) {
        this.namespace = namespace;
        this.dslVersion = dslVersion;
        this.version = version;
        this.concepts = toConcepts(entities);
        this.domainTypes = new ArrayList<>(domainTypes);
        this.capabilities = new ArrayList<>(capabilities);
        this.events = new ArrayList<>(events);
        this.flows = new ArrayList<>(flows);
        this.queries = new ArrayList<>(queries);
        this.panels = new ArrayList<>(panels);
        this.guidePages = new ArrayList<>(guidePages);
        this.aggregates = new ArrayList<>(aggregates);
        this.autoPanels = new ArrayList<>(autoPanels);
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
        this.sequences = sequences == null ? new ArrayList<>() : new ArrayList<>(sequences);
        this.seeds = seeds == null ? new ArrayList<>() : new ArrayList<>(seeds);
        this.appShell = appShell;
        this.agentAccess = agentAccess;
        this.experiences = experiences == null ? new ArrayList<>() : new ArrayList<>(experiences);
        this.screens = screens == null ? new ArrayList<>() : new ArrayList<>(screens);
    }

    /**
     * FORM P5-T1: the model's {@code experiences[]} (who lands where, on which device), exactly as authored --
     * opaque JSON objects, deliberately not a typed AST: the archetype set is closed but its options evolve, and
     * the only consumers are {@code ExperienceValidation} and the experience emitter, both of which read the
     * JSON. Never null.
     */
    public List<JsonNode> getExperiences() {
        return Collections.unmodifiableList(experiences);
    }

    /** FORM P5-T1: the model's {@code screens[]} (the archetype screens experiences navigate to), opaque JSON; never null. */
    public List<JsonNode> getScreens() {
        return Collections.unmodifiableList(screens);
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

    public List<EventAst> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public List<FlowAst> getFlows() {
        return Collections.unmodifiableList(flows);
    }

    public List<QueryAst> getQueries() {
        return Collections.unmodifiableList(queries);
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

    /**
     * The compile-time picker declarations: {@code screens[]} entries of kind {@code picker}, in declaration order
     * (P5-T9a: the top-level {@code selectors[]} key was removed; a picker screen is its authoring form). Derived on
     * every call from {@link #getScreens()} so there is one source of truth; never null.
     */
    public List<SelectorAst> getSelectors() {
        return SelectorAst.fromPickerScreens(screens);
    }

    /**
     * The compile-time document declarations: {@code screens[]} entries of kind {@code document}, in declaration
     * order (P5-T9b: the top-level {@code documents[]} key was removed; a document screen is its authoring form).
     * Derived on every call from {@link #getScreens()} so there is one source of truth; never null.
     */
    public List<DocumentAst> getDocuments() {
        return DocumentAst.fromDocumentScreens(screens);
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

    /** R5.3: model-declared document-numbering counters, empty when the model declares none (a
     *  model with no sequences behaves exactly as it did before this feature existed). */
    public List<SequenceAst> getSequences() {
        return Collections.unmodifiableList(sequences);
    }

    /** R8.8: model/pack-declared first-boot seed rows, empty when the model declares none (a model
     *  with no seeds behaves exactly as it did before this feature existed). Declaration order is
     *  preserved (never sorted) -- it is semantically load-bearing, since a later record's {@code
     *  data} may reference an earlier one's {@code alias} via {@code "$ref:<alias>"}. */
    public List<SeedAst> getSeeds() {
        return Collections.unmodifiableList(seeds);
    }

    /** Path A P6.3: declared navigation structure and default route for the shell chrome, or
     *  {@code null} if the model declares none (the shell's existing default behavior applies). */
    public AppShellAst getAppShell() {
        return appShell;
    }

    /** AGENT-1: what an AI agent may see and do, and on which channels, or {@code null} if the model
     *  declares none (agent access is entirely off in that case). */
    public AgentAccessAst getAgentAccess() {
        return agentAccess;
    }

    private static List<ConceptAst> toConcepts(List<? extends EntityAst> source) {
        List<ConceptAst> out = new ArrayList<>();
        for (EntityAst entity : source) {
            out.add(ConceptAst.fromLegacyEntity(entity));
        }
        return out;
    }
}
