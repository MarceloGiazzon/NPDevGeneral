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
                .toList());
    }

    public ModelAst parse(JsonNode root) throws IOException {
        return parse(root, "<resolved-model>", List.of());
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
        return parse(root, sourceLabel, List.of());
    }

    private ModelAst parse(JsonNode root, String sourceLabel, List<String> sourceWarnings) throws IOException {
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
                        ui
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
                        sensitive
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
            ConceptAst concept = new ConceptAst(name, extendsName, specializesName, fields, invariants, conceptEvents, lifecycle, conceptUi, truthLevel, module, indexes, access, conceptRenamedFrom);
            concepts.add(concept);
            conceptsByLowerName.put(name.toLowerCase(Locale.ROOT), concept);
        }

        parseCapabilitiesArray(root.get("capabilities"), "capabilities", capabilities, conceptsByLowerName);
        parseCapabilitiesArray(root.get("customCapabilities"), "customCapabilities", capabilities, conceptsByLowerName);

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
                events.add(new EventAst(name, null, specializes, eventVersion, payload));
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
                        schedule
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

        queries.addAll(parseQueries(root.get("queries")));
        ruleProfiles.addAll(parseRuleProfiles(root.get("ruleProfiles")));
        procedures.addAll(parseProcedures(root.get("procedures")));
        panels.addAll(parsePanels(root.get("panels")));
        guidePages.addAll(parseGuidePages(root.get("guidePages")));
        aggregates.addAll(parseAggregates(root.get("aggregates")));
        autoPanels.addAll(parseAutoPanels(root.get("autoPanels")));
        selectors.addAll(parseSelectors(root.get("selectors")));
        documents.addAll(parseDocuments(root.get("documents")));
        ExternalAiAst externalAi = parseExternalAi(root.get("externalAi"));

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
                externalAi
        );
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

    private static List<QueryAst> parseQueries(JsonNode node) throws IOException {
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
                    readText(queryNode, "auditPolicy"),
                    parseObjectMap(queryNode.get("metadata"))
            ));
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
                    readText(procedureNode, "auditPolicy"),
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
                    parseObjectMap(stepNode.get("metadata"))
            ));
        }
        return out;
    }

    private static List<PanelAst> parsePanels(JsonNode node) throws IOException {
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
                    readText(panelNode, "guidePage")
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
            out.add(new DocumentAst(
                    requiredText(documentNode, "name"),
                    requiredText(documentNode, "concept"),
                    readText(documentNode, "title"),
                    readText(documentNode, "pageSize"),
                    readOptionalDouble(documentNode, "marginMm"),
                    parseObjectMap(documentNode.get("metadata"))
            ));
        }
        return out;
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
                    parseObjectMap(aggregateNode.get("metadata"))
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
                parseObjectMap(node.get("metadata"))
        );
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
                    readText(gadgetNode, "title")
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
                    parseTextArray(dataSourceNode.get("addFormFields"))
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
                    readText(actionNode, "label"),
                    requiredText(actionNode, "binding"),
                    readText(actionNode, "concept"),
                    readText(actionNode, "operation"),
                    readText(actionNode, "procedure"),
                    readText(actionNode, "flow"),
                    readText(actionNode, "visibleWhen"),
                    readText(actionNode, "enabledWhen"),
                    parseTextArray(actionNode.get("permissionRequirements")),
                    parseObjectMap(actionNode.get("explainability")),
                    parseObjectMap(actionNode.get("metadata"))
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
            String label = firstNonBlank(readText(item, "label"), readText(item, "displayLabel"));
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
                    description
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

        // LNCH-17: declared compensation steps, run in reverse completion order when a later step
        // in the same flow terminally fails.
        List<StepAst> onFailureSteps = List.of();
        JsonNode onFailureNode = stepNode.get("onFailure");
        if (onFailureNode != null && onFailureNode.isArray()) {
            onFailureSteps = parseStepList(flowName + "." + stepName + ".onFailure", onFailureNode);
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
                onFailureSteps
        );
    }

    private List<CapabilityAst> parseCapabilitiesArray(
            JsonNode capabilitiesNode,
            String sourceLabel,
            List<CapabilityAst> target,
            Map<String, ConceptAst> conceptsByLowerName
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
            target.add(new CapabilityAst(name, type, specializes, operations));
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
                readText(node, "label"),
                readText(node, "confirmationText"),
                readText(node, "successMessage"),
                readText(node, "failureHint"),
                readText(node, "dangerLevel"),
                readText(node, "visibleWhen"),
                readText(node, "permissionHint"),
                readText(node, "inputFormHint")
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
                readText(node, "label"),
                readText(node, "placeholder"),
                readText(node, "helpText"),
                readText(node, "widget")
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
                readText(node, "label"),
                readText(node, "shortLabel"),
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
                readText(node, "customWidgetRef")
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
            String actionLabel = readText(transitionNode, "actionLabel");
            Map<String, String> metadata = parseStringMap(transitionNode.get("metadata"));
            ActionMetadataAst action = parseActionMetadata(transitionNode.get("action"), fieldPath + ".transitions[" + index + "].action");
            transitions.add(new StateTransitionAst(from, to, requiredPayload, event, guard, actionLabel, metadata, action));
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
            String label = readText(stateNode, "label");
            Boolean initial = readOptionalBoolean(stateNode, "initial");
            Boolean terminal = readOptionalBoolean(stateNode, "terminal");
            Map<String, String> metadata = parseStringMap(stateNode.get("metadata"));
            states.add(new StateMachineStateAst(
                    value,
                    label,
                    initial != null && initial,
                    terminal != null && terminal,
                    metadata
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

    private static String normalizeStepType(String type) {
        if (type == null) return null;
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "validate", "enforceinvariants", "invariant", "invariantcheck" -> "invariant";
            case "capabilitycall", "callcapability", "capability" -> "capability";
            case "generatedaction", "generated_action" -> "generatedAction";
            case "createentity", "createconcept", "conceptcreate" -> "createConcept";
            case "updateentity", "updateconcept", "conceptupdate" -> "updateConcept";
            case "emitevent", "event" -> "event";
            case "scheduleevent" -> "scheduleEvent";
            case "if", "branch" -> "branch";
            case "assign", "map" -> "map";
            case "waitforevent", "awaitevent", "await_event", "await" -> "await";
            case "return" -> "return";
            case "foreach", "loop" -> "forEach";
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

