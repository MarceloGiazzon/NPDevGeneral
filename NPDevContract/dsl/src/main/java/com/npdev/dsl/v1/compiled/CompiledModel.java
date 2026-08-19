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
    private final List<CompiledAutoPanel> autoPanels;
    private final List<CompiledDocument> documents;
    private final CompiledExternalAi externalAi;
    private final CompiledSettings settings;
    private final List<CompiledRole> roles;
    private final List<CompiledPropertyScope> propertyScopes;
    private final List<CompiledProperty> properties;
    private final List<CompiledContext> contexts;
    private final List<CompiledConversion> conversions;
    private final List<CompiledWebhook> webhooks;
    private final List<CompiledSequence> sequences;

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
        this(namespace, dslVersion, version, entitiesByName, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates, List.of());
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
            List<CompiledAggregate> aggregates,
            List<CompiledAutoPanel> autoPanels
    ) {
        this(namespace, dslVersion, version, entitiesByName, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates, autoPanels,
                List.of());
    }

    /** REG-12 Slice 3: canonical constructor, adds {@code documents} (LNCH-10's `document` PAGE kind). */
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
            List<CompiledAggregate> aggregates,
            List<CompiledAutoPanel> autoPanels,
            List<CompiledDocument> documents
    ) {
        this(namespace, dslVersion, version, entitiesByName, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates, autoPanels,
                documents, null);
    }

    /** ADR-0009: adds {@code externalAi} (app-level egress settings). */
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
            List<CompiledAggregate> aggregates,
            List<CompiledAutoPanel> autoPanels,
            List<CompiledDocument> documents,
            CompiledExternalAi externalAi
    ) {
        this(namespace, dslVersion, version, entitiesByName, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates, autoPanels,
                documents, externalAi, null);
    }

    /** Move 6 Move A: canonical constructor, adds {@code settings} (app-level locale/strings/ui). */
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
            List<CompiledAggregate> aggregates,
            List<CompiledAutoPanel> autoPanels,
            List<CompiledDocument> documents,
            CompiledExternalAi externalAi,
            CompiledSettings settings
    ) {
        this(namespace, dslVersion, version, entitiesByName, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates, autoPanels,
                documents, externalAi, settings, List.of());
    }

    /** Wave 3 (RC-B1): adds {@code roles} (app-defined role -> permission ceiling declarations). */
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
            List<CompiledAggregate> aggregates,
            List<CompiledAutoPanel> autoPanels,
            List<CompiledDocument> documents,
            CompiledExternalAi externalAi,
            CompiledSettings settings,
            List<CompiledRole> roles
    ) {
        this(namespace, dslVersion, version, entitiesByName, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates, autoPanels,
                documents, externalAi, settings, roles, List.of(), List.of());
    }

    /** Wave 6 (RC-A1): adds {@code propertyScopes} + {@code properties} (the scoped-property
     *  cascade's declaration layer). */
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
            List<CompiledAggregate> aggregates,
            List<CompiledAutoPanel> autoPanels,
            List<CompiledDocument> documents,
            CompiledExternalAi externalAi,
            CompiledSettings settings,
            List<CompiledRole> roles,
            List<CompiledPropertyScope> propertyScopes,
            List<CompiledProperty> properties
    ) {
        this(namespace, dslVersion, version, entitiesByName, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates, autoPanels,
                documents, externalAi, settings, roles, propertyScopes, properties, List.of());
    }

    /** B20 (S2): adds {@code contexts} (bounded-context registry). */
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
            List<CompiledAggregate> aggregates,
            List<CompiledAutoPanel> autoPanels,
            List<CompiledDocument> documents,
            CompiledExternalAi externalAi,
            CompiledSettings settings,
            List<CompiledRole> roles,
            List<CompiledPropertyScope> propertyScopes,
            List<CompiledProperty> properties,
            List<CompiledContext> contexts
    ) {
        this(namespace, dslVersion, version, entitiesByName, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates, autoPanels,
                documents, externalAi, settings, roles, propertyScopes, properties, contexts, List.of());
    }

    /** S7 Phase B (B13): canonical constructor, adds {@code conversions} (the declarative conversion
     *  vocabulary -- see {@link CompiledConversion}). */
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
            List<CompiledAggregate> aggregates,
            List<CompiledAutoPanel> autoPanels,
            List<CompiledDocument> documents,
            CompiledExternalAi externalAi,
            CompiledSettings settings,
            List<CompiledRole> roles,
            List<CompiledPropertyScope> propertyScopes,
            List<CompiledProperty> properties,
            List<CompiledContext> contexts,
            List<CompiledConversion> conversions
    ) {
        this(namespace, dslVersion, version, entitiesByName, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates, autoPanels,
                documents, externalAi, settings, roles, propertyScopes, properties, contexts, conversions, List.of());
    }

    /** R6.2: canonical constructor, adds {@code webhooks} (model-declared inbound webhook doors --
     *  see {@link CompiledWebhook}). */
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
            List<CompiledAggregate> aggregates,
            List<CompiledAutoPanel> autoPanels,
            List<CompiledDocument> documents,
            CompiledExternalAi externalAi,
            CompiledSettings settings,
            List<CompiledRole> roles,
            List<CompiledPropertyScope> propertyScopes,
            List<CompiledProperty> properties,
            List<CompiledContext> contexts,
            List<CompiledConversion> conversions,
            List<CompiledWebhook> webhooks
    ) {
        this(namespace, dslVersion, version, entitiesByName, domainTypes, capabilities, bindings, events, flows,
                orchestrationRules, queries, ruleProfiles, procedures, panels, guidePages, aggregates, autoPanels,
                documents, externalAi, settings, roles, propertyScopes, properties, contexts, conversions, webhooks,
                List.of());
    }

    /** R5.3: canonical constructor, adds {@code sequences} (model-declared document-numbering
     *  counters -- see {@link CompiledSequence}). */
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
            List<CompiledAggregate> aggregates,
            List<CompiledAutoPanel> autoPanels,
            List<CompiledDocument> documents,
            CompiledExternalAi externalAi,
            CompiledSettings settings,
            List<CompiledRole> roles,
            List<CompiledPropertyScope> propertyScopes,
            List<CompiledProperty> properties,
            List<CompiledContext> contexts,
            List<CompiledConversion> conversions,
            List<CompiledWebhook> webhooks,
            List<CompiledSequence> sequences
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
        this.autoPanels = new ArrayList<>(autoPanels);
        this.documents = new ArrayList<>(documents);
        this.externalAi = externalAi;
        this.settings = settings == null ? CompiledSettings.defaults() : settings;
        this.roles = roles == null ? List.of() : List.copyOf(roles);
        this.propertyScopes = propertyScopes == null ? List.of() : List.copyOf(propertyScopes);
        this.properties = properties == null ? List.of() : List.copyOf(properties);
        this.contexts = contexts == null ? List.of() : List.copyOf(contexts);
        this.conversions = conversions == null ? List.of() : List.copyOf(conversions);
        this.webhooks = webhooks == null ? List.of() : List.copyOf(webhooks);
        this.sequences = sequences == null ? List.of() : List.copyOf(sequences);
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

    public List<CompiledAutoPanel> getAutoPanels() {
        return Collections.unmodifiableList(autoPanels);
    }

    public List<CompiledDocument> getDocuments() {
        return Collections.unmodifiableList(documents);
    }

    /** ADR-0009: app-level external-AI delegation settings, or null if the model declares none (denied by default). */
    public CompiledExternalAi getExternalAi() {
        return externalAi;
    }

    /** Move 6 Move A: app-level settings. Never null -- platform defaults when the model declares none. */
    public CompiledSettings getSettings() {
        return settings;
    }

    /** Wave 3 (RC-B1): app-defined roles, empty when the model declares none (the built-in
     *  USER/OPERATOR/ADMIN trio then behaves exactly as before this feature existed). */
    public List<CompiledRole> getRoles() {
        return Collections.unmodifiableList(roles);
    }

    /** Wave 6 (RC-A1): declared scope levels of the property cascade, most specific first, empty
     *  when the model declares none. */
    public List<CompiledPropertyScope> getPropertyScopes() {
        return Collections.unmodifiableList(propertyScopes);
    }

    /** Wave 6 (RC-A1): declared runtime properties, empty when the model declares none. */
    public List<CompiledProperty> getProperties() {
        return Collections.unmodifiableList(properties);
    }

    /** B20 (S2): declared bounded contexts, empty when the model declares none (a model with no
     *  contexts behaves exactly as it did before this feature existed). */
    public List<CompiledContext> getContexts() {
        return Collections.unmodifiableList(contexts);
    }

    /** S7 Phase B (B13): declared conversions, empty when the model declares none. */
    public List<CompiledConversion> getConversions() {
        return Collections.unmodifiableList(conversions);
    }

    /** R6.2: model-declared inbound webhook doors, empty when the model declares none. */
    public List<CompiledWebhook> getWebhooks() {
        return Collections.unmodifiableList(webhooks);
    }

    /** R6.2: the single webhook whose {@code source} matches the given path segment, or empty --
     *  the lookup {@code WebhookInboundController} uses for {@code POST /api/hooks/{source}}. */
    public Optional<CompiledWebhook> findWebhookBySource(String source) {
        if (source == null || source.isBlank()) {
            return Optional.empty();
        }
        for (CompiledWebhook webhook : webhooks) {
            if (webhook.source().equals(source)) {
                return Optional.of(webhook);
            }
        }
        return Optional.empty();
    }

    /** R5.3: model-declared document-numbering counters, empty when the model declares none. */
    public List<CompiledSequence> getSequences() {
        return Collections.unmodifiableList(sequences);
    }

    /** R5.3: the single sequence whose {@code name} matches -- the lookup {@code
     *  ConfiguredConceptGatewaySemanticPolicy} uses to resolve a field's {@code
     *  nextNumber('name')} defaultExpression. */
    public Optional<CompiledSequence> findSequenceByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        for (CompiledSequence sequence : sequences) {
            if (sequence.name().equalsIgnoreCase(name)) {
                return Optional.of(sequence);
            }
        }
        return Optional.empty();
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
