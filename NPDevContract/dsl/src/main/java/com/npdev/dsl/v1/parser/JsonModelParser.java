package com.npdev.dsl.v1.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.*;
import com.npdev.dsl.v1.validation.JsonModelSchemaValidator;
import com.npdev.dsl.v1.validation.ValidationDiagnostic;
import com.npdev.dsl.v1.validation.ValidationLayer;
import com.npdev.dsl.v1.validation.ValidationSeverity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal JSON -> AST parser for model.json.
 */
public final class JsonModelParser {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonModelSchemaValidator schemaValidator = new JsonModelSchemaValidator();
    private static final String SUPPORTED_DSL_VERSION = ModelAst.DEFAULT_DSL_VERSION;
    private static final String CURRENT_SCHEMA_VERSION = "1.0.0";
    private static final Pattern FIELD_MATCHES_PATTERN =
            Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\.matches\\s*\\(.*\\)\\s*$");
    private static final Pattern FIELD_COMPARISON_PATTERN =
            Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(==|!=|>=|<=|>|<)\\s*.+$");
    private static final Pattern FIELD_UNIQUE_BY_PATTERN =
            Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\.uniqueBy\\s*\\(\\s*[A-Za-z_][A-Za-z0-9_]*\\s*\\)\\s*$");
    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Set<String> NON_FIELD_IDENTIFIERS = Set.of(
            "true", "false", "null", "and", "or", "not", "cap", "ctx"
    );

    /** PACK-2: converts a resolver-side {@link ModelSourceResolver.PackOrigin} carrier into the
     *  AST-level {@link OriginAst} every parsed member kind this card covers attaches -- null in,
     *  null out (not pack-contributed). */
    private static OriginAst toOriginAst(ModelSourceResolver.PackOrigin origin) {
        return origin == null
                ? null
                : new OriginAst(origin.packId(), origin.packVersion(), origin.packDigest(), origin.sealed());
    }

    /** PACK-2: looks up the pack origin recorded for one member, by {@code ModelSourceResolver
     *  .MODEL_ARRAY_KEYS} kind (e.g. "concepts", "queries") and the member's own (already-qualified
     *  for a pack member, unqualified for a root-declared one) name -- returns null (not pack-
     *  contributed) when the map has no entry, which is always the case for a root-/context-declared
     *  member since only {@code PackDependencyGraphWalker} ever populates this map. */
    private static OriginAst originFor(
            Map<String, Map<String, ModelSourceResolver.PackOrigin>> originByQualifiedMemberName,
            String kind,
            String name
    ) {
        return toOriginAst(originByQualifiedMemberName.getOrDefault(kind, Map.of()).get(name));
    }

    public ModelAst parse(Path modelJsonPath) throws IOException {
        if (!Files.exists(modelJsonPath)) {
            throw new IOException("model.json not found: " + modelJsonPath);
        }

        // Detect deprecated/legacy authoring shapes on the RAW root before pack/fragment
        // resolution runs. The resolver rejects unknown top-level keys (e.g. legacy
        // 'entities') with a generic IOException, which would otherwise mask the helpful
        // DeprecationException + migration guidance below.
        JsonNode rawRoot = mapper.readTree(Files.readAllBytes(modelJsonPath));
        checkDeprecatedAuthoringShape(rawRoot);

        return parse(new ModelSourceResolver().resolve(modelJsonPath));
    }

    public ModelAst parse(ResolvedModelSource source) throws IOException {
        if (source == null) {
            throw new IOException("Resolved model source is required");
        }
        return parse(source.resolvedRoot(), source.rootModelPath().toString(), source.warnings().stream()
                .map(ValidationDiagnostic::getMessage)
                .toList(), source.physicalQualifierByConceptName(), source.originByQualifiedMemberName());
    }

    public ModelAst parse(JsonNode root) throws IOException {
        return parse(root, "<resolved-model>", List.of(), Map.of(), Map.of());
    }

    /**
     * Rejects deprecated/legacy authoring shapes with a {@link DeprecationException} carrying
     * migration guidance. Runs both on the raw root (before pack/fragment resolution) and on the
     * resolved root, so the helpful deprecation message is never masked by the resolver's generic
     * "unsupported top-level key" IOException.
     */
    private void checkDeprecatedAuthoringShape(JsonNode root) throws DeprecationException {
        if (root == null || !root.isObject()) {
            return;
        }
        String schemaTarget = readText(root, "$schema");
        if (isDeprecatedSchemaTarget(schemaTarget)) {
            throw new DeprecationException(new ValidationDiagnostic(
                    ValidationLayer.STRUCTURAL,
                    ValidationSeverity.ERROR,
                    "LEGACY_SCHEMA_TARGET",
                    "Model references a deprecated schema target. Use model.schema.json with schemaVersion '" + CURRENT_SCHEMA_VERSION + "'.",
                    "NPDevContract",
                    "$schema",
                    null,
                    null,
                    "schema",
                    null,
                    "Replace the $schema value with NPDevContract/schemas/model.schema.json.",
                    "legacy-schema-target"
            ));
        }
        if (root.has("entities")) {
            throw new DeprecationException(new ValidationDiagnostic(
                    ValidationLayer.STRUCTURAL,
                    ValidationSeverity.ERROR,
                    "LEGACY_ENTITIES_ROOT",
                    "Official DSL models must use root 'concepts'. Root 'entities' is no longer supported.",
                    "NPDevContract",
                    "$.entities",
                    null,
                    null,
                    "concepts",
                    null,
                    "Rename root 'entities' to 'concepts' or run: npdev migrate legacy-model --input old.json --output new.json.",
                    "legacy-entities-root"
            ));
        }
    }

    public ModelAst parse(JsonNode root, String sourceLabel) throws IOException {
        return parse(root, sourceLabel, List.of(), Map.of(), Map.of());
    }

    private ModelAst parse(
            JsonNode root,
            String sourceLabel,
            List<String> sourceWarnings,
            Map<String, String> physicalQualifierByConceptName,
            Map<String, Map<String, ModelSourceResolver.PackOrigin>> originByQualifiedMemberName
    ) throws IOException {
        if (root == null || !root.isObject()) {
            throw new IOException("model.json root must be an object");
        }
        checkDeprecatedAuthoringShape(root);
        String namespace = firstNonBlank(readText(root, "namespace"), readText(root, "model"));
        if (namespace == null || namespace.isBlank()) {
            throw new IOException("Missing/blank required field: namespace (or alias: model)");
        }
        String dslVersion = readText(root, "dslVersion");
        if (dslVersion == null || dslVersion.isBlank()) {
            throw new IOException("Missing required field: dslVersion. Supported value: \"" + SUPPORTED_DSL_VERSION + "\".");
        }
        if (!SUPPORTED_DSL_VERSION.equals(dslVersion)) {
            throw new IOException("Unsupported dslVersion '" + dslVersion + "'. Supported value: \"" + SUPPORTED_DSL_VERSION + "\".");
        }
        schemaValidator.validate(root, sourceLabel);

        String schemaVersion = readText(root, "schemaVersion");
        if (schemaVersion != null && !schemaVersion.isBlank() && !CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IOException("Unsupported schemaVersion '" + schemaVersion + "'. Supported value: \"" + CURRENT_SCHEMA_VERSION + "\".");
        }
        String version = requiredText(root, "version");

        List<ConceptAst> concepts = new ArrayList<>();
        List<DomainTypeAst> domainTypes = new ArrayList<>();
        List<CapabilityAst> capabilities = new ArrayList<>();
        List<CapabilityBindingAst> bindings = new ArrayList<>();
        List<EventAst> events = new ArrayList<>();
        List<FlowAst> flows = new ArrayList<>();
        List<OrchestrationAst> orchestrationRules = new ArrayList<>();
        List<QueryAst> queries = new ArrayList<>();
        List<RuleProfileAst> ruleProfiles = new ArrayList<>();
        List<ProcedureAst> procedures = new ArrayList<>();
        List<PanelAst> panels = new ArrayList<>();
        List<GuidePageAst> guidePages = new ArrayList<>();
        List<AggregateAst> aggregates = new ArrayList<>();
        List<AutoPanelAst> autoPanels = new ArrayList<>();
        List<SelectorAst> selectors = new ArrayList<>();
        List<DocumentAst> documents = new ArrayList<>();
        List<com.npdev.dsl.v1.ast.RoleAst> roles = new ArrayList<>();
        List<com.npdev.dsl.v1.ast.PropertyScopeAst> propertyScopes = new ArrayList<>();
        List<com.npdev.dsl.v1.ast.PropertyAst> properties = new ArrayList<>();
        List<com.npdev.dsl.v1.ast.ContextAst> contexts = new ArrayList<>();
        List<com.npdev.dsl.v1.ast.WebhookAst> webhooks = new ArrayList<>();
        List<String> parserWarnings = new ArrayList<>(sourceWarnings == null ? List.of() : sourceWarnings);
        Map<String, ConceptAst> conceptsByLowerName = new LinkedHashMap<>();

        JsonNode conceptsNode = root.get("concepts");
        JsonNode domainTypesNode = root.get("domainTypes");
        if (conceptsNode == null || !conceptsNode.isArray()) {
            throw new IOException("concepts must be an array");
        }

        if (domainTypesNode != null) {
            if (!domainTypesNode.isArray()) {
                throw new IOException("domainTypes must be an array");
            }
            for (JsonNode domainTypeNode : domainTypesNode) {
                String domainTypeName = requiredText(domainTypeNode, "name");
                String baseType = requiredText(domainTypeNode, "baseType");
                SchemaAst validationSchema = parseSchema(
                        domainTypeNode.get("validation"),
                        "domainTypes[" + domainTypeName + "].validation"
                );
                List<String> normalizationRules = parseTextArray(domainTypeNode.get("normalization"));
                String formatHint = readText(domainTypeNode, "format");
                List<String> examples = parseTextArray(domainTypeNode.get("examples"));
                DomainTypeUiAst ui = parseDomainTypeUi(domainTypeNode.get("ui"), "domainTypes[" + domainTypeName + "].ui");
                domainTypes.add(new DomainTypeAst(
                        domainTypeName,
                        baseType,
                        validationSchema,
                        normalizationRules,
                        formatHint,
                        examples,
                        ui,
                        originFor(originByQualifiedMemberName, "domainTypes", domainTypeName)
                ));
            }
        }

        for (JsonNode ent : conceptsNode) {
            String name = requiredText(ent, "name");
            String extendsName = readText(ent, "extends");
            String specializesName = readText(ent, "specializes");
            PresentationMetadataAst conceptUi = parsePresentationMetadata(
                    ent.get("ui"),
                    "concepts[" + name + "].ui"
            );
            List<FieldAst> fields = new ArrayList<>();
            List<InvariantAst> invariants = new ArrayList<>();
            List<EventAst> conceptEvents = new ArrayList<>();
            LifecycleAst lifecycle = parseLifecycle(ent.get("lifecycle"), "concepts[" + name + "].lifecycle");

            JsonNode fieldsNode = ent.get("fields");
            if (fieldsNode == null || !fieldsNode.isArray()) {
                throw new IOException("Concept " + name + " fields must be an array");
            }

            for (JsonNode f : fieldsNode) {
                String fname = requiredText(f, "name");
                String ftype = requiredText(f, "type");
                boolean id = f.has("id") && f.get("id").asBoolean(false);
                boolean required = readBooleanFlag(f, "required");
                boolean unique = f.has("unique") && f.get("unique").asBoolean(false);
                List<EnumOptionAst> enumOptions = parseEnumOptions(
                        f.get("enumValues") != null ? f.get("enumValues") : f.get("values"),
                        "concepts[" + name + "].fields[" + fname + "].enumValues"
                );
                List<String> enumValues = enumOptions.stream()
                        .map(EnumOptionAst::getValue)
                        .toList();
                ReferenceSemanticsAst referenceSemantics = parseReferenceSemantics(
                        f.get("reference"),
                        "concepts[" + name + "].fields[" + fname + "].reference"
                );
                String referenceTarget = firstNonBlank(
                        readText(f, "ref"),
                        firstNonBlank(
                                referenceSemantics == null ? null : referenceSemantics.getTarget(),
                                readText(f, "reference")
                        )
                );
                String domainType = readText(f, "domainType");
                String connectable = readText(f, "connectable");
                String renamedFrom = readText(f, "renamedFrom");
                boolean sensitive = f.has("sensitive") && f.get("sensitive").asBoolean(false);
                SchemaAst fieldSchema = parseSchema(f, "concepts[" + name + "].fields[" + fname + "]");
                PresentationMetadataAst fieldUi = parsePresentationMetadata(
                        f.get("ui"),
                        "concepts[" + name + "].fields[" + fname + "].ui"
                );
                FileMetadataAst fileMetadata = parseFileMetadata(f.get("file"));
                FieldPickerAst picker = parseFieldPicker(f.get("picker"));
                FieldAccessAst fieldAccess = parseFieldAccess(
                        f.get("access"), "concepts[" + name + "].fields[" + fname + "].access");

                fields.add(new FieldAst(
                        fname,
                        ftype,
                        id,
                        required,
                        unique,
                        enumValues,
                        referenceTarget,
                        referenceSemantics,
                        domainType,
                        fieldSchema,
                        enumOptions,
                        fieldUi,
                        connectable,
                        renamedFrom,
                        fileMetadata,
                        sensitive,
                        picker,
                        fieldAccess
                ));
            }

            JsonNode invNode = ent.get("invariants");
            if (invNode != null && invNode.isArray()) {
                for (JsonNode inv : invNode) {
                    String iname = readText(inv, "name");
                    String itype = readText(inv, "type");
                    String rule = readText(inv, "rule");
                    String expression = firstNonBlank(readText(inv, "expression"), readText(inv, "expr"));
                    String invariantSpecializes = readText(inv, "specializes");
                    boolean invariantOverride = inv.has("override") && inv.get("override").asBoolean(false);

                    List<String> invFields = parseTextArray(inv.get("fields"));
                    if ((itype == null || itype.isBlank()) && rule != null && !rule.isBlank()) {
                        ParsedRule parsed = parseInvariantRule(rule);
                        itype = parsed.type();
                        invFields = parsed.fields();
                        expression = parsed.expression();
                    }
                    if ((itype == null || itype.isBlank()) && expression != null && !expression.isBlank()) {
                        itype = "expression";
                    }

                    if (itype == null || itype.isBlank()) {
                        throw new IOException("Invariant must declare 'type' or parseable 'rule'");
                    }

                    if ("expression".equalsIgnoreCase(itype)) {
                        if (expression == null || expression.isBlank()) {
                            expression = rule;
                        }
                        if ((invFields == null || invFields.isEmpty()) && expression != null && !expression.isBlank()) {
                            invFields = extractExpressionFieldCandidates(expression);
                        }
                    }

                    invariants.add(new InvariantAst(
                            iname,
                            itype,
                            invFields,
                            expression,
                            invariantSpecializes,
                            invariantOverride
                    ));
                }
            }

            List<IndexAst> indexes = new ArrayList<>();
            JsonNode indexesNode = ent.get("indexes");
            if (indexesNode != null) {
                if (!indexesNode.isArray()) {
                    throw new IOException("Concept " + name + " indexes must be an array");
                }
                for (JsonNode idx : indexesNode) {
                    List<String> indexFields = parseTextArray(idx.get("fields"));
                    if (indexFields == null || indexFields.isEmpty()) {
                        throw new IOException("Concept " + name + " index must declare a non-empty 'fields' array");
                    }
                    String indexName = readText(idx, "name");
                    boolean indexUnique = idx.has("unique") && idx.get("unique").asBoolean(false);
                    indexes.add(new IndexAst(indexName, indexFields, indexUnique));
                }
            }

            ConceptAccessAst access = null;
            JsonNode accessNode = ent.get("access");
            if (accessNode != null) {
                if (!accessNode.isObject()) {
                    throw new IOException("Concept " + name + " access must be an object");
                }
                String accessRead = readText(accessNode, "read");
                String accessWrite = readText(accessNode, "write");
                access = new ConceptAccessAst(accessRead, accessWrite);
            }

            JsonNode conceptEventsNode = ent.get("events");
            if (conceptEventsNode != null) {
                if (!conceptEventsNode.isArray()) {
                    throw new IOException("Concept " + name + " events must be an array");
                }
                for (JsonNode ev : conceptEventsNode) {
                    String eventName = requiredText(ev, "name");
                    List<EventPayloadAst> payload = parseEventPayload(ev.get("payload"));
                    String eventSpecializes = readText(ev, "specializes");
                    String eventVersion = readText(ev, "version");
                    String eventTriggerMode = readText(ev, "mode");
                    if (eventTriggerMode != null && !eventTriggerMode.isBlank()
                            && !List.of("create", "update", "delete").contains(eventTriggerMode.trim().toLowerCase(Locale.ROOT))) {
                        throw new IOException("Concept " + name + " event " + eventName
                                + " has invalid mode \"" + eventTriggerMode + "\" (must be create|update|delete)");
                    }
                    EventAst eventAst = new EventAst(eventName, name, eventSpecializes, eventVersion, payload, eventTriggerMode);
                    conceptEvents.add(eventAst);
                    events.add(eventAst);
                }
            }

            TruthLevel truthLevel = TruthLevel.fromStringOrDefault(readText(ent, "truthLevel"));
            String module = readText(ent, "module");
            String conceptRenamedFrom = readText(ent, "renamedFrom");
            String conceptSatelliteOf = readText(ent, "satelliteOf");
            boolean conceptSoftDelete = ent.has("softDelete") && ent.get("softDelete").asBoolean(false);
            ConceptAst concept = new ConceptAst(name, extendsName, specializesName, fields, invariants, conceptEvents, lifecycle, conceptUi, truthLevel, module, indexes, access, conceptRenamedFrom, conceptSatelliteOf, originFor(originByQualifiedMemberName, "concepts", name), conceptSoftDelete);
            concepts.add(concept);
            conceptsByLowerName.put(name.toLowerCase(Locale.ROOT), concept);
        }

        parseCapabilitiesArray(root.get("capabilities"), "capabilities", capabilities, conceptsByLowerName, originByQualifiedMemberName);
        parseCapabilitiesArray(root.get("customCapabilities"), "customCapabilities", capabilities, conceptsByLowerName, originByQualifiedMemberName);

        JsonNode eventsNode = root.get("events");
        if (eventsNode != null) {
            if (!eventsNode.isArray()) {
                throw new IOException("events must be an array");
            }
            for (JsonNode ev : eventsNode) {
                String name = requiredText(ev, "name");
                List<EventPayloadAst> payload = parseEventPayload(ev.get("payload"));
                String specializes = readText(ev, "specializes");
                String eventVersion = readText(ev, "version");
                // mode only means something on a concept-nested event (it tells generated CRUD
                // which mutation step to publish from); a top-level event has no concept to bind
                // that to. Reject it explicitly instead of silently dropping it -- the
                // concept-nested loop above validates the same field, so a top-level declaration
                // that happens to include it is far more likely an authoring mistake (declared in
                // the wrong place) than an intentional no-op.
                String topLevelMode = readText(ev, "mode");
                if (topLevelMode != null && !topLevelMode.isBlank()) {
                    throw new IOException("Top-level event " + name
                            + " declares \"mode\", which only applies to a concept-nested event "
                            + "(move it under that concept's \"events\" array).");
                }
                events.add(new EventAst(name, null, specializes, eventVersion, payload, null,
                        originFor(originByQualifiedMemberName, "events", name)));
            }
        }

        JsonNode bindingsNode = root.get("bindings");
        if (bindingsNode != null) {
            if (!bindingsNode.isArray()) {
                throw new IOException("bindings must be an array");
            }
            for (JsonNode binding : bindingsNode) {
                String capability = requiredText(binding, "capability");
                String adapter = requiredText(binding, "adapter");
                bindings.add(new CapabilityBindingAst(capability, adapter));
            }
        }

        JsonNode flowsNode = root.get("flows");

        if (flowsNode != null) {
            if (!flowsNode.isArray()) {
                throw new IOException("flows must be an array");
            }
            for (JsonNode flowNode : flowsNode) {
                String flowName = requiredText(flowNode, "name");
                String concept = readText(flowNode, "concept");
                String specializes = readText(flowNode, "specializes");
                String mode = null;
                JsonNode inputNode = flowNode.get("input");
                if (inputNode != null && inputNode.isObject()) {
                    concept = firstNonBlank(concept, readText(inputNode, "concept"));
                    mode = readText(inputNode, "mode");
                }
                if ((concept == null || concept.isBlank())
                        && (specializes == null || specializes.isBlank())) {
                    throw new IOException("Flow " + flowName + " concept is required (flow.concept or flow.input.concept)");
                }
                JsonNode stepsNode = flowNode.get("steps");
                List<StepAst> steps = List.of();
                if (stepsNode != null) {
                    if (!stepsNode.isArray()) {
                        throw new IOException("Flow " + flowName + " steps must be an array");
                    }
                    steps = parseStepList(flowName, stepsNode);
                }
                List<FlowHookAst> hooks = parseFlowHooks(flowName, flowNode.get("hooks"));
                if (steps.isEmpty() && hooks.isEmpty() && (specializes == null || specializes.isBlank())) {
                    throw new IOException("Flow " + flowName + " steps must be an array");
                }
                SchemaAst inputSchema = parseSchema(flowNode.get("inputSchema"), "flows[" + flowName + "].inputSchema");
                SchemaAst outputSchema = parseSchema(flowNode.get("outputSchema"), "flows[" + flowName + "].outputSchema");
                ActionMetadataAst action = parseActionMetadata(flowNode.get("action"), "flows[" + flowName + "].action");
                Boolean startEndpoint = readOptionalBoolean(flowNode, "startEndpoint");
                FlowScheduleAst schedule = parseFlowSchedule(flowNode.get("schedule"), flowName);
                flows.add(new FlowAst(
                        flowName,
                        concept,
                        mode,
                        specializes,
                        hooks,
                        steps,
                        inputSchema,
                        outputSchema,
                        action,
                        Boolean.TRUE.equals(startEndpoint),
                        schedule,
                        originFor(originByQualifiedMemberName, "flows", flowName)
                ));
            }
        }

        JsonNode orchestrationRulesNode = root.get("orchestrations");
        JsonNode legacyOrchestrationRulesNode = root.get("orchestrationRules");
        String orchestrationFieldName = "orchestrations";
        if (orchestrationRulesNode != null && legacyOrchestrationRulesNode != null) {
            throw new IOException("Model must not declare both orchestrations and legacy orchestrationRules");
        }
        if (orchestrationRulesNode == null) {
            orchestrationRulesNode = legacyOrchestrationRulesNode;
            orchestrationFieldName = "orchestrationRules";
        }
        if (orchestrationRulesNode != null) {
            if (!orchestrationRulesNode.isArray()) {
                throw new IOException(orchestrationFieldName + " must be an array");
            }
            int index = 0;
            for (JsonNode ruleNode : orchestrationRulesNode) {
                index++;
                if (ruleNode == null || !ruleNode.isObject()) {
                    throw new IOException(orchestrationFieldName + "[" + index + "] must be an object");
                }
                String name = requiredText(ruleNode, "name");
                String condition = readText(ruleNode, "condition");
                JsonNode triggerNode = ruleNode.get("trigger");
                if (triggerNode == null || !triggerNode.isObject()) {
                    throw new IOException(orchestrationFieldName + "[" + name + "].trigger must be an object");
                }
                String triggerType = requiredText(triggerNode, "type");
                String triggerEvent = requiredText(triggerNode, "event");

                JsonNode actionNode = ruleNode.get("action");
                JsonNode actionsNode = ruleNode.get("actions");
                if (actionNode == null && actionsNode == null) {
                    throw new IOException(orchestrationFieldName + "[" + name + "] must define 'action' or 'actions'");
                }
                List<OrchestrationActionAst> actions = new ArrayList<>();
                if (actionNode != null) {
                    if (!actionNode.isObject()) {
                        throw new IOException(orchestrationFieldName + "[" + name + "].action must be an object");
                    }
                    actions.add(parseOrchestrationAction(actionNode, orchestrationFieldName + "[" + name + "].action"));
                }
                if (actionsNode != null) {
                    if (!actionsNode.isArray()) {
                        throw new IOException(orchestrationFieldName + "[" + name + "].actions must be an array");
                    }
                    int actionIndex = 0;
                    for (JsonNode listedActionNode : actionsNode) {
                        actionIndex++;
                        if (listedActionNode == null || !listedActionNode.isObject()) {
                            throw new IOException(orchestrationFieldName + "[" + name + "].actions[" + actionIndex
                                    + "] must be an object");
                        }
                        actions.add(parseOrchestrationAction(
                                listedActionNode,
                                orchestrationFieldName + "[" + name + "].actions[" + actionIndex + "]"
                        ));
                    }
                }
                OrchestrationActionAst primaryAction = actions.isEmpty() ? null : actions.get(0);

                orchestrationRules.add(new OrchestrationAst(
                        name,
                        condition,
                        new OrchestrationTriggerAst(triggerType, triggerEvent),
                        primaryAction,
                        actions
                ));
            }
        }

        queries.addAll(parseQueries(root.get("queries"), originByQualifiedMemberName.getOrDefault("queries", Map.of())));
        ruleProfiles.addAll(parseRuleProfiles(root.get("ruleProfiles")));
        procedures.addAll(parseProcedures(root.get("procedures")));
        panels.addAll(parsePanels(root.get("panels"), originByQualifiedMemberName.getOrDefault("panels", Map.of())));
        guidePages.addAll(parseGuidePages(root.get("guidePages")));
        aggregates.addAll(parseAggregates(root.get("aggregates")));
        autoPanels.addAll(parseAutoPanels(root.get("autoPanels")));
        selectors.addAll(parseSelectors(root.get("selectors")));
        documents.addAll(parseDocuments(root.get("documents")));
        ExternalAiAst externalAi = parseExternalAi(root.get("externalAi"));
        SettingsAst settings = parseSettings(root.get("settings"));
        roles.addAll(parseRoles(root.get("roles"), originByQualifiedMemberName.getOrDefault("roles", Map.of())));
        propertyScopes.addAll(parsePropertyScopes(root.get("propertyScopes")));
        properties.addAll(parseProperties(root.get("properties")));
        contexts.addAll(parseContexts(root.get("contexts")));
        List<com.npdev.dsl.v1.ast.ConversionAst> conversions = parseConversions(root.get("conversions"));
        webhooks.addAll(parseWebhooks(root.get("webhooks")));
        List<com.npdev.dsl.v1.ast.SequenceAst> sequences = parseSequences(root.get("sequences"));
        List<com.npdev.dsl.v1.ast.SeedAst> seeds = parseSeeds(root.get("seeds"));

        return new ModelAst(
                namespace,
                dslVersion,
                version,
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
                selectors,
                documents,
                parserWarnings,
                externalAi,
                settings,
                roles,
                propertyScopes,
                properties,
                contexts,
                conversions,
                physicalQualifierByConceptName,
                webhooks,
                sequences,
                seeds
        );
    }

    /** B20 (S2): parses the optional top-level {@code contexts} array -- {@code name} + {@code $ref}.
     *  By the time this runs, {@code root} is the fully RESOLVED JSON ({@link ModelSourceResolver}
     *  has already composed each context fragment's content into {@code concepts}/{@code queries}/
     *  {@code panels}/{@code flows} with {@code contextName::Member} qualification, exactly parallel
     *  to how a pack import qualifies its members) -- this array is metadata (which contexts exist)
     *  surviving into the AST, not something this parser resolves further. */
    private static List<com.npdev.dsl.v1.ast.ContextAst> parseContexts(JsonNode node) throws IOException {
        List<com.npdev.dsl.v1.ast.ContextAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("contexts must be an array");
        }
        for (JsonNode contextNode : node) {
            out.add(new com.npdev.dsl.v1.ast.ContextAst(
                    requiredText(contextNode, "name"),
                    requiredText(contextNode, "$ref"),
                    contextNode.has("physicallyIsolate") && contextNode.get("physicallyIsolate").asBoolean(false)
            ));
        }
        return out;
    }

    /** S7 Phase B (B13): parses the optional top-level {@code conversions} array -- see
     *  {@link com.npdev.dsl.v1.ast.ConversionAst}'s own javadoc. Structural parsing only; the
     *  compiler resolves {@code concept}/field references against the real model graph. */
    private static List<com.npdev.dsl.v1.ast.ConversionAst> parseConversions(JsonNode node) throws IOException {
        List<com.npdev.dsl.v1.ast.ConversionAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("conversions must be an array");
        }
        for (JsonNode conversionNode : node) {
            String id = requiredText(conversionNode, "id");
            List<com.npdev.dsl.v1.ast.ConversionSplitTargetAst> into = new ArrayList<>();
            JsonNode intoNode = conversionNode.get("into");
            if (intoNode != null && !intoNode.isNull()) {
                if (!intoNode.isArray()) {
                    throw new IOException("conversions[" + id + "].into must be an array");
                }
                for (JsonNode targetNode : intoNode) {
                    into.add(new com.npdev.dsl.v1.ast.ConversionSplitTargetAst(
                            requiredText(targetNode, "field"),
                            requiredText(targetNode, "take")
                    ));
                }
            }
            JsonNode matchNode = conversionNode.get("match");
            com.npdev.dsl.v1.ast.ConversionLookupMatchAst match = null;
            if (matchNode != null && !matchNode.isNull()) {
                match = new com.npdev.dsl.v1.ast.ConversionLookupMatchAst(
                        requiredText(matchNode, "concept"),
                        requiredText(matchNode, "on"),
                        requiredText(matchNode, "equals")
                );
            }
            // S8 W1.2 (roadmap deferred item #4): "from" is a plain string for copy/split/convert but
            // an ARRAY of field names for merge (GroupByJoinGrammar-style overload of one JSON key by
            // shape, not a second differently-named property) -- see ConversionAst's own javadoc.
            String from = null;
            List<String> mergeFrom = new ArrayList<>();
            JsonNode fromNode = conversionNode.get("from");
            if (fromNode != null && !fromNode.isNull()) {
                if (fromNode.isArray()) {
                    for (JsonNode fromElement : fromNode) {
                        mergeFrom.add(fromElement.asText());
                    }
                } else {
                    from = readText(conversionNode, "from");
                }
            }
            // "with" is a separator LITERAL (e.g. a single space) -- readText()'s isBlank() collapse
            // (correct for every other field here, which must be non-blank identifiers) would wrongly
            // drop a legitimate " " or "" separator, so read it raw instead.
            JsonNode withNode = conversionNode.get("with");
            String with = (withNode != null && !withNode.isNull()) ? withNode.asText() : null;
            out.add(new com.npdev.dsl.v1.ast.ConversionAst(
                    id,
                    requiredText(conversionNode, "concept"),
                    requiredText(conversionNode, "op"),
                    from,
                    readText(conversionNode, "to"),
                    into,
                    match,
                    readText(conversionNode, "set"),
                    mergeFrom,
                    with
            ));
        }
        return out;
    }

    /** Wave 3 (RC-B1): parses the optional top-level {@code roles} array -- {@code name} +
     *  {@code grants} (a list of platform permission names, checked structurally here only; see
     *  {@link com.npdev.dsl.v1.ast.RoleAst}'s own javadoc for why the DSL module cannot validate
     *  against the real Permission enum). */
    private static List<com.npdev.dsl.v1.ast.RoleAst> parseRoles(
            JsonNode node,
            Map<String, ModelSourceResolver.PackOrigin> originByName
    ) throws IOException {
        List<com.npdev.dsl.v1.ast.RoleAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("roles must be an array");
        }
        for (JsonNode roleNode : node) {
            String name = requiredText(roleNode, "name");
            out.add(new com.npdev.dsl.v1.ast.RoleAst(
                    name,
                    parseTextArray(roleNode.get("grants")),
                    toOriginAst(originByName.get(name))
            ));
        }
        return out;
    }

    /** R6.2: parses the optional top-level {@code webhooks} array -- {@code source} +
     *  {@code hmacSecretEnvVar} + {@code eventName} + optional {@code fieldMapping}. No pack-origin
     *  lookup (unlike {@code roles}/{@code events}/...): a webhook's identity ({@code source}) is a
     *  wire path segment, deliberately never namespace-qualified by pack composition -- see
     *  {@link com.npdev.dsl.v1.ast.WebhookAst}'s own javadoc. */
    private static List<com.npdev.dsl.v1.ast.WebhookAst> parseWebhooks(JsonNode node) throws IOException {
        List<com.npdev.dsl.v1.ast.WebhookAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("webhooks must be an array");
        }
        for (JsonNode webhookNode : node) {
            out.add(new com.npdev.dsl.v1.ast.WebhookAst(
                    requiredText(webhookNode, "source"),
                    requiredText(webhookNode, "hmacSecretEnvVar"),
                    requiredText(webhookNode, "eventName"),
                    parseStringMap(webhookNode.get("fieldMapping"))
            ));
        }
        return out;
    }

    /** R5.3: parses the optional top-level {@code sequences} array -- {@code name} + {@code format}
     *  + optional {@code scope}. No pack-origin lookup (same reasoning as webhooks above): a
     *  sequence's identity ({@code name}) is referenced as an opaque literal argument to {@code
     *  nextNumber('name')} inside another field's defaultExpression TEXT, deliberately never
     *  namespace-qualified by pack composition -- see {@link com.npdev.dsl.v1.ast.SequenceAst}'s
     *  own javadoc. */
    private static List<com.npdev.dsl.v1.ast.SequenceAst> parseSequences(JsonNode node) throws IOException {
        List<com.npdev.dsl.v1.ast.SequenceAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("sequences must be an array");
        }
        for (JsonNode sequenceNode : node) {
            out.add(new com.npdev.dsl.v1.ast.SequenceAst(
                    requiredText(sequenceNode, "name"),
                    requiredText(sequenceNode, "format"),
                    readText(sequenceNode, "scope")
            ));
        }
        return out;
    }

    /** R8.8: parses the optional top-level {@code seeds} array -- {@code concept} + optional
     *  {@code alias}/{@code id}/{@code data}/{@code repeatOver}/{@code count}, the EXISTING
     *  app-level seed record shape ({@code NPDevContract/schemas/seed.schema.json}'s {@code
     *  $defs/record}). No pack-origin lookup here (unlike {@code roles}/{@code events}/...): a
     *  seed record has no {@code name} to key an origin lookup by, and its {@code concept} field
     *  was already rewritten to pack-qualified form (when pack/context-declared) upstream by
     *  {@code ModelSourceResolver.mergeQualifiedNonConceptArrays}'s own "seeds" branch, which also
     *  enforces that a pack/context may only seed a concept it owns. Declaration order is
     *  preserved -- see {@link com.npdev.dsl.v1.ast.SeedAst}'s own javadoc for why. */
    private static List<com.npdev.dsl.v1.ast.SeedAst> parseSeeds(JsonNode node) throws IOException {
        List<com.npdev.dsl.v1.ast.SeedAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("seeds must be an array");
        }
        for (JsonNode seedNode : node) {
            out.add(new com.npdev.dsl.v1.ast.SeedAst(
                    requiredText(seedNode, "concept"),
                    readText(seedNode, "alias"),
                    readText(seedNode, "id"),
                    parseObjectMap(seedNode.get("data")),
                    parseSeedRepeatOverVars(seedNode.get("repeatOver")),
                    seedNode.has("count") && !seedNode.get("count").isNull() ? seedNode.get("count").asInt() : null
            ));
        }
        return out;
    }

    /** R8.8: parses {@code seeds[].repeatOver.vars} -- an object of {@code [min, max]} inclusive
     *  integer pairs, the same bulk-generation shape {@code SeedDataService.expandSmartRecord}
     *  already reads from the app-level convention's JSON directly. */
    private static Map<String, List<Integer>> parseSeedRepeatOverVars(JsonNode repeatOverNode) throws IOException {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        if (repeatOverNode == null || repeatOverNode.isNull()) {
            return out;
        }
        JsonNode varsNode = repeatOverNode.get("vars");
        if (varsNode == null || !varsNode.isObject()) {
            return out;
        }
        Iterator<String> varNames = varsNode.fieldNames();
        while (varNames.hasNext()) {
            String varName = varNames.next();
            JsonNode range = varsNode.get(varName);
            if (range == null || !range.isArray() || range.size() != 2) {
                throw new IOException("seeds[].repeatOver.vars." + varName + " must be a [min, max] pair");
            }
            out.put(varName, List.of(range.get(0).asInt(), range.get(1).asInt()));
        }
        return out;
    }

    /** Wave 6 (RC-A1): parses the optional top-level {@code propertyScopes} array -- {@code name} +
     *  an optional {@code from} (blank/absent for the implicit root/tenant scope). */
    private static List<com.npdev.dsl.v1.ast.PropertyScopeAst> parsePropertyScopes(JsonNode node) throws IOException {
        List<com.npdev.dsl.v1.ast.PropertyScopeAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("propertyScopes must be an array");
        }
        for (JsonNode scopeNode : node) {
            out.add(new com.npdev.dsl.v1.ast.PropertyScopeAst(
                    requiredText(scopeNode, "name"),
                    readText(scopeNode, "from")
            ));
        }
        return out;
    }

    /** Wave 6 (RC-A1): parses the optional top-level {@code properties} array -- {@code name}/
     *  {@code type}/{@code default}/{@code settableAt}/{@code label}/{@code securityRelevant}. */
    private static List<com.npdev.dsl.v1.ast.PropertyAst> parseProperties(JsonNode node) throws IOException {
        List<com.npdev.dsl.v1.ast.PropertyAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("properties must be an array");
        }
        for (JsonNode propertyNode : node) {
            out.add(new com.npdev.dsl.v1.ast.PropertyAst(
                    requiredText(propertyNode, "name"),
                    requiredText(propertyNode, "type"),
                    parseJsonValue(propertyNode.get("default")),
                    parseTextArray(propertyNode.get("settableAt")),
                    readLabelText(propertyNode, "label"),
                    readBooleanFlag(propertyNode, "securityRelevant"),
                    readLabelLocales(propertyNode, "label")
            ));
        }
        return out;
    }

    /** ADR-0009: parses the optional app-level externalAi block; null if the model declares none. */
    private static ExternalAiAst parseExternalAi(JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException("externalAi must be an object");
        }
        String egress = readText(node, "egress");
        List<String> vendors = parseTextArray(node.get("vendors"));
        return new ExternalAiAst(egress, vendors);
    }

    /** Move 6 Move A: parses the optional app-level settings block; null if the model declares none. */
    private static SettingsAst parseSettings(JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException("settings must be an object");
        }
        String locale = readText(node, "locale");
        Map<String, String> strings = new LinkedHashMap<>();
        JsonNode stringsNode = node.get("strings");
        if (stringsNode != null && stringsNode.isObject()) {
            Iterator<String> keys = stringsNode.fieldNames();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = readText(stringsNode, key);
                if (value != null) {
                    strings.put(key, value);
                }
            }
        }
        Integer pageRows = null;
        String dateFormat = null;
        JsonNode uiNode = node.get("ui");
        if (uiNode != null && uiNode.isObject()) {
            JsonNode pageRowsNode = uiNode.get("pageRows");
            if (pageRowsNode != null && pageRowsNode.isNumber()) {
                pageRows = pageRowsNode.asInt();
            }
            dateFormat = readText(uiNode, "dateFormat");
        }
        return new SettingsAst(locale, strings, pageRows, dateFormat);
    }

    private static List<QueryAst> parseQueries(
            JsonNode node,
            Map<String, ModelSourceResolver.PackOrigin> originByName
    ) throws IOException {
        List<QueryAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("queries must be an array");
        }
        for (JsonNode queryNode : node) {
            String name = requiredText(queryNode, "name");
            out.add(new QueryAst(
                    name,
                    requiredText(queryNode, "concept"),
                    readText(queryNode, "where"),
                    parseTextArray(queryNode.get("orderBy")),
                    readOptionalInt(queryNode, "limit"),
                    parseProcedureParameters(queryNode.get("parameters"), "queries[" + name + "].parameters"),
                    parseTextArray(queryNode.get("permissionRequirements")),
                    readText(queryNode, "tracePolicy"),
                    parseObjectMap(queryNode.get("metadata")),
                    parseGroupByFields(queryNode.get("groupBy")),
                    parseAggregateFunctions(queryNode.get("aggregates")),
                    readText(queryNode, "having"),
                    toOriginAst(originByName.get(name))
            ));
        }
        return out;
    }

    /** Move 10 B1: query.groupBy[] accepts either a bare field-name string or
     *  {"field", "bucket"} for date/datetime bucketing -- normalized to one AST shape here. */
    private static List<com.npdev.dsl.v1.ast.GroupByFieldAst> parseGroupByFields(JsonNode node) throws IOException {
        List<com.npdev.dsl.v1.ast.GroupByFieldAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("query.groupBy must be an array");
        }
        for (JsonNode entry : node) {
            if (entry.isTextual()) {
                out.add(new com.npdev.dsl.v1.ast.GroupByFieldAst(entry.asText(), null));
            } else if (entry.isObject()) {
                out.add(new com.npdev.dsl.v1.ast.GroupByFieldAst(
                        requiredText(entry, "field"), readText(entry, "bucket")));
            } else {
                throw new IOException("query.groupBy entries must be a string or {field, bucket} object");
            }
        }
        return out;
    }

    /** Move 10 B1: query.aggregates[] -- {name, fn, field?}. */
    private static List<com.npdev.dsl.v1.ast.AggregateFunctionAst> parseAggregateFunctions(JsonNode node)
            throws IOException {
        List<com.npdev.dsl.v1.ast.AggregateFunctionAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("query.aggregates must be an array");
        }
        for (JsonNode entry : node) {
            out.add(new com.npdev.dsl.v1.ast.AggregateFunctionAst(
                    requiredText(entry, "name"), requiredText(entry, "fn"), readText(entry, "field")));
        }
        return out;
    }

    private static List<RuleProfileAst> parseRuleProfiles(JsonNode node) throws IOException {
        List<RuleProfileAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("ruleProfiles must be an array");
        }
        for (JsonNode profileNode : node) {
            String name = requiredText(profileNode, "name");
            Boolean enabled = readOptionalBoolean(profileNode, "enabled");
            out.add(new RuleProfileAst(
                    name,
                    readText(profileNode, "description"),
                    parseTextArray(profileNode.get("appliesTo")),
                    enabled == null || enabled,
                    parseObjectMap(profileNode.get("metadata"))
            ));
        }
        return out;
    }

    private static List<ProcedureAst> parseProcedures(JsonNode node) throws IOException {
        List<ProcedureAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("procedures must be an array");
        }
        for (JsonNode procedureNode : node) {
            String name = requiredText(procedureNode, "name");
            List<ProcedureVariableAst> variables = new ArrayList<>();
            variables.addAll(parseProcedureVariables(procedureNode.get("locals"), "procedures[" + name + "].locals"));
            variables.addAll(parseProcedureVariables(procedureNode.get("variables"), "procedures[" + name + "].variables"));
            out.add(new ProcedureAst(
                    name,
                    readText(procedureNode, "description"),
                    parseProcedureParameters(procedureNode.get("parameters"), "procedures[" + name + "].parameters"),
                    variables,
                    parseProcedureSteps(procedureNode.get("steps"), "procedures[" + name + "].steps", true),
                    parseSchema(procedureNode.get("returns"), "procedures[" + name + "].returns"),
                    parseTextArray(procedureNode.get("permissionRequirements")),
                    readText(procedureNode, "tracePolicy"),
                    parseGeneratedActionDescriptor(procedureNode.get("actionDescriptor"), "procedures[" + name + "].actionDescriptor"),
                    parseObjectMap(procedureNode.get("metadata"))
            ));
        }
        return out;
    }

    private static GeneratedActionDescriptorAst parseGeneratedActionDescriptor(JsonNode node, String fieldPath)
            throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException(fieldPath + " must be an object");
        }
        return new GeneratedActionDescriptorAst(
                readText(node, "actionName"),
                parseTextArray(node.get("affectedConcepts")),
                readText(node, "sideEffectConcept"),
                readText(node, "eventNameOnSuccess"),
                readText(node, "auditResourceType"),
                readText(node, "idempotencyPolicy"),
                readText(node, "tracePolicy"),
                readText(node, "correlationPolicy")
        );
    }

    private static List<ProcedureParameterAst> parseProcedureParameters(JsonNode node, String fieldPath)
            throws IOException {
        List<ProcedureParameterAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException(fieldPath + " must be an array");
        }
        for (JsonNode parameterNode : node) {
            String name = requiredText(parameterNode, "name");
            Boolean required = readOptionalBoolean(parameterNode, "required");
            out.add(new ProcedureParameterAst(
                    name,
                    requiredText(parameterNode, "type"),
                    required != null && required,
                    parseSchema(parameterNode.get("schema"), fieldPath + "[" + name + "].schema"),
                    readText(parameterNode, "description")
            ));
        }
        return out;
    }

    private static List<ProcedureVariableAst> parseProcedureVariables(JsonNode node, String fieldPath)
            throws IOException {
        List<ProcedureVariableAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException(fieldPath + " must be an array");
        }
        for (JsonNode variableNode : node) {
            String name = requiredText(variableNode, "name");
            out.add(new ProcedureVariableAst(
                    name,
                    readText(variableNode, "type"),
                    parseSchema(variableNode.get("schema"), fieldPath + "[" + name + "].schema"),
                    parseJsonValue(variableNode.get("initialValue"))
            ));
        }
        return out;
    }

    private static List<ProcedureStepAst> parseProcedureSteps(JsonNode node, String fieldPath, boolean required)
            throws IOException {
        List<ProcedureStepAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            if (required) {
                throw new IOException(fieldPath + " must be an array");
            }
            return out;
        }
        if (!node.isArray()) {
            throw new IOException(fieldPath + " must be an array");
        }
        for (JsonNode stepNode : node) {
            String type = requiredText(stepNode, "type");
            String name = readText(stepNode, "name");
            out.add(new ProcedureStepAst(
                    name,
                    type,
                    readText(stepNode, "target"),
                    parseJsonValue(stepNode.get("value")),
                    readText(stepNode, "condition"),
                    readText(stepNode, "items"),
                    readText(stepNode, "as"),
                    readText(stepNode, "concept"),
                    readText(stepNode, "query"),
                    parseObjectMap(stepNode.get("data")),
                    readText(stepNode, "id"),
                    readText(stepNode, "procedure"),
                    readText(stepNode, "flow"),
                    readText(stepNode, "capability"),
                    readText(stepNode, "operation"),
                    readText(stepNode, "event"),
                    parseObjectMap(stepNode.get("args")),
                    parseProcedureSteps(stepNode.get("then"), fieldPath + "[" + (name == null ? type : name) + "].then", false),
                    parseProcedureSteps(stepNode.get("else"), fieldPath + "[" + (name == null ? type : name) + "].else", false),
                    parseProcedureSteps(stepNode.get("steps"), fieldPath + "[" + (name == null ? type : name) + "].steps", false),
                    readOptionalBoolean(stepNode, "trace"),
                    readOptionalBoolean(stepNode, "audit"),
                    parseObjectMap(stepNode.get("metadata")),
                    parseObjectMap(stepNode.get("set")),
                    readOptionalBoolean(stepNode, "createIfMissing"),
                    parseObjectMap(stepNode.get("select")),
                    parseJsonValue(stepNode.get("left")),
                    parseJsonValue(stepNode.get("right"))
            ));
        }
        return out;
    }

    private static List<PanelAst> parsePanels(
            JsonNode node,
            Map<String, ModelSourceResolver.PackOrigin> originByName
    ) throws IOException {
        List<PanelAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("panels must be an array");
        }
        for (JsonNode panelNode : node) {
            String name = requiredText(panelNode, "name");
            out.add(new PanelAst(
                    name,
                    requiredText(panelNode, "route"),
                    readText(panelNode, "title"),
                    parsePanelDataSources(panelNode.get("dataSources"), "panels[" + name + "].dataSources"),
                    parsePanelLayout(panelNode.get("layout"), "panels[" + name + "].layout"),
                    parsePanelFieldBindings(panelNode.get("fieldBindings"), "panels[" + name + "].fieldBindings"),
                    readText(panelNode, "visibility"),
                    readText(panelNode, "enabledWhen"),
                    parsePanelActions(panelNode.get("actions"), "panels[" + name + "].actions"),
                    parseObjectMap(panelNode.get("explainability")),
                    parseObjectMap(panelNode.get("metadata")),
                    readText(panelNode, "guidePage"),
                    toOriginAst(originByName.get(name))
            ));
        }
        return out;
    }

    private static List<DocumentAst> parseDocuments(JsonNode node) throws IOException {
        List<DocumentAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("documents must be an array");
        }
        for (JsonNode documentNode : node) {
            String name = requiredText(documentNode, "name");
            out.add(new DocumentAst(
                    name,
                    requiredText(documentNode, "concept"),
                    readText(documentNode, "title"),
                    readText(documentNode, "pageSize"),
                    readOptionalDouble(documentNode, "marginMm"),
                    parseObjectMap(documentNode.get("metadata")),
                    readText(documentNode, "aggregate"),
                    parseDocumentBands(documentNode.get("bands"), "documents[" + name + "].bands"),
                    parseDocumentLogo(documentNode.get("logo"), "documents[" + name + "].logo")
            ));
        }
        return out;
    }

    /**
     * R5.7: a document band's {@code fields} reuses {@link #parsePanelFieldBindings} verbatim -- the
     * same bare property-name-plus-{@code ui} shape a panel's {@code fieldBindings} already parses,
     * per R5.7's "reuse the existing panel-binding shapes" mandate.
     */
    private static List<DocumentBandAst> parseDocumentBands(JsonNode node, String fieldPath) throws IOException {
        List<DocumentBandAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException(fieldPath + " must be an array");
        }
        for (JsonNode bandNode : node) {
            String name = requiredText(bandNode, "name");
            out.add(new DocumentBandAst(
                    name,
                    requiredText(bandNode, "kind"),
                    readText(bandNode, "collection"),
                    readLabelText(bandNode, "label"),
                    readLabelLocales(bandNode, "label"),
                    parsePanelFieldBindings(bandNode.get("fields"), fieldPath + "[" + name + "].fields")
            ));
        }
        return out;
    }

    /**
     * R5.7: {@code logo.field} names a property on the document's bound concept -- never a URL, so
     * there is nothing here for a malicious model to point at an internal host. See
     * {@link DocumentLogoAst}'s javadoc.
     */
    private static DocumentLogoAst parseDocumentLogo(JsonNode node, String fieldPath) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException(fieldPath + " must be an object");
        }
        return new DocumentLogoAst(requiredText(node, "field"));
    }

    private static List<AggregateAst> parseAggregates(JsonNode node) throws IOException {
        List<AggregateAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("aggregates must be an array");
        }
        for (JsonNode aggregateNode : node) {
            String name = requiredText(aggregateNode, "name");
            out.add(new AggregateAst(
                    name,
                    requiredText(aggregateNode, "root"),
                    parseAggregateCollections(aggregateNode.get("collections"),
                            "aggregates[" + name + "].collections"),
                    readText(aggregateNode, "onCommit"),
                    parseObjectMap(aggregateNode.get("metadata")),
                    readText(aggregateNode, "onValidate"),
                    // R4.4/npdev-aggregate-invariant-four-place: parser -> compiler -> canonical
                    // writer+reader, mirroring ast-compiled-four-place's chain for a per-member field.
                    parseAggregateInvariants(aggregateNode.get("invariants"),
                            "aggregates[" + name + "].invariants")
            ));
        }
        return out;
    }

    /** R4.4: aggregates[].invariants[] -- {name, expression, message?}, evaluated against the
     *  whole aggregate draft tree pre-commit. See AggregateInvariantAst's javadoc. */
    private static List<AggregateInvariantAst> parseAggregateInvariants(JsonNode node, String path)
            throws IOException {
        List<AggregateInvariantAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException(path + " must be an array");
        }
        for (JsonNode invariantNode : node) {
            out.add(new AggregateInvariantAst(
                    requiredText(invariantNode, "name"),
                    requiredText(invariantNode, "expression"),
                    readText(invariantNode, "message")
            ));
        }
        return out;
    }

    private static List<AggregateCollectionAst> parseAggregateCollections(JsonNode node, String path)
            throws IOException {
        List<AggregateCollectionAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException(path + " must be an array");
        }
        for (JsonNode collectionNode : node) {
            String name = requiredText(collectionNode, "name");
            out.add(new AggregateCollectionAst(
                    name,
                    requiredText(collectionNode, "concept"),
                    readText(collectionNode, "via"),
                    requiredText(collectionNode, "childField"),
                    readText(collectionNode, "ownership"),
                    readText(collectionNode, "orderBy"),
                    parseAggregateCollections(collectionNode.get("collections"),
                            path + "[" + name + "].collections"),
                    parseObjectMap(collectionNode.get("metadata"))
            ));
        }
        return out;
    }

    private static List<AutoPanelAst> parseAutoPanels(JsonNode node) throws IOException {
        List<AutoPanelAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("autoPanels must be an array");
        }
        for (JsonNode autoPanelNode : node) {
            out.add(new AutoPanelAst(
                    readText(autoPanelNode, "name"),
                    readText(autoPanelNode, "concept"),
                    readText(autoPanelNode, "aggregate"),
                    readText(autoPanelNode, "route"),
                    parseTextArray(autoPanelNode.get("surfaces")),
                    parseAutoPanelSurface(autoPanelNode.get("selection")),
                    parseAutoPanelSurface(autoPanelNode.get("detail")),
                    parseAutoPanelSurface(autoPanelNode.get("transaction")),
                    parseAutoPanelSurface(autoPanelNode.get("prompt")),
                    parseObjectMap(autoPanelNode.get("metadata"))
            ));
        }
        return out;
    }

    private static List<SelectorAst> parseSelectors(JsonNode node) throws IOException {
        List<SelectorAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("selectors must be an array");
        }
        for (JsonNode selectorNode : node) {
            Boolean multiSelect = readOptionalBoolean(selectorNode, "multiSelect");
            out.add(new SelectorAst(
                    requiredText(selectorNode, "name"),
                    requiredText(selectorNode, "concept"),
                    multiSelect != null && multiSelect,
                    parseTextArray(selectorNode.get("filters")),
                    parseTextArray(selectorNode.get("columns")),
                    parseObjectMap(selectorNode.get("returnMapping")),
                    parseObjectMap(selectorNode.get("metadata"))
            ));
        }
        return out;
    }

    private static AutoPanelSurfaceAst parseAutoPanelSurface(JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        return new AutoPanelSurfaceAst(
                parseTextArray(node.get("filters")),
                parseTextArray(node.get("columns")),
                parseTextArray(node.get("fields")),
                parseAutoPanelComputed(node.get("computed")),
                readText(node, "labelField"),
                parseObjectMap(node.get("metadata")),
                parseTransactionHooks(node.get("hooks")),
                parseDerivedFields(node.get("derivedFields")),
                parseRegions(node.get("regions")),
                parseWorkbenchActions(node.get("actions")),
                parseVisibleWhen(node.get("visibleWhen")),
                parseBandPickers(node.get("bandPickers")),
                parseAutoPanelDataSource(node.get("dataSource")),
                parseUiState(node.get("uiState"))
        );
    }

    /** Move 11 W6: parses transaction.uiState, an object keyed by UI-state name. */
    private static Map<String, UiStateControlAst> parseUiState(JsonNode node) throws IOException {
        Map<String, UiStateControlAst> out = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isObject()) {
            throw new IOException("transaction uiState must be an object keyed by UI-state name");
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode stateNode = node.get(name);
            out.put(name, new UiStateControlAst(
                    name,
                    readLabelText(stateNode, "label"),
                    parseTextArray(stateNode.get("values")),
                    readText(stateNode, "default"),
                    readLabelLocales(stateNode, "label")
            ));
        }
        return out;
    }

    /**
     * Move 8 D3 (item G6): parses a surface's {@code dataSource.procedure} -- the typed hook that
     * replaces the generated row source with a procedure's output instead of the bound concept's
     * table (the {@code produce} disposition {@code PanelRuntime} already executes).
     */
    private static AutoPanelDataSourceAst parseAutoPanelDataSource(JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException("autoPanel surface dataSource must be an object");
        }
        return new AutoPanelDataSourceAst(readText(node, "procedure"));
    }

    /** Move 7 W1: parses transaction.actions, typed replacement for metadata.actions. */
    private static List<WorkbenchActionAst> parseWorkbenchActions(JsonNode node) throws IOException {
        List<WorkbenchActionAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("transaction actions must be an array");
        }
        for (JsonNode actionNode : node) {
            out.add(new WorkbenchActionAst(
                    requiredText(actionNode, "procedure"),
                    readLabelText(actionNode, "label"),
                    parseTextArray(actionNode.get("inputFields")),
                    parseWorkbenchActionApplyTo(actionNode.get("applyTo")),
                    readText(actionNode, "afterAction"),
                    readText(actionNode, "visibleWhen"),
                    readLabelLocales(actionNode, "label")
            ));
        }
        return out;
    }

    private static WorkbenchActionApplyToAst parseWorkbenchActionApplyTo(JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException("transaction actions[].applyTo must be an object");
        }
        return new WorkbenchActionApplyToAst(
                requiredText(node, "collection"),
                requiredText(node, "mode"),
                parseStringMap(node.get("map"))
        );
    }

    /** Move 7 W1: parses transaction.visibleWhen, typed replacement for metadata.visibleWhen. */
    private static Map<String, String> parseVisibleWhen(JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new IOException("transaction visibleWhen must be an object keyed by collection/band name");
        }
        return parseStringMap(node);
    }

    /** Move 7 W1: parses transaction.bandPickers, typed replacement for metadata.bandPickers. */
    private static Map<String, WorkbenchBandPickerAst> parseBandPickers(JsonNode node) throws IOException {
        Map<String, WorkbenchBandPickerAst> out = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isObject()) {
            throw new IOException("transaction bandPickers must be an object keyed by band collection name");
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode pickerNode = node.get(name);
            String panel = readText(pickerNode, "panel");
            String filter = readText(pickerNode, "filter");
            boolean multiSelect = pickerNode.has("multiSelect") && pickerNode.get("multiSelect").asBoolean(false);
            // B16/B19 (Move 9 A3): filter/multiSelect are the same two properties a plain FK field's
            // picker declares -- structurally optional here; PanelValidation.validateBandPickers is
            // the single source of truth for "panel is still required" (not relaxed in this pass).
            out.put(name, new WorkbenchBandPickerAst(
                    panel,
                    readLabelText(pickerNode, "label"),
                    parseTextArray(pickerNode.get("columns")),
                    filter,
                    multiSelect,
                    readLabelLocales(pickerNode, "label")
            ));
        }
        return out;
    }


    /** Move 6 Move D: parses transaction.regions, an object keyed by derived region address. */
    private static Map<String, RegionMountAst> parseRegions(JsonNode node) throws IOException {
        Map<String, RegionMountAst> out = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isObject()) {
            throw new IOException("transaction regions must be an object keyed by region address");
        }
        Iterator<String> addresses = node.fieldNames();
        while (addresses.hasNext()) {
            String address = addresses.next();
            JsonNode regionNode = node.get(address);
            out.put(address, new RegionMountAst(readText(regionNode, "render"), readText(regionNode, "component")));
        }
        return out;
    }

    /** Move 6 Move B: parses the optional closed-enum transaction.hooks block; null if absent. */
    private static TransactionHooksAst parseTransactionHooks(JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException("transaction hooks must be an object");
        }
        return new TransactionHooksAst(
                readText(node, "onLoad"),
                readText(node, "onFieldChange"),
                readText(node, "beforeAction"),
                readText(node, "onValidate"),
                readText(node, "onCommit")
        );
    }

    /** Move 6 Move B: parses transaction.derivedFields, an object keyed by field name. */
    private static List<DerivedFieldAst> parseDerivedFields(JsonNode node) throws IOException {
        List<DerivedFieldAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isObject()) {
            throw new IOException("transaction derivedFields must be an object keyed by field name");
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode fieldNode = node.get(name);
            out.add(new DerivedFieldAst(
                    name,
                    readLabelText(fieldNode, "label"),
                    readText(fieldNode, "tier"),
                    readText(fieldNode, "expression"),
                    readText(fieldNode, "procedure"),
                    readLabelLocales(fieldNode, "label")
            ));
        }
        return out;
    }

    private static List<AutoPanelComputedAst> parseAutoPanelComputed(JsonNode node) throws IOException {
        List<AutoPanelComputedAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("autoPanel surface computed must be an array");
        }
        for (JsonNode computedNode : node) {
            out.add(new AutoPanelComputedAst(
                    requiredText(computedNode, "col"),
                    requiredText(computedNode, "expr")
            ));
        }
        return out;
    }

    private static List<GuidePageAst> parseGuidePages(JsonNode node) throws IOException {
        List<GuidePageAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("guidePages must be an array");
        }
        for (JsonNode pageNode : node) {
            out.add(new GuidePageAst(
                    requiredText(pageNode, "name"),
                    readOptionalBoolean(pageNode, "default") != null && readOptionalBoolean(pageNode, "default"),
                    parseGuidePageRegions(pageNode.get("regions")),
                    parseGuidePageTheme(pageNode.get("theme")),
                    parseGuidePageGadgets(pageNode.get("gadgets"))
            ));
        }
        return out;
    }

    private static GuidePageRegionsAst parseGuidePageRegions(JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException("guidePages[].regions must be an object");
        }
        Boolean top = readOptionalBoolean(node, "top");
        return new GuidePageRegionsAst(
                top == null || top,
                parseGuidePageRegion(node.get("left")),
                parseGuidePageRegion(node.get("right"))
        );
    }

    private static GuidePageRegionAst parseGuidePageRegion(JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException("guidePages[].regions.left/right must be an object");
        }
        Boolean enabled = readOptionalBoolean(node, "enabled");
        Boolean collapsible = readOptionalBoolean(node, "collapsible");
        Boolean defaultCollapsed = readOptionalBoolean(node, "defaultCollapsed");
        JsonNode widthNode = node.get("width");
        int width = widthNode != null && widthNode.isNumber() ? widthNode.asInt() : 0;
        return new GuidePageRegionAst(
                enabled == null || enabled,
                collapsible != null && collapsible,
                defaultCollapsed != null && defaultCollapsed,
                width
        );
    }

    private static GuidePageThemeAst parseGuidePageTheme(JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException("guidePages[].theme must be an object");
        }
        return new GuidePageThemeAst(
                readText(node, "mode"),
                readText(node, "accent"),
                readText(node, "density"),
                readText(node, "logoText"),
                readText(node, "logoUrl")
        );
    }

    private static List<GuidePageGadgetAst> parseGuidePageGadgets(JsonNode node) throws IOException {
        List<GuidePageGadgetAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("guidePages[].gadgets must be an array");
        }
        for (JsonNode gadgetNode : node) {
            out.add(new GuidePageGadgetAst(
                    requiredText(gadgetNode, "name"),
                    requiredText(gadgetNode, "type"),
                    readText(gadgetNode, "title"),
                    readText(gadgetNode, "query"),
                    readText(gadgetNode, "x"),
                    readText(gadgetNode, "y"),
                    readText(gadgetNode, "series")
            ));
        }
        return out;
    }

    private static List<PanelDataSourceAst> parsePanelDataSources(JsonNode node, String fieldPath) throws IOException {
        List<PanelDataSourceAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException(fieldPath + " must be an array");
        }
        for (JsonNode dataSourceNode : node) {
            out.add(new PanelDataSourceAst(
                    requiredText(dataSourceNode, "name"),
                    readText(dataSourceNode, "concept"),
                    readText(dataSourceNode, "query"),
                    readText(dataSourceNode, "procedure"),
                    parseObjectMap(dataSourceNode.get("params")),
                    readText(dataSourceNode, "parentDataSource"),
                    readText(dataSourceNode, "parentField"),
                    readText(dataSourceNode, "childField"),
                    parseTextArray(dataSourceNode.get("rowOps")),
                    parseTextArray(dataSourceNode.get("addFormFields")),
                    readText(dataSourceNode, "onRowLoad")
            ));
        }
        return out;
    }

    private static PanelLayoutAst parsePanelLayout(JsonNode node, String fieldPath) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException(fieldPath + " must be an object");
        }
        List<PanelLayoutAst> children = new ArrayList<>();
        JsonNode childrenNode = node.get("children");
        if (childrenNode != null && !childrenNode.isNull()) {
            if (!childrenNode.isArray()) {
                throw new IOException(fieldPath + ".children must be an array");
            }
            int index = 0;
            for (JsonNode childNode : childrenNode) {
                children.add(parsePanelLayout(childNode, fieldPath + ".children[" + index + "]"));
                index++;
            }
        }
        return new PanelLayoutAst(
                requiredText(node, "type"),
                children,
                parseTextArray(node.get("fields")),
                parseObjectMap(node.get("metadata"))
        );
    }

    private static List<PanelFieldBindingAst> parsePanelFieldBindings(JsonNode node, String fieldPath) throws IOException {
        List<PanelFieldBindingAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException(fieldPath + " must be an array");
        }
        for (JsonNode bindingNode : node) {
            String field = requiredText(bindingNode, "field");
            out.add(new PanelFieldBindingAst(
                    field,
                    readText(bindingNode, "source"),
                    readText(bindingNode, "visibleWhen"),
                    readText(bindingNode, "enabledWhen"),
                    readText(bindingNode, "readonlyWhen"),
                    parsePresentationMetadata(bindingNode.get("ui"), fieldPath + "[" + field + "].ui"),
                    readBooleanFlag(bindingNode, "editable")
            ));
        }
        return out;
    }

    private static List<PanelActionAst> parsePanelActions(JsonNode node, String fieldPath) throws IOException {
        List<PanelActionAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException(fieldPath + " must be an array");
        }
        for (JsonNode actionNode : node) {
            String name = requiredText(actionNode, "name");
            out.add(new PanelActionAst(
                    name,
                    readLabelText(actionNode, "label"),
                    requiredText(actionNode, "binding"),
                    readText(actionNode, "concept"),
                    readText(actionNode, "operation"),
                    readText(actionNode, "procedure"),
                    readText(actionNode, "flow"),
                    readText(actionNode, "visibleWhen"),
                    readText(actionNode, "enabledWhen"),
                    parseTextArray(actionNode.get("permissionRequirements")),
                    parseObjectMap(actionNode.get("explainability")),
                    parseObjectMap(actionNode.get("metadata")),
                    readText(actionNode, "scope"),
                    readText(actionNode, "dataSource"),
                    parseTextArray(actionNode.get("inputFields")),
                    readText(actionNode, "resultAs"),
                    readText(actionNode, "filename"),
                    readText(actionNode, "contentType"),
                    readLabelLocales(actionNode, "label")
            ));
        }
        return out;
    }

    private static Map<String, Object> parseObjectMap(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node == null || node.isNull() || !node.isObject()) {
            return out;
        }
        Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String key = fieldNames.next();
            out.put(key, parseJsonValue(node.get(key)));
        }
        return out;
    }

    private static String readText(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null) return null;
        String s = v.asText();
        if (s == null || s.isBlank()) return null;
        return s;
    }

    /**
     * R5.6: reads a label site's resolved default text. Every label site accepts the widened
     * shape {@code $defs/localizableLabel} -- a plain string (unchanged, {@link #readText} on it
     * directly would already work) OR an object {@code {"default": "...", "<locale>": "...", ...}}.
     * This is the ONE extra layer of indirection: for the object form it reads "default", the
     * schema-required terminal fallback; for the plain-string form it behaves exactly like
     * {@link #readText}. Always pair with {@link #readLabelLocales} at the same call site -- the
     * text is only half of a label site's authored value.
     */
    private static String readLabelText(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) return null;
        if (v.isObject()) {
            return readText(v, "default");
        }
        String s = v.asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * R5.6: reads a label site's per-locale overrides (empty when authored as a plain string, or
     * when the object form declares only "default"). Keys are whatever locale tags the author
     * wrote (e.g. "en", "pt-BR") -- not validated against a fixed locale list, matching this
     * codebase's general "informational tag, not a closed set" treatment of locale strings
     * elsewhere (see {@code SettingsAst.locale}). Insertion order is preserved (LinkedHashMap) so
     * canonical-JSON output is a function of parse order, not hash order.
     */
    private static Map<String, String> readLabelLocales(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull() || !v.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        Iterator<String> names = v.fieldNames();
        while (names.hasNext()) {
            String locale = names.next();
            if ("default".equals(locale)) {
                continue;
            }
            String value = readText(v, locale);
            if (value != null) {
                out.put(locale, value);
            }
        }
        return out;
    }

    private static String requiredText(JsonNode node, String key) throws IOException {
        String value = readText(node, key);
        if (value == null) {
            throw new IOException("Missing/blank required field: " + key);
        }
        return value;
    }

    private static boolean readBooleanFlag(JsonNode node, String key) {
        if (node == null || !node.isObject()) {
            return false;
        }
        JsonNode value = node.get(key);
        if (value == null || value.isNull() || !value.isBoolean()) {
            return false;
        }
        return value.asBoolean(false);
    }

    private static Boolean readOptionalBoolean(JsonNode node, String key) throws IOException {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isBoolean()) {
            throw new IOException("Field '" + key + "' must be boolean when provided");
        }
        return value.asBoolean();
    }

    private static Integer readOptionalInt(JsonNode node, String key) throws IOException {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToInt()) {
            throw new IOException("Field '" + key + "' must be integer when provided");
        }
        return value.intValue();
    }

    private static Long readOptionalLong(JsonNode node, String key) throws IOException {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToLong()) {
            throw new IOException("Field '" + key + "' must be long when provided");
        }
        return value.longValue();
    }

    /** LIFT-UPLOAD-P2: parses a field's `file: {contentTypes, maxSizeBytes, multiple}` block. */
    private static FileMetadataAst parseFileMetadata(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        List<String> contentTypes = parseTextArray(node.get("contentTypes"));
        JsonNode maxSizeNode = node.get("maxSizeBytes");
        Long maxSizeBytes = maxSizeNode != null && maxSizeNode.isNumber() ? maxSizeNode.asLong() : null;
        boolean multiple = node.has("multiple") && node.get("multiple").asBoolean(false);
        return new FileMetadataAst(contentTypes, maxSizeBytes, multiple);
    }

    /** B16/B19 (Move 9 A3): parses a field's `picker: {filter, multiSelect}` block. */
    private static FieldPickerAst parseFieldPicker(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String filter = readText(node, "filter");
        boolean multiSelect = node.has("multiSelect") && node.get("multiSelect").asBoolean(false);
        return new FieldPickerAst(filter, multiSelect);
    }

    /** R5.5: parses a field's `access: {read, write}` block -- same shape/grammar as a concept's
     *  own {@code access}, one rung down the ladder. */
    private static FieldAccessAst parseFieldAccess(JsonNode node, String path) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException(path + " must be an object");
        }
        return new FieldAccessAst(readText(node, "read"), readText(node, "write"));
    }

    /**
     * LNCH-12: {@code schedule.tenantScope} accepts either a single tenant id string or an array
     * of them, mirroring how many other fields in this DSL accept a "one or many" shorthand.
     */
    private static FlowScheduleAst parseFlowSchedule(JsonNode scheduleNode, String flowName) throws IOException {
        if (scheduleNode == null || scheduleNode.isNull()) {
            return null;
        }
        if (!scheduleNode.isObject()) {
            throw new IOException("Flow " + flowName + " schedule must be an object");
        }
        String cron = readText(scheduleNode, "cron");
        if (cron == null || cron.isBlank()) {
            throw new IOException("Flow " + flowName + " schedule.cron is required");
        }
        JsonNode tenantScopeNode = scheduleNode.get("tenantScope");
        List<String> tenantScope;
        if (tenantScopeNode == null || tenantScopeNode.isNull()) {
            tenantScope = List.of();
        } else if (tenantScopeNode.isTextual()) {
            tenantScope = List.of(tenantScopeNode.asText());
        } else if (tenantScopeNode.isArray()) {
            tenantScope = parseTextArray(tenantScopeNode);
        } else {
            throw new IOException("Flow " + flowName + " schedule.tenantScope must be a string or array of strings");
        }
        return new FlowScheduleAst(cron.trim(), tenantScope);
    }

    private static List<String> parseTextArray(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isNull()) return out;
        if (!node.isArray()) return out;

        Iterator<JsonNode> it = node.elements();
        while (it.hasNext()) {
            out.add(it.next().asText());
        }
        return out;
    }

    private static List<EnumOptionAst> parseEnumOptions(JsonNode node, String fieldPath) throws IOException {
        List<EnumOptionAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException(fieldPath + " must be an array");
        }
        int index = 0;
        for (JsonNode item : node) {
            index++;
            if (item == null || item.isNull()) {
                continue;
            }
            if (item.isTextual()) {
                out.add(new EnumOptionAst(item.asText(), null, null, null, false, false, null, null, null));
                continue;
            }
            if (!item.isObject()) {
                throw new IOException(fieldPath + "[" + index + "] must be string or object");
            }
            String value = requiredText(item, "value");
            String label = firstNonBlank(readLabelText(item, "label"), readLabelText(item, "displayLabel"));
            Map<String, String> labelLocales = !readLabelLocales(item, "label").isEmpty()
                    ? readLabelLocales(item, "label")
                    : readLabelLocales(item, "displayLabel");
            Integer order = readOptionalInt(item, "order");
            String group = readText(item, "group");
            boolean defaultValue = item.has("default") && item.get("default").asBoolean(false);
            boolean deprecated = item.has("deprecated") && item.get("deprecated").asBoolean(false);
            String iconHint = firstNonBlank(readText(item, "icon"), readText(item, "iconHint"));
            String badgeHint = firstNonBlank(readText(item, "badge"), readText(item, "badgeHint"));
            String description = firstNonBlank(readText(item, "description"), readText(item, "help"));
            out.add(new EnumOptionAst(
                    value,
                    label,
                    order,
                    group,
                    defaultValue,
                    deprecated,
                    iconHint,
                    badgeHint,
                    description,
                    labelLocales
            ));
        }
        return out;
    }

    private static ReferenceSemanticsAst parseReferenceSemantics(JsonNode node, String fieldPath) throws IOException {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return new ReferenceSemanticsAst(node.asText(), false, null, List.of(), List.of(), null, null, List.of(), null, null);
        }
        if (!node.isObject()) {
            throw new IOException(fieldPath + " must be a string or object");
        }
        String target = requiredText(node, "target");
        boolean multiple = node.has("multiple") && node.get("multiple").asBoolean(false);
        String displayField = readText(node, "displayField");
        List<String> searchFields = parseTextArray(node.get("searchFields"));
        if (searchFields.isEmpty()) {
            searchFields = parseTextArray(node.get("lookupFields"));
        }
        List<String> previewFields = parseTextArray(node.get("previewFields"));
        String inlineCreatePolicy = readText(node, "inlineCreate");
        String displayTemplate = readText(node, "displayTemplate");
        List<String> pickerColumns = parseTextArray(node.get("pickerColumns"));
        String previewCardTemplate = readText(node, "previewCardTemplate");
        String defaultFilter = firstNonBlank(readText(node, "defaultFilter"), readText(node, "defaultFilterBehavior"));
        String via = readText(node, "via");
        String onDelete = readText(node, "onDelete");
        return new ReferenceSemanticsAst(
                target,
                multiple,
                displayField,
                searchFields,
                previewFields,
                inlineCreatePolicy,
                displayTemplate,
                pickerColumns,
                previewCardTemplate,
                defaultFilter,
                via,
                onDelete
        );
    }

    private static List<StepAst> parseStepList(String flowName, JsonNode stepsNode) throws IOException {
        List<StepAst> steps = new ArrayList<>();
        int index = 0;
        for (JsonNode stepNode : stepsNode) {
            index++;
            steps.add(parseStep(flowName, stepNode, index));
        }
        return steps;
    }

    private static List<FlowHookAst> parseFlowHooks(String flowName, JsonNode hooksNode) throws IOException {
        if (hooksNode == null || hooksNode.isNull()) {
            return List.of();
        }
        if (!hooksNode.isArray()) {
            throw new IOException("Flow " + flowName + " hooks must be an array");
        }
        List<FlowHookAst> hooks = new ArrayList<>();
        int index = 0;
        for (JsonNode hookNode : hooksNode) {
            index++;
            if (hookNode == null || !hookNode.isObject()) {
                throw new IOException("Flow " + flowName + " hook #" + index + " must be an object");
            }
            String position = firstNonBlank(readText(hookNode, "position"), readText(hookNode, "at"));
            String targetStep = firstNonBlank(readText(hookNode, "targetStep"), readText(hookNode, "target"));
            JsonNode hookStepsNode = hookNode.get("steps");
            if (hookStepsNode == null || !hookStepsNode.isArray()) {
                throw new IOException("Flow " + flowName + " hook #" + index + " must define steps array");
            }
            List<StepAst> hookSteps = parseStepList(flowName + ".hook." + index, hookStepsNode);
            hooks.add(new FlowHookAst(position, targetStep, hookSteps));
        }
        return hooks;
    }

    private static StepAst parseStep(String flowName, JsonNode stepNode, int index) throws IOException {
        String stepName = readText(stepNode, "name");
        if (stepName == null || stepName.isBlank()) {
            stepName = flowName + "-step-" + index;
        }

        String rawType = requiredText(stepNode, "type");
        String type = normalizeStepType(rawType);
        String checkpoint = firstNonBlank(readText(stepNode, "checkpoint"), readText(stepNode, "phase"));
        String scope = readText(stepNode, "scope");
        List<String> invariants = parseTextArray(stepNode.get("invariants"));
        String capability = firstNonBlank(readText(stepNode, "capability"), readText(stepNode, "cap"));
        String operation = firstNonBlank(readText(stepNode, "operation"), readText(stepNode, "op"));
        CapabilityPolicyAst capabilityPolicy = parseCapabilityPolicy(stepNode.get("policy"));
        String input = readText(stepNode, "input");
        String output = firstNonBlank(readText(stepNode, "output"), readText(stepNode, "out"));
        List<String> args = parseTextArray(stepNode.get("args"));
        String event = readText(stepNode, "event");
        String payload = firstNonBlank(readText(stepNode, "payload"), readText(stepNode, "from"));
        String generatedActionName = readText(stepNode, "actionName");
        Map<String, String> data = parseStringMap(stepNode.get("data"));
        String condition = readText(stepNode, "condition");
        String awaitEvent = "await".equals(type)
                ? firstNonBlank(readText(stepNode, "awaitEvent"), readText(stepNode, "event"))
                : readText(stepNode, "awaitEvent");
        String awaitRef = firstNonBlank(readText(stepNode, "awaitRef"), readText(stepNode, "as"));
        JsonNode awaitMatch = stepNode.get("match");
        Boolean awaitMatchCorrelation = readOptionalBoolean(awaitMatch, "correlation");
        Map<String, String> awaitPayloadMatch = awaitMatch == null
                ? Map.of()
                : parseStringMap(awaitMatch.get("payload"));
        Long delaySeconds = readOptionalLong(stepNode, "delaySeconds");
        if (delaySeconds == null) {
            Long delayMinutes = readOptionalLong(stepNode, "delayMinutes");
            if (delayMinutes != null) {
                delaySeconds = delayMinutes * 60L;
            }
        }
        if (delaySeconds == null) {
            Long delayMs = readOptionalLong(stepNode, "delayMs");
            if (delayMs != null) {
                delaySeconds = Math.max(0L, (delayMs + 999L) / 1000L);
            }
        }
        String returnValue = readText(stepNode, "value");
        ActionMetadataAst action = parseActionMetadata(stepNode.get("action"), "flows[" + flowName + "].steps[" + stepName + "].action");
        // Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1A): callProcedure invokes a named
        // procedure synchronously from a flow, using the same input/output convention every other
        // flow step already uses -- no new ref-shaped properties needed beyond the procedure name.
        String procedure = readText(stepNode, "procedure");

        List<StepAst> thenSteps = List.of();
        List<StepAst> elseSteps = List.of();
        JsonNode thenNode = stepNode.get("then");
        if (thenNode != null && thenNode.isArray()) {
            thenSteps = parseStepList(flowName + "." + stepName + ".then", thenNode);
        }
        JsonNode elseNode = stepNode.get("else");
        if (elseNode != null && elseNode.isArray()) {
            elseSteps = parseStepList(flowName + "." + stepName + ".else", elseNode);
        }

        // LIFT-LOOP-P1: forEach/loop flow step -- collectionRef/itemKey/loopSteps/maxLoopIterations.
        String collectionRef = readText(stepNode, "collection");
        String itemKey = readText(stepNode, "itemKey");
        List<StepAst> loopSteps = List.of();
        JsonNode loopStepsNode = stepNode.get("steps");
        if (loopStepsNode != null && loopStepsNode.isArray()) {
            loopSteps = parseStepList(flowName + "." + stepName + ".steps", loopStepsNode);
        }
        Integer maxLoopIterations = readOptionalInt(stepNode, "maxLoopIterations");
        // B15(B) (S6, docs/BOUNDARY_LIFT_ROADMAP.md §B15(B)): opts a forEach loop body into N-way
        // parallel waiting instead of B15(A)'s default sequential behavior. null/absent means
        // sequential -- fully backward compatible.
        Boolean parallelAwait = readOptionalBoolean(stepNode, "parallelAwait");

        // LNCH-17: declared compensation steps, run in reverse completion order when a later step
        // in the same flow terminally fails.
        List<StepAst> onFailureSteps = List.of();
        JsonNode onFailureNode = stepNode.get("onFailure");
        if (onFailureNode != null && onFailureNode.isArray()) {
            onFailureSteps = parseStepList(flowName + "." + stepName + ".onFailure", onFailureNode);
        }

        // R2.5 (durable await timeouts): an awaitEvent step's optional durable wait deadline
        // (seconds from when it first parks) plus the escalation steps to run once that deadline
        // passes without the awaited event ever arriving -- same array-of-steps shape onFailure
        // already established above, deliberately not restricted to type=="await" here (mirroring
        // delaySeconds/onFailureSteps, which are also parsed unconditionally and left unused by
        // step types that don't read them; FlowValidation is where "only awaitEvent may declare
        // this" gets enforced, not the parser).
        Long timeoutSeconds = readOptionalLong(stepNode, "timeout");
        List<StepAst> onTimeoutSteps = List.of();
        JsonNode onTimeoutNode = stepNode.get("onTimeout");
        if (onTimeoutNode != null && onTimeoutNode.isArray()) {
            onTimeoutSteps = parseStepList(flowName + "." + stepName + ".onTimeout", onTimeoutNode);
        }

        if ("invariant".equals(type)) {
            if ((checkpoint == null || checkpoint.isBlank()) && scope != null && !scope.isBlank()) {
                checkpoint = "pre";
            }
        } else if ("capability".equals(type)) {
            if ((input == null || input.isBlank()) && !args.isEmpty()) {
                input = args.get(0);
            }
        } else if ("generatedAction".equals(type)) {
            if (generatedActionName == null || generatedActionName.isBlank()) {
                throw new IOException("Flow " + flowName + " step " + stepName + " actionName is required for generatedAction");
            }
            capability = "generated.action." + generatedActionName.trim();
            operation = "run";
            if ((input == null || input.isBlank()) && !args.isEmpty()) {
                input = args.get(0);
            }
        } else if ("return".equals(type)) {
            if (returnValue == null || returnValue.isBlank()) {
                returnValue = "last";
            }
        }

        return new StepAst(
                stepName,
                type,
                checkpoint,
                scope,
                invariants,
                capability,
                operation,
                capabilityPolicy,
                input,
                output,
                args,
                event,
                payload,
                data,
                condition,
                thenSteps,
                elseSteps,
                awaitEvent,
                awaitRef,
                awaitMatchCorrelation,
                awaitPayloadMatch,
                delaySeconds,
                returnValue,
                action,
                generatedActionName,
                collectionRef,
                itemKey,
                loopSteps,
                maxLoopIterations,
                onFailureSteps,
                procedure,
                parallelAwait,
                timeoutSeconds,
                onTimeoutSteps
        );
    }

    private List<CapabilityAst> parseCapabilitiesArray(
            JsonNode capabilitiesNode,
            String sourceLabel,
            List<CapabilityAst> target,
            Map<String, ConceptAst> conceptsByLowerName,
            Map<String, Map<String, ModelSourceResolver.PackOrigin>> originByQualifiedMemberName
    ) throws IOException {
        if (capabilitiesNode == null) {
            return target;
        }
        if (!capabilitiesNode.isArray()) {
            throw new IOException(sourceLabel + " must be an array");
        }
        for (JsonNode cap : capabilitiesNode) {
            String name = requiredText(cap, "name");
            String type = readText(cap, "type");
            String specializes = readText(cap, "specializes");
            List<CapabilityOperationAst> operations = new ArrayList<>();
            JsonNode opsNode = cap.get("operations");
            if (opsNode != null) {
                if (!opsNode.isArray()) {
                    throw new IOException("Capability " + name + " operations must be an array");
                }
                for (JsonNode op : opsNode) {
                    if (op.isTextual()) {
                        operations.add(new CapabilityOperationAst(op.asText(), List.of(), List.of()));
                    } else if (op.isObject()) {
                        String opName = requiredText(op, "name");
                        List<String> input = parseTextArray(op.get("input"));
                        List<String> output = parseTextArray(op.get("output"));
                        SchemaAst inputSchema = parseCapabilityOperationSchema(
                                op.get("input"),
                                sourceLabel + "[" + name + "].operations[" + opName + "].input",
                                conceptsByLowerName
                        );
                        SchemaAst outputSchema = parseCapabilityOperationSchema(
                                op.get("output"),
                                sourceLabel + "[" + name + "].operations[" + opName + "].output",
                                conceptsByLowerName
                        );
                        CapabilityPolicyAst operationPolicy = parseCapabilityPolicy(op.get("policy"));
                        operations.add(new CapabilityOperationAst(
                                opName,
                                input,
                                output,
                                inputSchema,
                                outputSchema,
                                operationPolicy
                        ));
                    } else {
                        throw new IOException("Capability " + name + " operation must be a string or object");
                    }
                }
            }
            target.add(new CapabilityAst(name, type, specializes, operations,
                    originFor(originByQualifiedMemberName, sourceLabel, name)));
        }
        return target;
    }

    private static CapabilityPolicyAst parseCapabilityPolicy(JsonNode policyNode) throws IOException {
        if (policyNode == null || policyNode.isNull()) {
            return null;
        }
        if (!policyNode.isObject()) {
            throw new IOException("Step field 'policy' must be an object when provided");
        }
        Integer retryCount = readOptionalInt(policyNode, "retryCount");
        Long retryDelayMs = readOptionalLong(policyNode, "retryDelayMs");
        Long timeoutMs = readOptionalLong(policyNode, "timeoutMs");
        Integer circuitOpenAfterFailures = readOptionalInt(policyNode, "circuitOpenAfterFailures");
        Long circuitOpenMs = readOptionalLong(policyNode, "circuitOpenMs");
        Integer bulkheadMaxConcurrent = readOptionalInt(policyNode, "bulkheadMaxConcurrent");
        String idempotencyKeyField = firstNonBlank(
                readText(policyNode, "idempotencyKeyField"),
                readText(policyNode, "idempotencyKey")
        );
        String failureClassification = readText(policyNode, "failureClassification");
        return new CapabilityPolicyAst(
                retryCount,
                retryDelayMs,
                timeoutMs,
                circuitOpenAfterFailures,
                circuitOpenMs,
                bulkheadMaxConcurrent,
                idempotencyKeyField,
                failureClassification
        );
    }

    private static SchemaAst parseCapabilityOperationSchema(
            JsonNode schemaNode,
            String fieldPath,
            Map<String, ConceptAst> conceptsByLowerName
    ) throws IOException {
        if (schemaNode == null || schemaNode.isNull()) {
            return null;
        }
        if (!schemaNode.isObject()) {
            throw new IOException(fieldPath + " must be a schema object or concept/schema ref object");
        }

        String conceptRef = readText(schemaNode, "conceptRef");
        String schemaRef = readText(schemaNode, "schemaRef");
        JsonNode nestedSchemaNode = schemaNode.get("schema");

        if (nestedSchemaNode != null && !nestedSchemaNode.isNull()) {
            return parseSchema(nestedSchemaNode, fieldPath + ".schema");
        }
        if (conceptRef != null && !conceptRef.isBlank()) {
            return schemaFromConceptRef(conceptRef, conceptsByLowerName, fieldPath);
        }
        if (schemaRef != null && !schemaRef.isBlank()) {
            String resolvedConceptRef = resolveConceptRefFromSchemaRef(schemaRef);
            return schemaFromConceptRef(resolvedConceptRef, conceptsByLowerName, fieldPath);
        }
        return parseSchema(schemaNode, fieldPath);
    }

    private static SchemaAst schemaFromConceptRef(
            String conceptRef,
            Map<String, ConceptAst> conceptsByLowerName,
            String fieldPath
    ) throws IOException {
        ConceptAst concept = conceptsByLowerName.get(conceptRef.toLowerCase(Locale.ROOT));
        if (concept == null) {
            throw new IOException(fieldPath + " references unknown concept/schema: " + conceptRef);
        }
        Map<String, SchemaAst> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (FieldAst field : concept.getFields()) {
            properties.put(
                    field.getName(),
                    new SchemaAst(
                            toJsonSchemaType(field.getType()),
                            Map.of(),
                            List.of(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null
                    )
            );
            if (field.isRequired()) {
                required.add(field.getName());
            }
        }
        required.sort(String.CASE_INSENSITIVE_ORDER);
        return new SchemaAst("object", properties, required, null, null, null, null, null, null);
    }

    private static String resolveConceptRefFromSchemaRef(String schemaRef) {
        String trimmed = schemaRef.trim();
        if (trimmed.startsWith("#/concepts/")) {
            return trimmed.substring("#/concepts/".length());
        }
        return trimmed;
    }

    private static String toJsonSchemaType(String dslType) {
        if (dslType == null || dslType.isBlank()) {
            return null;
        }
        return switch (dslType.trim().toLowerCase(Locale.ROOT)) {
            case "string", "uuid", "enum", "date", "datetime", "reference" -> "string";
            case "int", "integer", "long" -> "integer";
            case "decimal" -> "number";
            case "boolean" -> "boolean";
            case "object" -> "object";
            case "array" -> "array";
            default -> "string";
        };
    }

    private static Map<String, String> parseStringMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return Map.of();
        }

        Map<String, String> out = new LinkedHashMap<>();
        Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String key = fieldNames.next();
            JsonNode valueNode = node.get(key);
            if (valueNode != null && !valueNode.isNull()) {
                out.put(key, valueNode.asText());
            }
        }
        return out;
    }

    private static List<EventPayloadAst> parseEventPayload(JsonNode node) throws IOException {
        List<EventPayloadAst> out = new ArrayList<>();
        if (node == null || node.isNull()) return out;
        if (!node.isArray()) {
            throw new IOException("event payload must be an array");
        }

        Iterator<JsonNode> it = node.elements();
        while (it.hasNext()) {
            JsonNode item = it.next();
            if (item.isTextual()) {
                out.add(new EventPayloadAst(item.asText(), "string"));
            } else if (item.isObject()) {
                String name = requiredText(item, "name");
                String type = firstNonBlank(readText(item, "type"), "string");
                out.add(new EventPayloadAst(name, type));
            } else {
                throw new IOException("event payload item must be string or object");
            }
        }
        return out;
    }

    private static OrchestrationActionAst parseOrchestrationAction(JsonNode actionNode, String fieldPath)
            throws IOException {
        if (actionNode == null || !actionNode.isObject()) {
            throw new IOException(fieldPath + " must be an object");
        }
        String actionType = requiredText(actionNode, "type");
        String actionConcept = firstNonBlank(
                readText(actionNode, "concept"),
                readText(actionNode, "targetConcept")
        );
        String actionCapability = firstNonBlank(
                readText(actionNode, "capability"),
                readText(actionNode, "capabilityName")
        );
        String actionOperation = firstNonBlank(
                readText(actionNode, "operation"),
                readText(actionNode, "op")
        );
        String actionEvent = firstNonBlank(
                readText(actionNode, "event"),
                readText(actionNode, "eventName")
        );
        Long delaySeconds = readOptionalLong(actionNode, "delaySeconds");
        if (delaySeconds == null) {
            Long delayMinutes = readOptionalLong(actionNode, "delayMinutes");
            if (delayMinutes != null) {
                delaySeconds = delayMinutes * 60L;
            }
        }
        if (delaySeconds == null) {
            Long delayMs = readOptionalLong(actionNode, "delayMs");
            if (delayMs != null) {
                delaySeconds = Math.max(0L, (delayMs + 999L) / 1000L);
            }
        }
        JsonNode mapNode = actionNode.get("map") != null
                ? actionNode.get("map")
                : actionNode.get("fieldMap");
        Map<String, String> fieldMap = parseStringMap(mapNode);
        ActionMetadataAst action = parseActionMetadata(actionNode.get("action"), fieldPath + ".action");
        return new OrchestrationActionAst(
                actionType,
                actionConcept,
                actionCapability,
                actionOperation,
                actionEvent,
                delaySeconds,
                fieldMap,
                action
        );
    }

    private static ActionMetadataAst parseActionMetadata(JsonNode node, String fieldPath) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException(fieldPath + " must be an object");
        }
        return new ActionMetadataAst(
                readLabelText(node, "label"),
                readText(node, "confirmationText"),
                readText(node, "successMessage"),
                readText(node, "failureHint"),
                readText(node, "dangerLevel"),
                readText(node, "visibleWhen"),
                readText(node, "permissionHint"),
                readText(node, "inputFormHint"),
                readLabelLocales(node, "label")
        );
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    private static DomainTypeUiAst parseDomainTypeUi(JsonNode node, String fieldPath) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException(fieldPath + " must be an object");
        }
        return new DomainTypeUiAst(
                readLabelText(node, "label"),
                readText(node, "placeholder"),
                readText(node, "helpText"),
                readText(node, "widget"),
                readLabelLocales(node, "label")
        );
    }

    private static PresentationMetadataAst parsePresentationMetadata(JsonNode node, String fieldPath) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException(fieldPath + " must be an object");
        }
        return new PresentationMetadataAst(
                readLabelText(node, "label"),
                readLabelText(node, "shortLabel"),
                readText(node, "description"),
                readText(node, "helpText"),
                readText(node, "placeholder"),
                readText(node, "group"),
                readText(node, "section"),
                readOptionalInt(node, "order"),
                readOptionalBoolean(node, "advanced"),
                readOptionalBoolean(node, "deprecated"),
                parseTextArray(node.get("examples")),
                readText(node, "widget"),
                readText(node, "visibleWhen"),
                readText(node, "enabledWhen"),
                readText(node, "readonlyWhen"),
                readText(node, "requiredWhen"),
                readText(node, "pickerType"),
                readOptionalBoolean(node, "allowInlineCreate"),
                parseTextArray(node.get("searchFields")),
                readText(node, "filterPreset"),
                readText(node, "tab"),
                readOptionalInt(node, "column"),
                readOptionalInt(node, "columnSpan"),
                readText(node, "width"),
                readOptionalBoolean(node, "summaryCard"),
                readOptionalBoolean(node, "listColumn"),
                readOptionalBoolean(node, "showInDefaultWebUi"),
                readOptionalInt(node, "listColumnOrder"),
                readOptionalInt(node, "formColumns"),
                readText(node, "displayMode"),
                readText(node, "formPresentation"),
                readText(node, "defaultSort"),
                readText(node, "defaultGroup"),
                readText(node, "imageField"),
                readText(node, "customWidgetRef"),
                readLabelLocales(node, "label"),
                readLabelLocales(node, "shortLabel")
        );
    }

    private static SchemaAst parseSchema(JsonNode node, String fieldPath) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException(fieldPath + " must be an object");
        }

        String type = readText(node, "type");
        String description = readText(node, "description");
        Integer minLength = readOptionalInt(node, "minLength");
        Integer maxLength = readOptionalInt(node, "maxLength");
        Integer precision = readOptionalInt(node, "precision");
        Integer scale = readOptionalInt(node, "scale");
        Integer minItems = readOptionalInt(node, "minItems");
        Integer maxItems = readOptionalInt(node, "maxItems");
        Boolean uniqueItems = readOptionalBoolean(node, "uniqueItems");
        String itemIdentityField = readText(node, "itemIdentityField");
        String duplicationPolicy = readText(node, "duplicationPolicy");
        Double min = readOptionalDouble(node, "min");
        Double max = readOptionalDouble(node, "max");
        String regex = firstNonBlank(readText(node, "regex"), readText(node, "pattern"));

        Map<String, SchemaAst> properties = new LinkedHashMap<>();
        JsonNode propertiesNode = node.get("properties");
        if (propertiesNode != null && !propertiesNode.isNull()) {
            if (!propertiesNode.isObject()) {
                throw new IOException(fieldPath + ".properties must be an object");
            }
            Iterator<String> fieldNames = propertiesNode.fieldNames();
            while (fieldNames.hasNext()) {
                String propertyName = fieldNames.next();
                SchemaAst propertySchema = parseSchema(
                        propertiesNode.get(propertyName),
                        fieldPath + ".properties." + propertyName
                );
                if (propertySchema != null) {
                    properties.put(propertyName, propertySchema);
                }
            }
        }

        List<String> required = parseTextArray(node.get("required"));
        SchemaAst items = parseSchema(node.get("items"), fieldPath + ".items");
        List<String> enumValues = parseTextArray(
                node.get("enumValues") != null
                        ? node.get("enumValues")
                        : (node.get("values") != null ? node.get("values") : node.get("enum"))
        );
        Object defaultValue = parseJsonValue(node.get("default"));
        String defaultExpression = readText(node, "defaultExpression");
        String derivedExpression = readText(node, "derivedExpression");

        if ((type == null || type.isBlank()) && !properties.isEmpty()) {
            type = "object";
        }
        if ((type == null || type.isBlank()) && items != null) {
            type = "array";
        }

        return new SchemaAst(
                type,
                properties,
                items,
                required,
                enumValues,
                defaultValue,
                defaultExpression,
                derivedExpression,
                description,
                minLength,
                maxLength,
                precision,
                scale,
                minItems,
                maxItems,
                uniqueItems,
                itemIdentityField,
                duplicationPolicy,
                min,
                max,
                regex
        );
    }

    private static LifecycleAst parseLifecycle(JsonNode node, String fieldPath) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException(fieldPath + " must be an object");
        }

        String statusField = firstNonBlank(readText(node, "statusField"), "status");
        List<StateMachineStateAst> states = parseStateMachineStates(node.get("states"), fieldPath + ".states");
        JsonNode transitionsNode = node.get("transitions");
        if (transitionsNode == null || !transitionsNode.isArray()) {
            throw new IOException(fieldPath + ".transitions must be an array");
        }
        List<StateTransitionAst> transitions = new ArrayList<>();
        int index = 0;
        for (JsonNode transitionNode : transitionsNode) {
            index++;
            if (transitionNode == null || !transitionNode.isObject()) {
                throw new IOException(fieldPath + ".transitions[" + index + "] must be an object");
            }
            String from = requiredText(transitionNode, "from");
            String to = requiredText(transitionNode, "to");
            List<String> requiredPayload = parseTextArray(transitionNode.get("requiredPayload"));
            if (requiredPayload.isEmpty()) {
                requiredPayload = parseTextArray(transitionNode.get("requires"));
            }
            String event = readText(transitionNode, "event");
            String guard = readText(transitionNode, "guard");
            String actionLabel = readLabelText(transitionNode, "actionLabel");
            Map<String, String> metadata = parseStringMap(transitionNode.get("metadata"));
            ActionMetadataAst action = parseActionMetadata(transitionNode.get("action"), fieldPath + ".transitions[" + index + "].action");
            transitions.add(new StateTransitionAst(from, to, requiredPayload, event, guard, actionLabel, metadata, action,
                    readLabelLocales(transitionNode, "actionLabel")));
        }
        return new LifecycleAst(statusField, states, transitions);
    }

    private static List<StateMachineStateAst> parseStateMachineStates(JsonNode node, String fieldPath) throws IOException {
        List<StateMachineStateAst> states = new ArrayList<>();
        if (node == null || node.isNull()) {
            return states;
        }
        if (!node.isArray()) {
            throw new IOException(fieldPath + " must be an array");
        }
        int index = 0;
        for (JsonNode stateNode : node) {
            index++;
            if (stateNode == null || stateNode.isNull()) {
                continue;
            }
            if (stateNode.isTextual()) {
                states.add(new StateMachineStateAst(stateNode.asText()));
                continue;
            }
            if (!stateNode.isObject()) {
                throw new IOException(fieldPath + "[" + index + "] must be a string or object");
            }
            String value = requiredText(stateNode, "value");
            String label = readLabelText(stateNode, "label");
            Boolean initial = readOptionalBoolean(stateNode, "initial");
            Boolean terminal = readOptionalBoolean(stateNode, "terminal");
            List<String> allowedActions = parseTextArray(stateNode.get("allowedActions"));
            Map<String, String> metadata = parseStringMap(stateNode.get("metadata"));
            states.add(new StateMachineStateAst(
                    value,
                    label,
                    initial != null && initial,
                    terminal != null && terminal,
                    allowedActions,
                    metadata,
                    readLabelLocales(stateNode, "label")
            ));
        }
        return states;
    }

    private static Object parseJsonValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonNode item : node) {
                values.add(parseJsonValue(item));
            }
            return values;
        }
        if (node.isObject()) {
            Map<String, Object> values = new LinkedHashMap<>();
            Iterator<String> fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                String key = fieldNames.next();
                values.put(key, parseJsonValue(node.get(key)));
            }
            return values;
        }
        return node.toString();
    }

    private static Double readOptionalDouble(JsonNode node, String key) throws IOException {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw new IOException("Field '" + key + "' must be numeric when provided");
        }
        return value.doubleValue();
    }

    /**
     * DSL 2.0 (docs/DSL2_AND_DECOMPOSITION_PLAN.md 2.A.4): a 1:1 map from the 12 canonical
     * {@code flowStep.type} spellings to their AST-level string. The 23-spelling alias table this
     * replaced is gone -- {@code model.schema.json}'s narrowed {@code type} enum is now the single
     * place a non-canonical spelling is refused, with a diagnostic naming the canonical replacement
     * (REG-51's "refuse, don't silently accept" precedent). Schema validation
     * ({@code JsonModelSchemaValidator}) always runs before this method is ever called, so
     * {@code normalized} is already guaranteed to be one of the 12 keys below; {@code default}
     * exists only as a defensive fallback for that unreachable case, not as tolerance for anything
     * unrecognized.
     */
    private static String normalizeStepType(String type) {
        if (type == null) return null;
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "invariantcheck" -> "invariant";
            case "capabilitycall" -> "capability";
            case "generatedaction" -> "generatedAction";
            case "createconcept" -> "createConcept";
            case "updateconcept" -> "updateConcept";
            case "emitevent" -> "event";
            case "scheduleevent" -> "scheduleEvent";
            case "branch" -> "branch";
            case "map" -> "map";
            case "awaitevent" -> "await";
            case "return" -> "return";
            case "foreach" -> "forEach";
            case "callprocedure" -> "callProcedure";
            default -> type;
        };
    }

    private static ParsedRule parseInvariantRule(String rule) {
        String trimmed = rule.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("unique(") || !trimmed.endsWith(")")) {
            return new ParsedRule("expression", extractExpressionFieldCandidates(trimmed), trimmed);
        }
        String inner = trimmed.substring(trimmed.indexOf('(') + 1, trimmed.length() - 1).trim();
        if (inner.isBlank()) {
            return new ParsedRule("unique", List.of(), null);
        }
        return new ParsedRule("unique", List.of(inner), null);
    }

    private static List<String> extractExpressionFieldCandidates(String expression) {
        if (expression == null || expression.isBlank()) return List.of();

        Matcher matchesMatcher = FIELD_MATCHES_PATTERN.matcher(expression);
        if (matchesMatcher.matches()) {
            return List.of(matchesMatcher.group(1));
        }

        Matcher comparisonMatcher = FIELD_COMPARISON_PATTERN.matcher(expression);
        if (comparisonMatcher.matches()) {
            return List.of(comparisonMatcher.group(1));
        }

        Matcher uniqueByMatcher = FIELD_UNIQUE_BY_PATTERN.matcher(expression);
        if (uniqueByMatcher.matches()) {
            return List.of(uniqueByMatcher.group(1));
        }

        String stripped = stripQuotedSegments(expression);
        Matcher identifierMatcher = IDENTIFIER_PATTERN.matcher(stripped);
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        while (identifierMatcher.find()) {
            String identifier = identifierMatcher.group(1);
            if (identifier == null || identifier.isBlank()) {
                continue;
            }
            String normalized = identifier.toLowerCase(Locale.ROOT);
            if (NON_FIELD_IDENTIFIERS.contains(normalized)) {
                continue;
            }

            int start = identifierMatcher.start(1);
            int end = identifierMatcher.end(1);
            char previous = start > 0 ? stripped.charAt(start - 1) : '\0';
            char next = end < stripped.length() ? stripped.charAt(end) : '\0';

            // Ignore dotted chains and function identifiers; keep bare field references only.
            if (previous == '.' || next == '.') {
                continue;
            }
            if (nextNonWhitespace(stripped, end) == '(') {
                continue;
            }

            refs.add(identifier);
        }

        return List.copyOf(refs);
    }

    private static char nextNonWhitespace(String value, int start) {
        for (int idx = start; idx < value.length(); idx++) {
            char current = value.charAt(idx);
            if (!Character.isWhitespace(current)) {
                return current;
            }
        }
        return '\0';
    }

    private static String stripQuotedSegments(String expression) {
        StringBuilder out = new StringBuilder(expression.length());
        boolean inSingle = false;
        boolean inDouble = false;
        for (int idx = 0; idx < expression.length(); idx++) {
            char current = expression.charAt(idx);
            if (current == '\'' && !inDouble) {
                inSingle = !inSingle;
                out.append(' ');
                continue;
            }
            if (current == '"' && !inSingle) {
                inDouble = !inDouble;
                out.append(' ');
                continue;
            }
            out.append((inSingle || inDouble) ? ' ' : current);
        }
        return out.toString();
    }

    private static boolean isDeprecatedSchemaTarget(String schemaTarget) {
        if (schemaTarget == null || schemaTarget.isBlank()) {
            return false;
        }
        String normalized = schemaTarget.replace('\\', '/').toLowerCase(Locale.ROOT);
        String legacyModelSchemaName = "model-" + "1.0.0" + ".schema.json";
        return normalized.endsWith("/" + legacyModelSchemaName) || normalized.endsWith(legacyModelSchemaName);
    }

    private record ParsedRule(String type, List<String> fields, String expression) {
    }
}

