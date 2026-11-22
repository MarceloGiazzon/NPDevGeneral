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
        if (root.has("model")) {
            throw new DeprecationException(new ValidationDiagnostic(
                    ValidationLayer.STRUCTURAL,
                    ValidationSeverity.ERROR,
                    "LEGACY_MODEL_ROOT_KEY",
                    "Root 'model' (a legacy alias of the model name) is no longer supported. Use root 'namespace'.",
                    "NPDevContract",
                    "$.model",
                    null,
                    null,
                    "namespace",
                    null,
                    "Rename root 'model' to 'namespace' (same value).",
                    "legacy-model-root-key"
            ));
        }
        // D-1 (phase 2): the top-level autoPanels[] key was folded into screens[] (an `admin` block). It is refused HERE,
        // before the schema (which no longer lists the key) can answer with a bare "property not defined".
        if (root.has("autoPanels")) {
            throw new DeprecationException(new ValidationDiagnostic(
                    ValidationLayer.STRUCTURAL,
                    ValidationSeverity.ERROR,
                    "LEGACY_AUTOPANELS_ROOT_KEY",
                    "'autoPanels' was removed (D-1): declare each entry as a screens[] entry with an `admin` block.",
                    "NPDevContract",
                    "$.autoPanels",
                    null,
                    null,
                    "screens[].admin",
                    null,
                    "Replace each autoPanels[] entry with {\"name\": <its name, or the concept/aggregate name when it had none>, "
                            + "\"kind\": \"list\" (a concept's pages) or \"workbench\" (an aggregate's workbench), "
                            + "\"concept\": <its concept, or its aggregate root>, \"admin\": {<every other key of the entry: route, "
                            + "surfaces, selection, detail, transaction, prompt, metadata>}} in screens[]; name, concept and aggregate "
                            + "stay on the screen and are not repeated in admin.",
                    "legacy-autopanels-root-key"
            ));
        }
        // D-1 (phase 3): the top-level panels[] key was folded into screens[] (a kind "custom" screen with a `panel` block).
        if (root.has("panels")) {
            throw new DeprecationException(new ValidationDiagnostic(
                    ValidationLayer.STRUCTURAL,
                    ValidationSeverity.ERROR,
                    "LEGACY_PANELS_ROOT_KEY",
                    "'panels' was removed (D-1): declare each entry as a screens[] entry of kind \"custom\" with a `panel` block.",
                    "NPDevContract",
                    "$.panels",
                    null,
                    null,
                    "screens[].panel",
                    null,
                    "Replace each panels[] entry with {\"name\": <its name>, \"kind\": \"custom\", \"panel\": {<every other key of the "
                            + "entry: route, title, dataSources, layout, fieldBindings, visibility, enabledWhen, actions, explainability, "
                            + "metadata, guidePage>}} in screens[]; the name stays on the screen and is not repeated in panel.",
                    "legacy-panels-root-key"
            ));
        }
        if (root.has("contexts")) {
            throw new DeprecationException(new ValidationDiagnostic(
                    ValidationLayer.STRUCTURAL,
                    ValidationSeverity.ERROR,
                    "LEGACY_CONTEXTS_ROOT_KEY",
                    "Root 'contexts' was renamed to 'modules'.",
                    "NPDevContract",
                    "$.contexts",
                    null,
                    null,
                    "modules",
                    null,
                    "Rename root 'contexts' to 'modules' (same entries: {name, $ref, physicallyIsolate}).",
                    "legacy-contexts-root-key"
            ));
        }
        if (root.has("customCapabilities")) {
            throw new DeprecationException(new ValidationDiagnostic(
                    ValidationLayer.STRUCTURAL,
                    ValidationSeverity.ERROR,
                    "LEGACY_CUSTOM_CAPABILITIES_ROOT_KEY",
                    "Root 'customCapabilities' was merged into 'capabilities' (REG-342/P4-T5); it was only ever an alias.",
                    "NPDevContract",
                    "$.customCapabilities",
                    null,
                    null,
                    "capabilities",
                    null,
                    "Move every 'customCapabilities' entry into 'capabilities' (same shape; adapter 'plugin:*' still marks code).",
                    "legacy-custom-capabilities-root-key"
            ));
        }
        if (root.has("procedures")) {
            throw new DeprecationException(new ValidationDiagnostic(
                    ValidationLayer.STRUCTURAL,
                    ValidationSeverity.ERROR,
                    "LEGACY_PROCEDURES_ROOT_KEY",
                    "Root 'procedures' was removed (REG-342): every procedure is a flow.",
                    "NPDevContract",
                    "$.procedures",
                    null,
                    null,
                    "flows",
                    null,
                    "Rewrite each procedure as a flow in 'flows' (steps keep their shape; a code procedure is a flow with metadata.untrustedExtensionEntrypoint).",
                    "legacy-procedures-root-key"
            ));
        }
        if (root.has("fragments")) {
            throw new DeprecationException(new ValidationDiagnostic(
                    ValidationLayer.STRUCTURAL,
                    ValidationSeverity.ERROR,
                    "LEGACY_FRAGMENTS_ROOT_KEY",
                    "Root 'fragments' was removed: a file split is a plain $ref include of an array entry.",
                    "NPDevContract",
                    "$.fragments",
                    null,
                    null,
                    "$ref",
                    null,
                    "Replace root 'fragments' with {\"$ref\": \"file.json\"} entries in the arrays themselves (capabilities, bindings, flows, ...); a referenced file's same-named array is spliced in.",
                    "legacy-fragments-root-key"
            ));
        }
        // D-1 (phase 4): the root backOffice key became the `admin` block of an experiences[] entry.
        if (root.has("backOffice")) {
            throw new DeprecationException(new ValidationDiagnostic(
                    ValidationLayer.STRUCTURAL,
                    ValidationSeverity.ERROR,
                    "LEGACY_BACKOFFICE_ROOT_KEY",
                    "'backOffice' was removed (D-1): the back office is the admin experience -- declare it as an experiences[] entry with an `admin` block.",
                    "NPDevContract",
                    "$.backOffice",
                    null,
                    null,
                    "experiences[].admin",
                    null,
                    "Replace the root \"backOffice\": {<keys>} with an entry in experiences[]: {\"name\": \"admin\", \"admin\": {<the same keys: "
                            + "locale, strings, defaultRoute, navigation, pages>}}. A pack declares its pages the same way "
                            + "(an experiences[] entry with an `admin` block holding `pages`).",
                    "legacy-backoffice-root-key"
            ));
        }
        for (String movedKey : List.of("appShell", "guidePages")) {
            if (root.has(movedKey)) {
                boolean shell = "appShell".equals(movedKey);
                throw new DeprecationException(new ValidationDiagnostic(
                        ValidationLayer.STRUCTURAL,
                        ValidationSeverity.ERROR,
                        shell ? "LEGACY_APPSHELL_ROOT_KEY" : "LEGACY_GUIDEPAGES_ROOT_KEY",
                        "Root '" + movedKey + "' was removed (FORM P5-T9c): the back-office configuration is the `admin` block of an "
                                + "experiences[] entry {defaultRoute, navigation, pages}.",
                        "NPDevContract",
                        "$." + movedKey,
                        null,
                        null,
                        "experiences[].admin",
                        null,
                        shell
                                ? "Move root appShell {defaultRoute, navigation} to an experiences[] entry {\"name\": \"admin\", \"admin\": {\"defaultRoute\": ..., \"navigation\": [...]}} (it also takes \"pages\")."
                                : "Move root guidePages[] to an experiences[] entry {\"name\": \"admin\", \"admin\": {\"pages\": [...]}} (same entries); a pack declares them the same way.",
                        shell ? "legacy-appshell-root-key" : "legacy-guidepages-root-key"
                ));
            }
        }
        if (root.has("aggregates")) {
            throw new DeprecationException(new ValidationDiagnostic(
                    ValidationLayer.STRUCTURAL,
                    ValidationSeverity.ERROR,
                    "LEGACY_AGGREGATES_ROOT_KEY",
                    "Root 'aggregates' was removed (FORM P5-T9e): an aggregate is its root concept's composition -- "
                            + "concepts[].composition {collections, invariants, balances, onValidate, onCommit}; its name is the concept's name.",
                    "NPDevContract",
                    "$.aggregates",
                    null,
                    null,
                    "composition",
                    null,
                    "Move each aggregate's collections/invariants/balances/onValidate/onCommit/metadata into \"composition\" on its root concept, "
                            + "and point every \"aggregate\" reference (autoPanels, screens, agentAccess exposures, gadgets) at the concept name. "
                            + "`python NPDevCli/aggregates_to_composition_codemod.py --root <dir> --apply` does the move.",
                    "legacy-aggregates-root-key"
            ));
        }
        if (root.has("settings")) {
            throw new DeprecationException(new ValidationDiagnostic(
                    ValidationLayer.STRUCTURAL,
                    ValidationSeverity.ERROR,
                    "LEGACY_SETTINGS_ROOT_KEY",
                    "Root 'settings' was removed (FORM P5-T9g): the app's locale and string catalogue are the admin experience's locale and "
                            + "strings (experiences[].admin); settings.ui (pageRows, dateFormat) was read by nothing and is gone.",
                    "NPDevContract",
                    "$.settings",
                    null,
                    null,
                    "experiences[].admin",
                    null,
                    "Move root settings.locale and settings.strings to an experiences[] entry {\"name\": \"admin\", \"admin\": {\"locale\": ..., \"strings\": {...}}} and delete settings.ui.",
                    "legacy-settings-root-key"
            ));
        }
        if (root.has("ruleProfiles")) {
            throw new DeprecationException(new ValidationDiagnostic(
                    ValidationLayer.STRUCTURAL,
                    ValidationSeverity.ERROR,
                    "LEGACY_RULE_PROFILES_ROOT_KEY",
                    "Root 'ruleProfiles' was removed: it was parsed and name-checked but never read by the runtime.",
                    "NPDevContract",
                    "$.ruleProfiles",
                    null,
                    null,
                    null,
                    null,
                    "Delete root 'ruleProfiles'. The invariant phases (always, interactive, headless, query, beforeCommit, afterCommit) are fixed by the platform and need no declaration.",
                    "legacy-rule-profiles-root-key"
            ));
        }
        if (root.has("bindings")) {
            throw new DeprecationException(new ValidationDiagnostic(
                    ValidationLayer.STRUCTURAL,
                    ValidationSeverity.ERROR,
                    "LEGACY_BINDINGS_ROOT_KEY",
                    "Root 'bindings' was removed: an adapter that is not a deployment default is declared on the capability itself.",
                    "NPDevContract",
                    "$.bindings",
                    null,
                    null,
                    "capabilities[].adapter",
                    null,
                    "Delete root 'bindings'. Move each non-default entry onto its capability as capabilities[].adapter (add {\"name\": ..., \"adapter\": ...} when the capability is not declared, e.g. plugin:*). persistence -> repository, eventBus -> inproc and a NotificationCapability -> notification-inproc are generated; override one per deployment with config.json adapters {capability: adapter}.",
                    "legacy-bindings-root-key"
            ));
        }
        if (root.has("webhooks")) {
            throw new DeprecationException(new ValidationDiagnostic(
                    ValidationLayer.STRUCTURAL,
                    ValidationSeverity.ERROR,
                    "LEGACY_WEBHOOKS_ROOT_KEY",
                    "Root 'webhooks' was removed: an inbound door is declared on the capability it serves.",
                    "NPDevContract",
                    "$.webhooks",
                    null,
                    null,
                    "inbound",
                    null,
                    "Move each webhooks[] entry onto a capability as capabilities[].inbound ({path (was source), hmacSecretEnvVar, eventName, fieldMapping, idempotencyKey, signature}); a former startFlow becomes {\"trigger\": {\"webhook\": \"<capability name>\"}} on that flow.",
                    "legacy-webhooks-root-key"
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
        // D-2 (2026-11-21): `extends` retired everywhere. On a concept it was an alias of `specializes`.
        JsonNode rawConcepts = root.get("concepts");
        if (rawConcepts != null && rawConcepts.isArray()) {
            for (JsonNode rawConcept : rawConcepts) {
                if (rawConcept != null && rawConcept.isObject() && rawConcept.has("extends")) {
                    String conceptName = readText(rawConcept, "name");
                    throw new DeprecationException(new ValidationDiagnostic(
                            ValidationLayer.STRUCTURAL,
                            ValidationSeverity.ERROR,
                            "LEGACY_CONCEPT_EXTENDS",
                            "Concept " + (conceptName == null ? "" : "'" + conceptName + "' ")
                                    + "uses 'extends', which was removed (D-2): it was only ever an alias of 'specializes'.",
                            "NPDevContract",
                            "$.concepts[" + (conceptName == null ? "?" : conceptName) + "].extends",
                            null,
                            null,
                            "specializes",
                            null,
                            "Rename the concept's 'extends' to 'specializes' (same value).",
                            "legacy-concept-extends"
                    ));
                }
            }
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
        String namespace = readText(root, "namespace");
        if (namespace == null || namespace.isBlank()) {
            throw new IOException("Missing/blank required field: namespace");
        }
        String dslVersion = readText(root, "dslVersion");
        if (dslVersion == null) {
            JsonNode rawDslVersion = root.get("dslVersion");
            if (rawDslVersion != null && !rawDslVersion.isNull()) {
                throw new IOException("dslVersion must not be blank. Supported value: \"" + SUPPORTED_DSL_VERSION + "\".");
            }
            dslVersion = SUPPORTED_DSL_VERSION;
        } else if (dslVersion.isBlank()) {
            throw new IOException("dslVersion must not be blank. Supported value: \"" + SUPPORTED_DSL_VERSION + "\".");
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
        List<EventAst> events = new ArrayList<>();
        List<FlowAst> flows = new ArrayList<>();
        List<QueryAst> queries = new ArrayList<>();
        List<PanelAst> panels = new ArrayList<>();
        List<GuidePageAst> guidePages = new ArrayList<>();
        List<AggregateAst> aggregates = new ArrayList<>();
        List<AutoPanelAst> autoPanels = new ArrayList<>();
        List<com.npdev.dsl.v1.ast.RoleAst> roles = new ArrayList<>();
        List<com.npdev.dsl.v1.ast.PropertyScopeAst> propertyScopes = new ArrayList<>();
        List<com.npdev.dsl.v1.ast.PropertyAst> properties = new ArrayList<>();
        List<com.npdev.dsl.v1.ast.ContextAst> contexts = new ArrayList<>();
        List<String> parserWarnings = new ArrayList<>(sourceWarnings == null ? List.of() : sourceWarnings);
        collectEnumOptionAliasWarnings(root, "$", parserWarnings);
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
            String extendsName = null; // `extends` retired (D-2); checkDeprecatedAuthoringShape refuses it first
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
                String fieldUid = readText(f, "uid");
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
                        fieldAccess,
                        fieldUid
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
                access = new ConceptAccessAst(accessRead, accessWrite, readPublicRead(name, accessNode.get("public")));
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
                    String eventUid = readText(ev, "uid");
                    EventAst eventAst = new EventAst(eventName, name, eventSpecializes, eventVersion, payload, eventTriggerMode,
                            null, eventUid);
                    conceptEvents.add(eventAst);
                    events.add(eventAst);
                }
            }

            TruthLevel truthLevel = TruthLevel.fromStringOrDefault(readText(ent, "truthLevel"));
            String module = readText(ent, "module");
            String conceptRenamedFrom = readText(ent, "renamedFrom");
            String conceptSatelliteOf = readText(ent, "satelliteOf");
            boolean conceptSoftDelete = ent.has("softDelete") && ent.get("softDelete").asBoolean(false);
            boolean conceptTemporal = ent.has("temporal") && ent.get("temporal").asBoolean(false);
            String conceptUid = readText(ent, "uid");
            boolean conceptSystem = ent.has("system") && ent.get("system").asBoolean(false);
            ConceptAst concept = new ConceptAst(name, extendsName, specializesName, fields, invariants, conceptEvents, lifecycle, conceptUi, truthLevel, module, indexes, access, conceptRenamedFrom, conceptSatelliteOf, originFor(originByQualifiedMemberName, "concepts", name), conceptSoftDelete, conceptTemporal, conceptUid,
                    readRollups(name, ent.get("rollups")), readCollaboration(name, ent.get("collaboration")), conceptSystem);
            concepts.add(concept);
            conceptsByLowerName.put(name.toLowerCase(Locale.ROOT), concept);
        }

        parseCapabilitiesArray(root.get("capabilities"), "capabilities", capabilities, conceptsByLowerName, originByQualifiedMemberName);

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
                String topLevelEventUid = readText(ev, "uid");
                events.add(new EventAst(name, null, specializes, eventVersion, payload, null,
                        originFor(originByQualifiedMemberName, "events", name), topLevelEventUid));
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
                // FORM P3-T5c: a flow's concept is optional -- a flow that only orchestrates (a former procedure or
                // orchestration rule) names the concept on each step's `scope` instead.
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
                // FORM P3-T5a: trigger / expose / runAs. A trigger.schedule lands in the flow's own schedule
                // (declaring both is refused), so the cron scheduler keeps reading one place.
                FlowTriggerAst trigger = null;
                JsonNode triggerNode = flowNode.get("trigger");
                if (triggerNode != null && !triggerNode.isNull()) {
                    if (triggerNode.isTextual()) {
                        if (!"api".equals(triggerNode.asText().trim())) {
                            throw new IOException("Flow " + flowName + " trigger must be \"api\" or an object with exactly one of "
                                    + "event, schedule, webhook");
                        }
                    } else if (triggerNode.isObject()) {
                        List<String> kinds = new ArrayList<>();
                        for (String key : List.of("event", "schedule", "webhook")) {
                            if (triggerNode.has(key)) {
                                kinds.add(key);
                            }
                        }
                        Iterator<String> triggerKeys = triggerNode.fieldNames();
                        while (triggerKeys.hasNext()) {
                            String key = triggerKeys.next();
                            if (!kinds.contains(key) && !"condition".equals(key)) {
                                throw new IOException("Flow " + flowName + " trigger has unknown key '" + key
                                        + "' (allowed: event, condition, schedule, webhook)");
                            }
                        }
                        if (kinds.size() != 1) {
                            throw new IOException("Flow " + flowName + " trigger must have exactly one of event, schedule, webhook");
                        }
                        if (triggerNode.has("condition") && !"event".equals(kinds.get(0))) {
                            throw new IOException("Flow " + flowName + " trigger.condition is only valid with trigger.event");
                        }
                        switch (kinds.get(0)) {
                            case "event": {
                                String triggerEvent = readText(triggerNode, "event");
                                if (triggerEvent == null || triggerEvent.isBlank()) {
                                    throw new IOException("Flow " + flowName + " trigger.event must name an event");
                                }
                                String triggerCondition = readText(triggerNode, "condition");
                                trigger = new FlowTriggerAst("event", triggerEvent.trim(),
                                        triggerCondition == null || triggerCondition.isBlank() ? null : triggerCondition.trim(), null);
                                break;
                            }
                            case "schedule": {
                                if (schedule != null) {
                                    throw new IOException("Flow " + flowName + " declares both schedule and trigger.schedule");
                                }
                                schedule = parseFlowSchedule(triggerNode.get("schedule"), flowName);
                                if (schedule == null) {
                                    throw new IOException("Flow " + flowName + " trigger.schedule must be an object with a cron");
                                }
                                trigger = new FlowTriggerAst("schedule", null, null, null);
                                break;
                            }
                            default: {
                                String triggerWebhook = readText(triggerNode, "webhook");
                                if (triggerWebhook == null || triggerWebhook.isBlank()) {
                                    throw new IOException("Flow " + flowName + " trigger.webhook must name a webhook");
                                }
                                trigger = new FlowTriggerAst("webhook", null, null, triggerWebhook.trim());
                                break;
                            }
                        }
                    } else {
                        throw new IOException("Flow " + flowName + " trigger must be \"api\" or an object");
                    }
                }
                String expose = readText(flowNode, "expose");
                if (expose != null && !expose.isBlank()) {
                    expose = expose.trim();
                    if (!List.of("api", "ui", "internal").contains(expose)) {
                        throw new IOException("Flow " + flowName + " expose must be api, ui or internal, got '" + expose + "'");
                    }
                    if ("api".equals(expose)) {
                        expose = null;
                    }
                } else {
                    expose = null;
                }
                String flowRunAs = readText(flowNode, "runAs");
                if (flowRunAs != null && !flowRunAs.isBlank()) {
                    flowRunAs = flowRunAs.trim();
                    if (!List.of("caller", "system").contains(flowRunAs)) {
                        throw new IOException("Flow " + flowName + " runAs must be caller or system, got '" + flowRunAs + "'");
                    }
                    if ("caller".equals(flowRunAs)) {
                        flowRunAs = null;
                    }
                } else {
                    flowRunAs = null;
                }
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
                        originFor(originByQualifiedMemberName, "flows", flowName),
                        trigger,
                        expose,
                        flowRunAs,
                        readText(flowNode, "description"),
                        readText(flowNode, "crossAggregateReason"),
                        parseObjectMap(flowNode.get("metadata"))
                ));
            }
        }

        // FORM P3-T5c (DSL 3.0): orchestration rules were folded into flows. Refuse the old keys with the
        // exact replacement, never ignore them -- a silently dropped rule is a rule that never fires.
        for (String removedKey : List.of("orchestrations", "orchestrationRules")) {
            if (root.has(removedKey)) {
                throw new IOException("'" + removedKey + "' was removed in DSL 3.0: declare a flow with "
                        + "trigger {\"event\": \"<EventName>\"} instead "
                        + "(its steps replace the rule's actions)");
            }
        }

        // FORM P5-T9a: the top-level selectors[] key was folded into screens[] (kind "picker"). Refuse it with the
        // exact replacement, never ignore it -- a silently dropped selector breaks every picker.selectorRef to it.
        if (root.has("selectors")) {
            throw new IOException("'selectors' was removed (FORM P5-T9a): declare each selector as a screens[] entry "
                    + "{\"name\": \"<same name>\", \"kind\": \"picker\", \"concept\": \"<concept>\", "
                    + "\"multiSelect\": ..., \"filters\": [...], \"columns\": [...], \"returnMapping\": {...}, "
                    + "\"orderBy\": ..., \"where\": \"<the old filter>\"}; picker.selectorRef keeps naming it");
        }
        // FORM P5-T9b: the same for documents[] (kind "document" screens).
        if (root.has("documents")) {
            throw new IOException("'documents' was removed (FORM P5-T9b): declare each document as a screens[] entry "
                    + "{\"name\": \"<same name>\", \"kind\": \"document\", \"concept\": \"<concept>\", \"title\": ..., "
                    + "\"pageSize\": ..., \"marginMm\": ..., \"aggregate\": ..., \"bands\": [...], \"logo\": {...}}; a multi-record report "
                    + "adds \"report\": true (the old kind \"list\") and \"printColumns\" (the old columns), \"query\", \"groupBy\", "
                    + "\"subtotals\", \"grandTotal\"; the render URLs /api/documents/{name}/render.pdf|csv keep the name");
        }

        queries.addAll(parseQueries(root.get("queries"), originByQualifiedMemberName.getOrDefault("queries", Map.of())));
        // D-1 (phase 3): a hand-written panel is a kind "custom" screen with a `panel` block; a pack's panel screen is
        // pack-qualified like the panel it replaces, so its origin is recorded under the screens kind.
        panels.addAll(parsePanels(panelScreensAsPanels(parseOpaqueObjects(root.get("screens"), "screens")),
                originByQualifiedMemberName.getOrDefault("screens", Map.of())));
        // FORM P5-T9c: the pages live under the root backOffice.pages (see parseBackOffice below).
        aggregates.addAll(parseCompositions(root.get("concepts")));
        // D-1: a concept's generated back-office pages (kind "list") and an aggregate's workbench (kind "workbench") are
        // declared on a screens[] entry under `admin`; parsed through the same code the old autoPanels[] key used, so the
        // compiler, expander and generator see the identical AutoPanelAst.
        autoPanels.addAll(parseAutoPanels(adminScreensAsAutoPanels(parseOpaqueObjects(root.get("screens"), "screens"))));
        ExternalAiAst externalAi = parseExternalAi(root.get("externalAi"));
        // D-1 phase 4: the back office is the `admin` block of an experiences[] entry (read through the same code the old
        // root backOffice key used); that entry is not a role experience and never reaches the experience list below.
        JsonNode backOfficeBlock = adminExperienceBlock(root.get("experiences"));
        SettingsAst settings = parseSettings(backOfficeBlock);
        roles.addAll(parseRoles(root.get("roles"), originByQualifiedMemberName.getOrDefault("roles", Map.of())));
        propertyScopes.addAll(parsePropertyScopes(root.get("propertyScopes")));
        properties.addAll(parseProperties(root.get("properties")));
        contexts.addAll(parseContexts(root.get("modules")));
        List<com.npdev.dsl.v1.ast.ConversionAst> conversions = parseConversions(root.get("conversions"));
        List<com.npdev.dsl.v1.ast.SequenceAst> sequences = parseSequences(root.get("sequences"));
        List<com.npdev.dsl.v1.ast.SeedAst> seeds = parseSeeds(root.get("seeds"));
        com.npdev.dsl.v1.ast.AgentAccessAst agentAccess = parseAgentAccess(root.get("agentAccess"));
        List<JsonNode> experiences = new ArrayList<>(parseOpaqueObjects(root.get("experiences"), "experiences"));
        experiences.removeIf(entry -> entry != null && entry.has("admin"));
        // FORM P5-T9c: the old top-level appShell + guidePages are the root backOffice {defaultRoute, navigation, pages}.
        // Read it into the SAME AppShellAst + guidePages list the compiler, canonical JSON and emitters consume.
        AppShellAst appShell = parseBackOffice(backOfficeBlock, guidePages);
        List<JsonNode> screens = parseOpaqueObjects(root.get("screens"), "screens");
        // FORM P5-T9b: ModelAst derives its documents from the document screens on every call; parse them once
        // here so a malformed band/column/logo is a parse error now, never a surprise in a later getDocuments().
        parseDocumentScreens(screens);

        return new ModelAst(
                namespace,
                dslVersion,
                version,
                concepts,
                domainTypes,
                capabilities,
                events,
                flows,
                queries,
                panels,
                guidePages,
                aggregates,
                autoPanels,
                parserWarnings,
                externalAi,
                settings,
                roles,
                propertyScopes,
                properties,
                contexts,
                conversions,
                physicalQualifierByConceptName,
                sequences,
                seeds,
                appShell,
                agentAccess,
                experiences,
                screens
        );
    }

    /** FORM P5-T1: parses an optional top-level array whose entries are carried as opaque JSON objects
     *  ({@code experiences}, {@code screens}). The schema has already shaped each entry; this only refuses a
     *  non-array / non-object so a hand-built tree cannot smuggle one in. Entries are deep-copied so the AST
     *  does not alias the parse tree. */
    private static List<JsonNode> parseOpaqueObjects(JsonNode node, String key) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException(key + " must be an array");
        }
        for (JsonNode entry : node) {
            if (!entry.isObject()) {
                throw new IOException(key + " entries must be objects");
            }
            out.add(entry.deepCopy());
        }
        return out;
    }

    /** B20 (S2): parses the optional top-level {@code modules} array -- {@code name} + {@code $ref}.
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
            throw new IOException("modules must be an array");
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
            // Wave 4 (B13 vocabulary expansion): "when" is case's list of literal equals/then
            // branches; "else" is a literal too (JSON key "else", read raw like "with" above since
            // an explicit "" is a legitimate replacement value, not an absent one).
            List<com.npdev.dsl.v1.ast.ConversionCaseWhenAst> when = new ArrayList<>();
            JsonNode whenNode = conversionNode.get("when");
            if (whenNode != null && !whenNode.isNull()) {
                if (!whenNode.isArray()) {
                    throw new IOException("conversions[" + id + "].when must be an array");
                }
                for (JsonNode whenElement : whenNode) {
                    // "then" is a literal replacement value -- read raw like "with"/"else", since an
                    // explicit "" is legitimate and readText()'s isBlank() collapse would wrongly turn
                    // it into a missing value.
                    JsonNode thenNode = whenElement.get("then");
                    String thenValue = (thenNode != null && !thenNode.isNull()) ? thenNode.asText() : null;
                    when.add(new com.npdev.dsl.v1.ast.ConversionCaseWhenAst(
                            requiredText(whenElement, "equals"),
                            thenValue
                    ));
                }
            }
            JsonNode elseNode = conversionNode.get("else");
            String elseValue = (elseNode != null && !elseNode.isNull()) ? elseNode.asText() : null;
            // B1 (REAL_LIFT_PLAN_2026-09-03, B13): javaHook is a sibling alternative to op -- both are
            // structurally optional here (the schema's oneOf, re-checked by ConversionAst's own
            // compact constructor, enforces exactly one is present).
            JsonNode javaHookNode = conversionNode.get("javaHook");
            com.npdev.dsl.v1.ast.ConversionAst.JavaHookAst javaHook = null;
            if (javaHookNode != null && !javaHookNode.isNull()) {
                javaHook = new com.npdev.dsl.v1.ast.ConversionAst.JavaHookAst(
                        requiredText(javaHookNode, "source"),
                        requiredText(javaHookNode, "class"),
                        requiredText(javaHookNode, "method")
                );
            }
            List<String> claims = new ArrayList<>();
            JsonNode claimsNode = conversionNode.get("claims");
            if (claimsNode != null && !claimsNode.isNull()) {
                if (!claimsNode.isArray()) {
                    throw new IOException("conversions[" + id + "].claims must be an array");
                }
                for (JsonNode claimElement : claimsNode) {
                    claims.add(claimElement.asText());
                }
            }
            out.add(new com.npdev.dsl.v1.ast.ConversionAst(
                    id,
                    requiredText(conversionNode, "concept"),
                    readText(conversionNode, "op"),
                    from,
                    readText(conversionNode, "to"),
                    into,
                    match,
                    readText(conversionNode, "set"),
                    mergeFrom,
                    with,
                    when,
                    elseValue,
                    javaHook,
                    claims
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
                    parseRoleConcepts(name, roleNode.get("concepts")),
                    toOriginAst(originByName.get(name))
            ));
        }
        return out;
    }

    /** Roles and users: a role's optional {@code concepts} object -- concept name -> operations
     *  ({@code read}/{@code create}/{@code update}/{@code delete}), checked by RoleValidation. */
    private static Map<String, List<String>> parseRoleConcepts(String roleName, JsonNode node) throws IOException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isObject()) {
            throw new IOException("roles[" + roleName + "].concepts must be an object");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getValue().isArray()) {
                throw new IOException("roles[" + roleName + "].concepts." + field.getKey() + " must be an array");
            }
            out.put(field.getKey(), parseTextArray(field.getValue()));
        }
        return out;
    }

    /** R5.3: parses the optional top-level {@code sequences} array -- {@code name} + {@code format}
     *  + optional {@code scope}. No pack-origin lookup (a sequence is referenced by an opaque literal): a
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
        List<ExternalAiPromptAst> prompts = new ArrayList<>();
        JsonNode promptsNode = node.get("prompts");
        if (promptsNode != null && !promptsNode.isNull()) {
            if (!promptsNode.isArray()) {
                throw new IOException("externalAi.prompts must be an array");
            }
            for (JsonNode prompt : promptsNode) {
                JsonNode outputSchema = prompt.get("outputSchema");
                prompts.add(new ExternalAiPromptAst(
                        readText(prompt, "name"),
                        readText(prompt, "description"),
                        readText(prompt, "vendor"),
                        readText(prompt, "model"),
                        readText(prompt, "template"),
                        readText(prompt, "image"),
                        outputSchema == null || outputSchema.isNull() ? null : outputSchema.toString(),
                        readOptionalInt(prompt, "maxOutputTokens"),
                        readText(prompt, "review"),
                        readText(prompt, "profile"),
                        readText(prompt, "effort")
                ));
            }
        }
        List<ExternalAiProviderAst> providers = new ArrayList<>();
        JsonNode providersNode = node.get("providers");
        if (providersNode != null && !providersNode.isNull()) {
            if (!providersNode.isArray()) {
                throw new IOException("externalAi.providers must be an array");
            }
            for (JsonNode provider : providersNode) {
                JsonNode needsKey = provider.get("needsKey");
                JsonNode price = provider.get("price");
                providers.add(new ExternalAiProviderAst(
                        readText(provider, "id"),
                        readText(provider, "name"),
                        readText(provider, "protocol"),
                        readText(provider, "baseUrl"),
                        needsKey == null || !needsKey.isBoolean() ? null : needsKey.asBoolean(),
                        readText(provider, "defaultModel"),
                        price != null && price.path("in").isNumber() ? price.path("in").decimalValue() : null,
                        price != null && price.path("out").isNumber() ? price.path("out").decimalValue() : null));
            }
        }
        JsonNode limits = node.get("limits");
        String callsPerUserPerDay = limits == null ? null : readText(limits, "callsPerUserPerDay");
        String monthlyCostCapUsd = limits == null ? null : readText(limits, "monthlyCostCapUsd");
        return new ExternalAiAst(egress, vendors, prompts, callsPerUserPerDay, monthlyCostCapUsd, providers);
    }

    /** Move 6 Move A, FORM P5-T9g: parses the app's locale + string catalogue, which live in the optional root
     *  {@code backOffice} object ({@code locale}, {@code strings}); null if the model declares neither. */
    private static SettingsAst parseSettings(JsonNode backOffice) throws IOException {
        if (backOffice == null || backOffice.isNull() || !backOffice.isObject()) {
            return null;
        }
        if (!backOffice.has("locale") && !backOffice.has("strings")) {
            return null;
        }
        String locale = readText(backOffice, "locale");
        Map<String, String> strings = new LinkedHashMap<>();
        JsonNode stringsNode = backOffice.get("strings");
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
        return new SettingsAst(locale, strings);
    }

    /** FORM P5-T9c: reads the optional ROOT {@code backOffice} object ({@code {defaultRoute?, navigation?, pages?}}),
     *  the replacement of the removed top-level {@code appShell} + {@code guidePages}, into the SAME
     *  {@link AppShellAst} and guide-page list the compiler, canonical JSON and emitters already consume (the IR is
     *  unchanged). {@code defaultRoute}/{@code navigation} become the shell ({@code null} when neither is declared,
     *  exactly like an absent {@code appShell}); {@code pages} are appended to {@code guidePages}. A model without
     *  {@code backOffice} behaves exactly as before: derived navigation and the built-in guide pages.
     *  {@link ModelSourceResolver} has already folded pack-contributed pages into {@code backOffice.pages}. */
    private static AppShellAst parseBackOffice(JsonNode backOffice, List<GuidePageAst> guidePages) throws IOException {
        if (backOffice == null || backOffice.isNull()) {
            return null;
        }
        if (!backOffice.isObject()) {
            throw new IOException("backOffice must be an object");
        }
        AppShellAst appShell = null;
        if (backOffice.has("defaultRoute") || backOffice.has("navigation")) {
            com.fasterxml.jackson.databind.node.ObjectNode shell = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            if (backOffice.has("defaultRoute")) {
                shell.set("defaultRoute", backOffice.get("defaultRoute"));
            }
            if (backOffice.has("navigation")) {
                shell.set("navigation", backOffice.get("navigation"));
            }
            appShell = parseAppShell(shell);
        }
        guidePages.addAll(parseGuidePages(backOffice.get("pages")));
        return appShell;
    }

    /** Path A P6.3: parses the back-office {@code defaultRoute}/{@code navigation} pair (declared navigation
     *  structure + default route for the generated app's shell chrome; it was the top-level {@code appShell}
     *  block until FORM P5-T9c moved it into the root {@code backOffice}); null if the model declares
     *  none. Neither {@code defaultRoute} nor a nav item's {@code target} is validated against an
     *  actual concept/panel name here -- structural plumbing only, see AppShellAst's javadoc. */
    private static AppShellAst parseAppShell(JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException("backOffice must be an object");
        }
        String defaultRoute = readText(node, "defaultRoute");
        List<AppShellNavItemAst> navigation = new ArrayList<>();
        JsonNode navigationNode = node.get("navigation");
        if (navigationNode != null && navigationNode.isArray()) {
            for (JsonNode itemNode : navigationNode) {
                if (!itemNode.isObject()) {
                    throw new IOException("backOffice.navigation entries must be objects");
                }
                navigation.add(new AppShellNavItemAst(
                        readText(itemNode, "label"),
                        readText(itemNode, "target"),
                        readText(itemNode, "group")
                ));
            }
        }
        return new AppShellAst(defaultRoute, navigation);
    }

    /** AGENT-1: parses the optional top-level {@code agentAccess} block (what an AI agent may see and
     *  do, and on which channels); null if the model declares none. */
    private static com.npdev.dsl.v1.ast.AgentAccessAst parseAgentAccess(JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException("agentAccess must be an object");
        }
        com.npdev.dsl.v1.ast.AgentAccessAssistantAst assistant = null;
        JsonNode assistantNode = node.get("assistant");
        if (assistantNode != null && !assistantNode.isNull()) {
            if (!assistantNode.isObject()) {
                throw new IOException("agentAccess.assistant must be an object");
            }
            assistant = new com.npdev.dsl.v1.ast.AgentAccessAssistantAst(
                    readText(assistantNode, "name"),
                    readText(assistantNode, "instructions"),
                    readText(assistantNode, "language"),
                    readText(assistantNode, "profile"),
                    readText(assistantNode, "effort")
            );
        }
        com.npdev.dsl.v1.ast.AgentAccessChannelsAst channels = null;
        JsonNode channelsNode = node.get("channels");
        if (channelsNode != null && !channelsNode.isNull()) {
            if (!channelsNode.isObject()) {
                throw new IOException("agentAccess.channels must be an object");
            }
            channels = new com.npdev.dsl.v1.ast.AgentAccessChannelsAst(
                    readChannelEnabled(channelsNode, "mcp"),
                    readChannelEnabled(channelsNode, "telegram"),
                    readChannelEnabled(channelsNode, "whatsapp")
            );
        }
        List<com.npdev.dsl.v1.ast.AgentAccessExposureAst> expose = new ArrayList<>();
        JsonNode exposeNode = node.get("expose");
        if (exposeNode == null || !exposeNode.isArray() || exposeNode.isEmpty()) {
            throw new IOException("agentAccess.expose must be a non-empty array");
        }
        for (JsonNode exposureNode : exposeNode) {
            if (!exposureNode.isObject()) {
                throw new IOException("agentAccess.expose entries must be objects");
            }
            expose.add(new com.npdev.dsl.v1.ast.AgentAccessExposureAst(
                    readText(exposureNode, "concept"),
                    readText(exposureNode, "flow"),
                    readText(exposureNode, "aggregate"),
                    parseTextArray(exposureNode.get("operations")),
                    parseTextArray(exposureNode.get("fields")),
                    parseTextArray(exposureNode.get("roles")),
                    readText(exposureNode, "description"),
                    readOptionalBoolean(exposureNode, "confirmWrites")
            ));
        }
        return new com.npdev.dsl.v1.ast.AgentAccessAst(assistant, channels, expose,
                parseAgentPhotoIntake(node.get("photoIntake")));
    }

    /** P8 (G5): {@code agentAccess.photoIntake} -- what a photo sent to the bot becomes; null if absent. */
    private static com.npdev.dsl.v1.ast.AgentAccessPhotoIntakeAst parseAgentPhotoIntake(JsonNode node)
            throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException("agentAccess.photoIntake must be an object");
        }
        Map<String, String> defaults = new LinkedHashMap<>();
        JsonNode defaultsNode = node.get("defaults");
        if (defaultsNode != null && !defaultsNode.isNull()) {
            if (!defaultsNode.isObject()) {
                throw new IOException("agentAccess.photoIntake.defaults must be an object of field -> value");
            }
            Iterator<Map.Entry<String, JsonNode>> entries = defaultsNode.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                if (!entry.getValue().isValueNode() || entry.getValue().isNull()) {
                    throw new IOException("agentAccess.photoIntake.defaults." + entry.getKey()
                            + " must be a string, number or boolean");
                }
                defaults.put(entry.getKey(), entry.getValue().asText());
            }
        }
        return new com.npdev.dsl.v1.ast.AgentAccessPhotoIntakeAst(
                readText(node, "concept"),
                readText(node, "imageField"),
                readText(node, "captionField"),
                readText(node, "procedure"),
                defaults,
                readText(node, "description"));
    }

    private static boolean readChannelEnabled(JsonNode channelsNode, String channelKey) throws IOException {
        JsonNode channelNode = channelsNode.get(channelKey);
        if (channelNode == null || channelNode.isNull()) {
            return false;
        }
        if (!channelNode.isObject()) {
            throw new IOException("agentAccess.channels." + channelKey + " must be an object");
        }
        Boolean enabled = readOptionalBoolean(channelNode, "enabled");
        return enabled != null && enabled;
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
                    parseQueryParameters(queryNode.get("parameters"), "queries[" + name + "].parameters"),
                    parseTextArray(queryNode.get("permissionRequirements")),
                    parseObjectMap(queryNode.get("metadata")),
                    parseGroupByFields(queryNode.get("groupBy")),
                    parseAggregateFunctions(queryNode.get("aggregates")),
                    readText(queryNode, "having"),
                    toOriginAst(originByName.get(name)),
                    parseQueryLookups(queryNode.get("lookups")),
                    parseQueryComputedColumns(queryNode.get("computed"))
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

    /** Friction #5: query.lookups[] -- {name, concept, fn, field?, on, where?}. */
    private static List<com.npdev.dsl.v1.ast.QueryLookupAst> parseQueryLookups(JsonNode node) throws IOException {
        List<com.npdev.dsl.v1.ast.QueryLookupAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("query.lookups must be an array");
        }
        for (JsonNode entry : node) {
            out.add(new com.npdev.dsl.v1.ast.QueryLookupAst(
                    requiredText(entry, "name"),
                    requiredText(entry, "concept"),
                    requiredText(entry, "fn"),
                    readText(entry, "field"),
                    parseStringMap(entry.get("on")),
                    readText(entry, "where")));
        }
        return out;
    }

    /** Friction #5: query.computed[] -- {name, expression}. */
    private static List<com.npdev.dsl.v1.ast.QueryComputedColumnAst> parseQueryComputedColumns(JsonNode node)
            throws IOException {
        List<com.npdev.dsl.v1.ast.QueryComputedColumnAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException("query.computed must be an array");
        }
        for (JsonNode entry : node) {
            out.add(new com.npdev.dsl.v1.ast.QueryComputedColumnAst(
                    requiredText(entry, "name"), requiredText(entry, "expression")));
        }
        return out;
    }

    private static List<QueryParameterAst> parseQueryParameters(JsonNode node, String fieldPath)
            throws IOException {
        List<QueryParameterAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException(fieldPath + " must be an array");
        }
        for (JsonNode parameterNode : node) {
            String name = requiredText(parameterNode, "name");
            Boolean required = readOptionalBoolean(parameterNode, "required");
            out.add(new QueryParameterAst(
                    name,
                    requiredText(parameterNode, "type"),
                    required != null && required,
                    parseSchema(parameterNode.get("schema"), fieldPath + "[" + name + "].schema"),
                    readText(parameterNode, "description"),
                    readText(parameterNode, "defaultExpression")
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
                    toOriginAst(originByName.get(name)),
                    readText(panelNode, "uid")
            ));
        }
        return out;
    }

    /**
     * FORM P5-T9b: the document declarations among {@code screens} (entries with {@code kind: "document"}, a name and a
     * concept), in order -- the one place the authoring form of a document is read, so {@code DocumentAst.fromDocumentScreens}
     * (what {@code ModelAst.getDocuments()} calls) and the parser share it. Property mapping onto the old {@code documents[]} entry:
     * {@code name, concept, title, pageSize, marginMm, metadata, aggregate, bands, logo, query, groupBy, subtotals, grandTotal}
     * keep their names; {@code report: true} is the old {@code kind: "list"}; {@code printColumns} is the old {@code columns}
     * (a screen's own {@code columns} is the 7-column list cap, which a printable report must not inherit). A document screen
     * without a name or concept is skipped here: {@code ExperienceValidation} reports it.
     */
    public static List<DocumentAst> parseDocumentScreens(List<JsonNode> screens) throws IOException {
        List<DocumentAst> out = new ArrayList<>();
        if (screens == null) {
            return out;
        }
        for (JsonNode documentNode : screens) {
            if (documentNode == null || !"document".equals(readText(documentNode, "kind"))) {
                continue;
            }
            String name = readText(documentNode, "name");
            String concept = readText(documentNode, "concept");
            if (name == null || concept == null) {
                continue;
            }
            String where = "screens[" + name + "]";
            out.add(new DocumentAst(
                    name,
                    concept,
                    readText(documentNode, "title"),
                    readText(documentNode, "pageSize"),
                    readOptionalDouble(documentNode, "marginMm"),
                    parseObjectMap(documentNode.get("metadata")),
                    readText(documentNode, "aggregate"),
                    parseDocumentBands(documentNode.get("bands"), where + ".bands"),
                    parseDocumentLogo(documentNode.get("logo"), where + ".logo"),
                    Boolean.TRUE.equals(readOptionalBoolean(documentNode, "report")) ? "list" : null,
                    readText(documentNode, "query"),
                    parseDocumentColumns(documentNode.get("printColumns"), where + ".printColumns"),
                    parseTextArray(documentNode.get("groupBy")),
                    readOptionalBoolean(documentNode, "subtotals"),
                    readOptionalBoolean(documentNode, "grandTotal")
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

    /** REP-1: a {@code kind: list} document's {@code columns[]} -- {@code field} required, {@code
     *  label}/{@code total} optional. */
    private static List<DocumentColumnAst> parseDocumentColumns(JsonNode node, String fieldPath) throws IOException {
        List<DocumentColumnAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException(fieldPath + " must be an array");
        }
        for (JsonNode columnNode : node) {
            out.add(new DocumentColumnAst(
                    requiredText(columnNode, "field"),
                    readLabelText(columnNode, "label"),
                    readText(columnNode, "total")));
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

    /** FORM P5-T9e: every concept that declares a {@code composition} is the root of an aggregate. The aggregate's name
     *  IS the concept's name (the internal {@link AggregateAst} keeps its {@code name}/{@code root} pair, equal here), so
     *  the compiler, canonical IR and runtime are unchanged. */
    private static List<AggregateAst> parseCompositions(JsonNode conceptsNode) throws IOException {
        List<AggregateAst> out = new ArrayList<>();
        if (conceptsNode == null || !conceptsNode.isArray()) {
            return out;
        }
        for (JsonNode conceptNode : conceptsNode) {
            JsonNode composition = conceptNode.get("composition");
            if (composition == null || composition.isNull()) {
                continue;
            }
            String name = requiredText(conceptNode, "name");
            if (!composition.isObject()) {
                throw new IOException("concepts[" + name + "].composition must be an object");
            }
            String path = "concepts[" + name + "].composition";
            out.add(new AggregateAst(
                    name,
                    name,
                    parseAggregateCollections(composition.get("collections"), path + ".collections"),
                    readText(composition, "onCommit"),
                    parseObjectMap(composition.get("metadata")),
                    readText(composition, "onValidate"),
                    // R4.4/npdev-aggregate-invariant-four-place: parser -> compiler -> canonical
                    // writer+reader, mirroring ast-compiled-four-place's chain for a per-member field.
                    parseAggregateInvariants(composition.get("invariants"), path + ".invariants"),
                    // npdev-aggregate-balance-four-place (Session 1, 2026-09-14): parser -> compiler
                    // -> canonical writer+reader, same shape as npdev-aggregate-invariant-four-place.
                    parseAggregateBalances(composition.get("balances"), path + ".balances")
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

    /** Session 1 (NPDEV_MEGA_ROADMAP.md, 2026-09-14): aggregates[].balances[] -- grouped,
     *  role-partitioned balance rules. See AggregateBalanceAst's javadoc. */
    private static List<AggregateBalanceAst> parseAggregateBalances(JsonNode node, String path)
            throws IOException {
        List<AggregateBalanceAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException(path + " must be an array");
        }
        for (JsonNode balanceNode : node) {
            out.add(new AggregateBalanceAst(
                    requiredText(balanceNode, "name"),
                    requiredText(balanceNode, "collection"),
                    parseTextArray(balanceNode.get("groupBy")),
                    requiredText(balanceNode, "discriminatorField"),
                    requiredText(balanceNode, "leftValue"),
                    requiredText(balanceNode, "rightValue"),
                    requiredText(balanceNode, "quantityField"),
                    readText(balanceNode, "message")
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
                    requiredText(collectionNode, "childField"),
                    readText(collectionNode, "ownership"),
                    readText(collectionNode, "orderBy"),
                    parseAggregateCollections(collectionNode.get("collections"),
                            path + "[" + name + "].collections"),
                    parseAggregateCollectionLookupFields(collectionNode.get("lookupFields"),
                            path + "[" + name + "].lookupFields")
            ));
        }
        return out;
    }

    /** Session 1 (NPDEV_MEGA_ROADMAP.md, 2026-09-14): aggregateCollection.lookupFields[] --
     *  read-only, query-sourced per-row fields. See AggregateCollectionLookupFieldAst's javadoc. */
    private static List<AggregateCollectionLookupFieldAst> parseAggregateCollectionLookupFields(
            JsonNode node, String path) throws IOException {
        List<AggregateCollectionLookupFieldAst> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new IOException(path + " must be an array");
        }
        for (JsonNode lookupNode : node) {
            out.add(new AggregateCollectionLookupFieldAst(
                    requiredText(lookupNode, "name"),
                    requiredText(lookupNode, "query"),
                    requiredText(lookupNode, "joinField"),
                    requiredText(lookupNode, "valueField")
            ));
        }
        return out;
    }

    /**
     * D-1 phase 1: the {@code screens[]} entries that carry an {@code admin} block, as the autoPanel objects
     * {@link #parseAutoPanels} reads. The screen supplies {@code name} and {@code concept}; {@code admin} holds the rest
     * ({@code route}, {@code surfaces}, {@code selection}, {@code detail}, {@code transaction}, {@code prompt},
     * {@code metadata}). {@code ExperienceValidation} refuses an {@code admin} that repeats {@code name}/{@code concept}
     * or sits on a screen that is not kind "list" with a concept, so this only reshapes.
     */
    static JsonNode adminScreensAsAutoPanels(List<JsonNode> screens) {
        var array = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (JsonNode screen : screens) {
            JsonNode admin = screen == null ? null : screen.get("admin");
            if (admin == null || !admin.isObject()) {
                continue;
            }
            var merged = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            if (screen.hasNonNull("name")) {
                merged.set("name", screen.get("name"));
            }
            if (screen.hasNonNull("concept")) {
                // D-1 phase 2: a workbench screen's concept IS the aggregate root, which is what an aggregate-bound
                // autoPanel names in `aggregate` (an aggregate is its root concept's composition).
                merged.set("workbench".equals(readText(screen, "kind")) ? "aggregate" : "concept", screen.get("concept"));
            }
            admin.fields().forEachRemaining(entry -> {
                if (!merged.has(entry.getKey())) {
                    merged.set(entry.getKey(), entry.getValue());
                }
            });
            array.add(merged);
        }
        return array;
    }

    /**
     * D-1 phase 4: the {@code admin} block of the {@code experiences[]} entry that configures the back office (the old
     * root {@code backOffice} object: {@code locale}, {@code strings}, {@code defaultRoute}, {@code navigation},
     * {@code pages}); null when no experience carries one. {@code ExperienceValidation} allows at most one.
     */
    static JsonNode adminExperienceBlock(JsonNode experiences) {
        if (experiences == null || !experiences.isArray()) {
            return null;
        }
        for (JsonNode experience : experiences) {
            JsonNode admin = experience == null ? null : experience.get("admin");
            if (admin != null && admin.isObject()) {
                return admin;
            }
        }
        return null;
    }

    /**
     * D-1 phase 3: the {@code screens[]} entries that carry a {@code panel} block, as the panel objects {@link #parsePanels}
     * reads. The screen supplies {@code name}; {@code panel} holds the rest ({@code route}, {@code title},
     * {@code dataSources}, {@code layout}, {@code fieldBindings}, {@code visibility}, {@code enabledWhen}, {@code actions},
     * {@code explainability}, {@code metadata}, {@code guidePage}, {@code uid}). {@code ExperienceValidation} refuses a
     * {@code panel} that repeats {@code name} or sits on a screen that is not kind "custom", so this only reshapes.
     */
    static JsonNode panelScreensAsPanels(List<JsonNode> screens) {
        var array = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (JsonNode screen : screens) {
            JsonNode panel = screen == null ? null : screen.get("panel");
            if (panel == null || !panel.isObject()) {
                continue;
            }
            var merged = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            if (screen.hasNonNull("name")) {
                merged.set("name", screen.get("name"));
            }
            panel.fields().forEachRemaining(entry -> {
                if (!merged.has(entry.getKey())) {
                    merged.set(entry.getKey(), entry.getValue());
                }
            });
            if (!merged.has("title") && screen.hasNonNull("title")) {
                merged.set("title", screen.get("title"));
            }
            array.add(merged);
        }
        return array;
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
                parseUiState(node.get("uiState")),
                readText(node, "layout"),
                readText(node, "panel")
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
                    // Session 1: procedure is now optional at the parser level -- an action may
                    // instead declare checkBalances. PanelValidation#validateWorkbenchActions
                    // enforces exactly one of the two is present.
                    readText(actionNode, "procedure"),
                    readLabelText(actionNode, "label"),
                    parseTextArray(actionNode.get("inputFields")),
                    parseWorkbenchActionApplyTo(actionNode.get("applyTo")),
                    readText(actionNode, "afterAction"),
                    readText(actionNode, "visibleWhen"),
                    readLabelLocales(actionNode, "label"),
                    parseTextArray(actionNode.get("checkBalances")),
                    actionNode.path("review").asBoolean(false)
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
                    readLabelLocales(pickerNode, "label"),
                    readText(pickerNode, "selectorRef")
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
            out.put(address, new RegionMountAst(
                    readText(regionNode, "render"), readText(regionNode, "component"), parseCellGrid(regionNode.get("cellGrid"))));
        }
        return out;
    }

    /** P3b: parses a region's optional cellGrid block (render "cellGrid"); null when absent. */
    private static CellGridAst parseCellGrid(JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException("transaction region cellGrid must be an object");
        }
        JsonNode palette = node.get("palette");
        return new CellGridAst(
                readText(node, "rowField"),
                readText(node, "colField"),
                readText(node, "valueField"),
                readText(node, "rowsField"),
                readText(node, "colsField"),
                readOptionalInt(node, "defaultRows"),
                readOptionalInt(node, "defaultCols"),
                readText(node, "layout"),
                palette == null ? null : readText(palette, "query"),
                palette == null ? null : readText(palette, "labelField"),
                palette == null ? null : readText(palette, "imageField"),
                palette == null ? null : readText(palette, "colorField"));
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
                    requiredText(computedNode, "expr"),
                    readText(computedNode, "collection")
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
            throw new IOException("backOffice.pages must be an array");
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
            throw new IOException("backOffice.pages[].regions must be an object");
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
            throw new IOException("backOffice.pages[].regions.left/right must be an object");
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
            throw new IOException("backOffice.pages[].theme must be an object");
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
            throw new IOException("backOffice.pages[].gadgets must be an array");
        }
        for (JsonNode gadgetNode : node) {
            out.add(new GuidePageGadgetAst(
                    requiredText(gadgetNode, "name"),
                    requiredText(gadgetNode, "type"),
                    readText(gadgetNode, "title"),
                    readText(gadgetNode, "query"),
                    readText(gadgetNode, "x"),
                    readText(gadgetNode, "y"),
                    readText(gadgetNode, "series"),
                    readText(gadgetNode, "url")
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
                    readText(dataSourceNode, "onRowLoad"),
                    readText(dataSourceNode, "totalField")
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
        List<PanelSlotAst> slots = new ArrayList<>();
        JsonNode slotsNode = node.get("slots");
        if (slotsNode != null && !slotsNode.isNull()) {
            if (!slotsNode.isArray()) {
                throw new IOException(fieldPath + ".slots must be an array");
            }
            int slotIndex = 0;
            for (JsonNode slotNode : slotsNode) {
                String slotPath = fieldPath + ".slots[" + slotIndex + "]";
                if (!slotNode.isObject()) {
                    throw new IOException(slotPath + " must be an object");
                }
                slots.add(new PanelSlotAst(
                        requiredText(slotNode, "placement"),
                        requiredText(slotNode, "component"),
                        readText(slotNode, "label")
                ));
                slotIndex++;
            }
        }
        List<PanelWidgetAst> widgets = new ArrayList<>();
        JsonNode widgetsNode = node.get("widgets");
        if (widgetsNode != null && !widgetsNode.isNull()) {
            if (!widgetsNode.isArray()) {
                throw new IOException(fieldPath + ".widgets must be an array");
            }
            int widgetIndex = 0;
            for (JsonNode widgetNode : widgetsNode) {
                String widgetPath = fieldPath + ".widgets[" + widgetIndex + "]";
                if (!widgetNode.isObject()) {
                    throw new IOException(widgetPath + " must be an object");
                }
                JsonNode filterNode = widgetNode.get("filter");
                JsonNode spanNode = widgetNode.get("span");
                widgets.add(new PanelWidgetAst(
                        requiredText(widgetNode, "kind"),
                        readText(widgetNode, "title"),
                        readText(widgetNode, "dataSource"),
                        readText(widgetNode, "aggregate"),
                        readText(widgetNode, "field"),
                        readText(widgetNode, "groupBy"),
                        readText(widgetNode, "chart"),
                        filterNode != null && filterNode.isObject() ? readText(filterNode, "field") : null,
                        filterNode != null && filterNode.isObject() ? readText(filterNode, "equals") : null,
                        spanNode != null && spanNode.canConvertToInt() ? Integer.valueOf(spanNode.asInt()) : null
                ));
                widgetIndex++;
            }
        }
        return new PanelLayoutAst(
                requiredText(node, "type"),
                children,
                parseTextArray(node.get("fields")),
                parseObjectMap(node.get("metadata")),
                slots,
                widgets
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

    /** PROC-5/PROC-6: {@code collaboration: { comments, attachments }}; null when absent. */
    private static CollaborationAst readCollaboration(String conceptName, JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException("Concept " + conceptName + " collaboration must be an object");
        }
        return new CollaborationAst(
                node.has("comments") && node.get("comments").asBoolean(false),
                node.has("attachments") && node.get("attachments").asBoolean(false));
    }

    /** P8 prelude: {@code rollups: [{ field, from, via, fn, of }]}; empty when absent. */
    private static List<RollupAst> readRollups(String conceptName, JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IOException("Concept " + conceptName + " rollups must be an array");
        }
        List<RollupAst> out = new ArrayList<>();
        for (JsonNode entry : node) {
            if (!entry.isObject()) {
                throw new IOException("Concept " + conceptName + " rollups[] entries must be objects");
            }
            out.add(new RollupAst(readText(entry, "field"), readText(entry, "from"), readText(entry, "via"),
                    readText(entry, "fn"), readText(entry, "of")));
        }
        return out;
    }

    /** P6: {@code access.public: { where, fields[], scope }}; null when absent. */
    private static PublicReadAst readPublicRead(String conceptName, JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException("Concept " + conceptName + " access.public must be an object");
        }
        List<String> fields = new ArrayList<>();
        JsonNode fieldsNode = node.get("fields");
        if (fieldsNode != null && fieldsNode.isArray()) {
            for (JsonNode field : fieldsNode) {
                if (field.isTextual() && !field.asText().isBlank()) {
                    fields.add(field.asText().trim());
                }
            }
        }
        return new PublicReadAst(readText(node, "where"), fields, readText(node, "scope"));
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

    /** B16/B19 (Move 9 A3): parses a field's `picker: {filter, multiSelect, selectorRef}` block --
     *  `selectorRef` added by REAL_LIFT_PLAN_2026-09-03 package C2 (EDIT-18). */
    private static FieldPickerAst parseFieldPicker(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String filter = readText(node, "filter");
        boolean multiSelect = node.has("multiSelect") && node.get("multiSelect").asBoolean(false);
        return new FieldPickerAst(filter, multiSelect, readText(node, "selectorRef"));
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

    /** Enum option keys parseEnumOptions folds together; the first of each pair wins. */
    private static final String[][] ENUM_OPTION_ALIAS_PAIRS = {
            {"label", "displayLabel"}, {"description", "help"}, {"icon", "iconHint"}, {"badge", "badgeHint"}};

    /**
     * ENF-1 (D5): an enum option that sets BOTH keys of an alias pair keeps the first and silently drops
     * the second (dsl-conformance-max's Draft option did, for all four pairs). Warned, never refused:
     * the model is valid, but the author wrote a value that will never be served.
     */
    private static void collectEnumOptionAliasWarnings(JsonNode node, String path, List<String> warnings) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectEnumOptionAliasWarnings(node.get(i), path + "[" + i + "]", warnings);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode options = node.get("enumValues");
        if (options != null && options.isArray()) {
            for (int i = 0; i < options.size(); i++) {
                JsonNode option = options.get(i);
                if (option == null || !option.isObject()) {
                    continue;
                }
                for (String[] pair : ENUM_OPTION_ALIAS_PAIRS) {
                    if (option.hasNonNull(pair[0]) && option.hasNonNull(pair[1])) {
                        warnings.add(path + ".enumValues[" + i + "] (" + option.path("value").asText("?") + ") sets both '"
                                + pair[0] + "' and its alias '" + pair[1] + "'; '" + pair[1] + "' is ignored -- keep one.");
                    }
                }
            }
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            collectEnumOptionAliasWarnings(entry.getValue(), path + "." + entry.getKey(), warnings);
        }
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
        for (JsonNode rawStepNode : stepsNode) {
            index++;
            // P8: a compact string step ("read Ride $input.id as ride") is lowered to the object it stands for.
            JsonNode stepNode = CompactSteps.expandStep(rawStepNode, "Flow " + flowName + " step #" + index);
            if ("notify".equalsIgnoreCase(readText(stepNode, "type"))) {
                steps.addAll(lowerNotifyStep(flowName, stepNode, index));
                continue;
            }
            steps.add(parseStep(flowName, stepNode, index));
        }
        return steps;
    }

    /** FORM P3-T8 (D11 = step): {@code {"type":"notify","to":..,"message":..}} is sugar for the two steps an author
     *  used to write -- a {@code map} that builds the {@code {to,message}} payload, then a
     *  {@code notification.send} capabilityCall on it. Lowering here keeps AST, compiled model, canonical JSON and
     *  kernel unchanged: the notification capability and its default adapter are implied by the compiler
     *  ({@code ModelCompiler.addImplicitNotification}). Optional on the notify step: {@code name}, {@code output},
     *  {@code errorTarget} (a failed send is stored there and the flow goes on), {@code capability} (a declared
     *  NotificationCapability other than the implied {@code notification}). */
    private static List<StepAst> lowerNotifyStep(String flowName, JsonNode stepNode, int index) throws IOException {
        String name = readText(stepNode, "name");
        if (name == null || name.isBlank()) {
            name = flowName + "-step-" + index;
        }
        if (!stepNode.hasNonNull("to") || !stepNode.hasNonNull("message")) {
            throw new IOException("Flow " + flowName + " step " + name + ": a notify step needs both `to` and `message` -- "
                    + "e.g. {\"type\":\"notify\",\"to\":\"$input.email\",\"message\":\"Welcome\"}");
        }
        var factory = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        String payloadVar = "$" + name.replaceAll("[^A-Za-z0-9_]", "_") + "_notice";
        var build = factory.objectNode();
        build.put("name", name + "-payload");
        build.put("type", "map");
        var select = build.putObject("select");
        select.set("to", stepNode.get("to"));
        select.set("message", stepNode.get("message"));
        build.put("output", payloadVar);
        var send = factory.objectNode();
        send.put("name", name);
        send.put("type", "capabilityCall");
        String capability = readText(stepNode, "capability");
        send.put("capability", capability == null || capability.isBlank() ? "notification" : capability);
        send.put("operation", "send");
        send.putArray("args").add(payloadVar);
        String output = firstNonBlank(readText(stepNode, "output"), readText(stepNode, "out"));
        if (output != null && !output.isBlank()) {
            send.put("output", output);
        }
        if (stepNode.hasNonNull("errorTarget")) {
            send.set("errorTarget", stepNode.get("errorTarget"));
        }
        if (stepNode.has("policy")) {
            send.set("policy", stepNode.get("policy"));
        }
        return List.of(parseStep(flowName, build, index), parseStep(flowName, send, index));
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
        // FORM P3-T5a: the keys of the merged step vocabulary's concept steps.
        String stepRecordId = readText(stepNode, "id");
        String stepQuery = readText(stepNode, "query");
        Map<String, String> stepParams = parseStringMap(stepNode.get("params"));
        String stepFlow = readText(stepNode, "flow");
        // FORM P3-T5c: `select` (map step) / `set` (updateConcept step) are one field map; plus createIfMissing / errorTarget / ifExists.
        Map<String, Object> stepFieldValues = parseObjectMap(stepNode.has("select") ? stepNode.get("select") : stepNode.get("set"));
        Boolean stepCreateIfMissing = readOptionalBoolean(stepNode, "createIfMissing");
        String stepErrorTarget = readText(stepNode, "errorTarget");
        String stepIfExists = readText(stepNode, "ifExists");
        String stepDedupeKey = readText(stepNode, "dedupeKey");

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

        // PROC-1: optional human-task metadata (who answers, form, outcomes) on an awaitEvent step.
        HumanTaskAst task = parseHumanTask(stepNode.get("task"));

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
                onTimeoutSteps,
                task,
                stepRecordId,
                stepQuery,
                stepParams,
                stepFlow,
                stepFieldValues,
                stepCreateIfMissing,
                stepErrorTarget,
                stepIfExists,
                stepDedupeKey
        );
    }

    private static HumanTaskAst parseHumanTask(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode assignee = node.get("assignee");
        List<HumanTaskAst.Outcome> outcomes = new ArrayList<>();
        JsonNode outcomesNode = node.get("outcomes");
        if (outcomesNode != null && outcomesNode.isArray()) {
            for (JsonNode o : outcomesNode) {
                outcomes.add(new HumanTaskAst.Outcome(readText(o, "value"), readText(o, "label")));
            }
        }
        List<HumanTaskAst.Field> fields = new ArrayList<>();
        JsonNode fieldsNode = node.get("fields");
        if (fieldsNode != null && fieldsNode.isArray()) {
            for (JsonNode f : fieldsNode) {
                JsonNode max = f.get("maxLength");
                fields.add(new HumanTaskAst.Field(readText(f, "name"), readText(f, "type"), readText(f, "label"),
                        f.path("required").asBoolean(false), max != null && max.isInt() ? max.asInt() : null));
            }
        }
        return new HumanTaskAst(readText(node, "title"), readText(node, "description"),
                assignee == null ? null : readText(assignee, "user"),
                assignee == null ? List.of() : parseTextArray(assignee.get("roles")),
                readText(node, "outcomeField"), outcomes, fields, readText(node, "record"),
                node.path("proposal").isObject() ? readText(node.get("proposal"), "value") : null,
                node.path("proposal").isObject() ? readText(node.get("proposal"), "current") : null);
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
            String capabilityUid = readText(cap, "uid");
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
                        List<CapabilityOperationErrorAst> operationErrors = parseCapabilityOperationErrors(
                                op.get("errors"),
                                sourceLabel + "[" + name + "].operations[" + opName + "].errors"
                        );
                        String sideEffects = readText(op, "sideEffects");
                        CapabilityAuthAst operationAuth = parseCapabilityAuth(op.get("auth"));
                        operations.add(new CapabilityOperationAst(
                                opName,
                                input,
                                output,
                                inputSchema,
                                outputSchema,
                                operationPolicy,
                                operationErrors,
                                sideEffects,
                                operationAuth,
                                parseObjectMap(op.get("http"))
                        ));
                    } else {
                        throw new IOException("Capability " + name + " operation must be a string or object");
                    }
                }
            }
            target.add(new CapabilityAst(name, type, specializes, operations,
                    originFor(originByQualifiedMemberName, sourceLabel, name), capabilityUid,
                    parseObjectMap(cap.get("http")),
                    cap.has("inbound") ? parseObjectMap(cap.get("inbound")) : null,
                    readText(cap, "adapter")));
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

    private static List<CapabilityOperationErrorAst> parseCapabilityOperationErrors(
            JsonNode errorsNode,
            String fieldPath
    ) throws IOException {
        List<CapabilityOperationErrorAst> errors = new ArrayList<>();
        if (errorsNode == null || errorsNode.isNull()) {
            return errors;
        }
        if (!errorsNode.isArray()) {
            throw new IOException(fieldPath + " must be an array");
        }
        for (JsonNode errorNode : errorsNode) {
            if (!errorNode.isObject()) {
                throw new IOException(fieldPath + " entries must be objects");
            }
            String errorName = requiredText(errorNode, "name");
            String classification = readText(errorNode, "classification");
            String description = readText(errorNode, "description");
            errors.add(new CapabilityOperationErrorAst(errorName, classification, description));
        }
        return errors;
    }

    private static CapabilityAuthAst parseCapabilityAuth(JsonNode authNode) throws IOException {
        if (authNode == null || authNode.isNull()) {
            return null;
        }
        if (!authNode.isObject()) {
            throw new IOException("Capability operation field 'auth' must be an object when provided");
        }
        List<String> roles = parseTextArray(authNode.get("roles"));
        List<String> scopes = parseTextArray(authNode.get("scopes"));
        return new CapabilityAuthAst(roles, scopes);
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
                readText(node, "currency"),
                readLabelLocales(node, "label"),
                readLabelLocales(node, "shortLabel"),
                readOptionalBoolean(node, "offlineWrite"),
                readOptionalBoolean(node, "selectionTools")
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
            case "set" -> "set";
            case "deleteconcept" -> "deleteConcept";
            case "readconcept" -> "readConcept";
            case "listconcepts" -> "listConcepts";
            case "runquery" -> "runQuery";
            case "callflow" -> "callFlow";
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

