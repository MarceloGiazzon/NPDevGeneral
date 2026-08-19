package com.npdev.dsl.v1.compiled;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CompiledModelCanonicalJsonReader {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CompiledModelCanonicalJsonReader() {
    }

    public static CompiledModel read(Path inputFile) throws IOException {
        return fromJson(Files.readString(inputFile));
    }

    public static CompiledModel fromJson(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        String namespace = text(root, "namespace");
        String dslVersion = defaulted(text(root, "dslVersion"), "1.0.0");
        String version = text(root, "version");

        List<CompiledDomainType> domainTypes = new ArrayList<>();
        for (JsonNode node : array(root, "domainTypes")) {
            domainTypes.add(toDomainType(node));
        }

        Map<String, CompiledConcept> conceptsByName = new LinkedHashMap<>();
        for (JsonNode conceptNode : array(root, "concepts", "entities")) {
            CompiledConcept concept = toConcept(conceptNode);
            conceptsByName.put(concept.getName(), concept);
        }

        List<CompiledCapability> capabilities = new ArrayList<>();
        for (JsonNode node : array(root, "capabilities")) {
            capabilities.add(toCapability(node));
        }

        List<CompiledCapabilityBinding> bindings = new ArrayList<>();
        for (JsonNode node : array(root, "bindings")) {
            bindings.add(new CompiledCapabilityBinding(text(node, "capability"), text(node, "adapter")));
        }

        List<CompiledEvent> events = new ArrayList<>();
        for (JsonNode node : array(root, "events")) {
            events.add(toEvent(node));
        }

        List<CompiledFlow> flows = new ArrayList<>();
        for (JsonNode node : array(root, "flows")) {
            flows.add(toFlow(node));
        }

        List<CompiledOrchestration> orchestrationRules = new ArrayList<>();
        for (JsonNode node : array(root, "orchestrationRules")) {
            orchestrationRules.add(toOrchestration(node));
        }

        List<CompiledQuery> queries = new ArrayList<>();
        for (JsonNode node : array(root, "queries")) {
            queries.add(toQuery(node));
        }

        List<CompiledRuleProfile> ruleProfiles = new ArrayList<>();
        for (JsonNode node : array(root, "ruleProfiles")) {
            ruleProfiles.add(toRuleProfile(node));
        }

        List<CompiledProcedure> procedures = new ArrayList<>();
        for (JsonNode node : array(root, "procedures")) {
            procedures.add(toProcedure(node));
        }

        List<CompiledPanel> panels = new ArrayList<>();
        for (JsonNode node : array(root, "panels")) {
            panels.add(toPanel(node));
        }

        List<CompiledGuidePage> guidePages = new ArrayList<>();
        for (JsonNode node : array(root, "guidePages")) {
            guidePages.add(toGuidePage(node));
        }

        List<CompiledAggregate> aggregates = new ArrayList<>();
        for (JsonNode node : array(root, "aggregates")) {
            aggregates.add(toAggregate(node));
        }

        List<CompiledAutoPanel> autoPanels = new ArrayList<>();
        for (JsonNode node : array(root, "autoPanels")) {
            autoPanels.add(toAutoPanel(node));
        }

        List<CompiledDocument> documents = new ArrayList<>();
        for (JsonNode node : array(root, "documents")) {
            documents.add(toDocument(node));
        }

        CompiledExternalAi externalAi = toExternalAi(root.get("externalAi"));
        CompiledSettings settings = toSettings(root.get("settings"));

        List<CompiledRole> roles = new ArrayList<>();
        for (JsonNode node : array(root, "roles")) {
            roles.add(toRole(node));
        }

        List<CompiledPropertyScope> propertyScopes = new ArrayList<>();
        for (JsonNode node : array(root, "propertyScopes")) {
            propertyScopes.add(toPropertyScope(node));
        }

        List<CompiledProperty> properties = new ArrayList<>();
        for (JsonNode node : array(root, "properties")) {
            properties.add(toProperty(node));
        }

        List<CompiledContext> contexts = new ArrayList<>();
        for (JsonNode node : array(root, "contexts")) {
            contexts.add(toContext(node));
        }

        List<CompiledConversion> conversions = new ArrayList<>();
        for (JsonNode node : array(root, "conversions")) {
            conversions.add(toConversion(node));
        }

        return new CompiledModel(
                namespace,
                dslVersion,
                version,
                conceptsByName,
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
                externalAi,
                settings,
                roles,
                propertyScopes,
                properties,
                contexts,
                conversions
        );
    }

    /** Wave 3 (RC-B1): reads a single app-defined role -> permission-ceiling declaration. */
    private static CompiledRole toRole(JsonNode node) {
        return new CompiledRole(text(node, "name"), toStringList(node.get("grants")), toOrigin(node.get("origin")));
    }

    /** B20 (S2): reads a single declared bounded context (name + $ref). S8 Wave 4: plus the
     *  optional physicallyIsolate flag, absent/false meaning exactly the pre-Wave-4 shape. */
    private static CompiledContext toContext(JsonNode node) {
        return new CompiledContext(
                text(node, "name"),
                text(node, "ref"),
                node.has("physicallyIsolate") && node.get("physicallyIsolate").asBoolean(false)
        );
    }

    /** S7 Phase B (B13): reads a single declared conversion. */
    private static CompiledConversion toConversion(JsonNode node) {
        List<CompiledConversion.CompiledConversionSplitTarget> into = new ArrayList<>();
        for (JsonNode targetNode : array(node, "into")) {
            into.add(new CompiledConversion.CompiledConversionSplitTarget(
                    text(targetNode, "field"), text(targetNode, "take")));
        }
        JsonNode matchNode = node.get("match");
        CompiledConversion.CompiledConversionLookupMatch match = matchNode == null || matchNode.isNull()
                ? null
                : new CompiledConversion.CompiledConversionLookupMatch(
                        text(matchNode, "concept"), text(matchNode, "on"), text(matchNode, "equals"));
        // "with" is a separator LITERAL (e.g. a single space or "") -- optionalText()'s isBlank()
        // collapse would wrongly drop a legitimate " " or "" separator on the round trip, so read it
        // raw instead (same reasoning as JsonModelParser's own "with" parsing).
        JsonNode withNode = node.get("with");
        String with = (withNode != null && !withNode.isNull()) ? withNode.asText() : null;
        return new CompiledConversion(
                text(node, "id"),
                text(node, "concept"),
                text(node, "op"),
                optionalText(node, "from"),
                optionalText(node, "to"),
                into,
                match,
                optionalText(node, "set"),
                toStringList(node.get("mergeFrom")),
                with
        );
    }

    /** Wave 6 (RC-A1): reads a single declared property-cascade scope level. */
    private static CompiledPropertyScope toPropertyScope(JsonNode node) {
        return new CompiledPropertyScope(text(node, "name"), optionalText(node, "from"));
    }

    /** Wave 6 (RC-A1): reads a single declared runtime property. */
    private static CompiledProperty toProperty(JsonNode node) {
        return new CompiledProperty(
                text(node, "name"),
                text(node, "type"),
                toDefaultValue(node.get("default")),
                toStringList(node.get("settableAt")),
                optionalText(node, "label"),
                node.has("securityRelevant") && node.get("securityRelevant").asBoolean(false)
        );
    }

    /** ADR-0009: reads the optional app-level externalAi block; null if absent. */
    private static CompiledExternalAi toExternalAi(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return new CompiledExternalAi(optionalText(node, "egress"), toStringList(node.get("vendors")));
    }

    /** Move 6 Move A: reads the app-level settings block; platform defaults if absent (the writer
     * always emits the FULL merged catalogue, so a round-tripped read needs no re-merge here -- the
     * ids happen to already equal the platform defaults for an app that overrode nothing). */
    private static CompiledSettings toSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return CompiledSettings.defaults();
        }
        JsonNode uiNode = node.get("ui");
        Integer pageRows = uiNode == null ? null : optionalIntegerObject(uiNode.get("pageRows"));
        String dateFormat = uiNode == null ? null : optionalText(uiNode, "dateFormat");
        return new CompiledSettings(
                optionalText(node, "locale"), toStringMap(node.get("strings")), pageRows, dateFormat);
    }

    private static CompiledDocument toDocument(JsonNode node) {
        return new CompiledDocument(
                text(node, "name"),
                text(node, "concept"),
                optionalText(node, "title"),
                optionalText(node, "pageSize"),
                optionalDoubleObject(node.get("marginMm")),
                toObjectMap(node.get("metadata"))
        );
    }

    private static CompiledAutoPanel toAutoPanel(JsonNode node) {
        return new CompiledAutoPanel(
                optionalText(node, "name"),
                optionalText(node, "concept"),
                optionalText(node, "aggregate"),
                optionalText(node, "route"),
                toStringList(node.get("surfaces")),
                toAutoPanelSurface(node.get("selection")),
                toAutoPanelSurface(node.get("detail")),
                toAutoPanelSurface(node.get("transaction")),
                toAutoPanelSurface(node.get("prompt")),
                toObjectMap(node.get("metadata"))
        );
    }

    private static CompiledAutoPanelSurface toAutoPanelSurface(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        List<CompiledAutoPanelComputed> computed = new ArrayList<>();
        JsonNode computedNode = node.get("computed");
        if (computedNode != null && computedNode.isArray()) {
            for (JsonNode c : computedNode) {
                computed.add(new CompiledAutoPanelComputed(optionalText(c, "col"), optionalText(c, "expr")));
            }
        }
        return new CompiledAutoPanelSurface(
                toStringList(node.get("filters")),
                toStringList(node.get("columns")),
                toStringList(node.get("fields")),
                computed,
                optionalText(node, "labelField"),
                toObjectMap(node.get("metadata")),
                toTransactionHooks(node.get("hooks")),
                toDerivedFields(node.get("derivedFields")),
                toRegions(node.get("regions")),
                toWorkbenchActions(node.get("actions")),
                toStringMap(node.get("visibleWhen")),
                toBandPickers(node.get("bandPickers")),
                toAutoPanelDataSource(node.get("dataSource")),
                toUiState(node.get("uiState"))
        );
    }

    /** Move 11 W6: reads transaction.uiState -- the reader half of R0.3 (writer AND reader). */
    private static Map<String, CompiledUiStateControl> toUiState(JsonNode node) {
        Map<String, CompiledUiStateControl> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode controlNode = node.get(name);
            out.put(name, new CompiledUiStateControl(
                    optionalText(controlNode, "name"),
                    optionalText(controlNode, "label"),
                    toStringList(controlNode.get("values")),
                    optionalText(controlNode, "default")
            ));
        }
        return out;
    }

    /** Move 8 D3 (item G6): reads a surface's dataSource.procedure declaration, or null if absent. */
    private static CompiledAutoPanelDataSource toAutoPanelDataSource(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return new CompiledAutoPanelDataSource(optionalText(node, "procedure"));
    }

    /** Move 7 W1: reads transaction.actions, typed replacement for metadata.actions. */
    private static List<CompiledWorkbenchAction> toWorkbenchActions(JsonNode node) {
        List<CompiledWorkbenchAction> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode actionNode : node) {
            out.add(new CompiledWorkbenchAction(
                    optionalText(actionNode, "procedure"),
                    optionalText(actionNode, "label"),
                    toStringList(actionNode.get("inputFields")),
                    toWorkbenchActionApplyTo(actionNode.get("applyTo")),
                    optionalText(actionNode, "afterAction"),
                    optionalText(actionNode, "visibleWhen")));
        }
        return out;
    }

    private static CompiledWorkbenchActionApplyTo toWorkbenchActionApplyTo(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return new CompiledWorkbenchActionApplyTo(
                optionalText(node, "collection"),
                optionalText(node, "mode"),
                toStringMap(node.get("map")));
    }

    /** Move 7 W1: reads transaction.bandPickers, typed replacement for metadata.bandPickers. */
    private static Map<String, CompiledWorkbenchBandPicker> toBandPickers(JsonNode node) {
        Map<String, CompiledWorkbenchBandPicker> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        node.fields().forEachRemaining(entry -> {
            JsonNode pickerNode = entry.getValue();
            out.put(entry.getKey(), new CompiledWorkbenchBandPicker(
                    optionalText(pickerNode, "panel"),
                    optionalText(pickerNode, "label"),
                    toStringList(pickerNode.get("columns")),
                    optionalText(pickerNode, "filter"),
                    booleanValue(pickerNode, "multiSelect")));
        });
        return out;
    }

    /** Move 6 Move D: reads transaction.regions, an object keyed by derived region address. */
    private static Map<String, CompiledRegionMount> toRegions(JsonNode node) {
        Map<String, CompiledRegionMount> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        node.fields().forEachRemaining(entry -> {
            JsonNode regionNode = entry.getValue();
            out.put(entry.getKey(), new CompiledRegionMount(
                    defaulted(optionalText(regionNode, "render"), "generated"),
                    optionalText(regionNode, "component")));
        });
        return out;
    }

    /** Move 6 Move B: reads the closed-enum transaction.hooks block; null if absent. */
    private static CompiledTransactionHooks toTransactionHooks(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return new CompiledTransactionHooks(
                optionalText(node, "onLoad"),
                optionalText(node, "onFieldChange"),
                optionalText(node, "beforeAction"),
                optionalText(node, "onValidate"),
                optionalText(node, "onCommit"));
    }

    /** Move 6 Move B: reads transaction.derivedFields, an object keyed by field name. */
    private static List<CompiledDerivedField> toDerivedFields(JsonNode node) {
        List<CompiledDerivedField> out = new ArrayList<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        node.fields().forEachRemaining(entry -> {
            JsonNode fieldNode = entry.getValue();
            out.add(new CompiledDerivedField(
                    entry.getKey(),
                    optionalText(fieldNode, "label"),
                    defaulted(optionalText(fieldNode, "tier"), "client"),
                    optionalText(fieldNode, "expression"),
                    optionalText(fieldNode, "procedure")));
        });
        return out;
    }

    private static CompiledAggregate toAggregate(JsonNode node) {
        return new CompiledAggregate(
                text(node, "name"),
                text(node, "root"),
                toAggregateCollections(node.get("collections")),
                optionalText(node, "onCommit"),
                toObjectMap(node.get("metadata")),
                optionalText(node, "onValidate")
        );
    }

    private static List<CompiledAggregateCollection> toAggregateCollections(JsonNode node) {
        List<CompiledAggregateCollection> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode collectionNode : node) {
            out.add(new CompiledAggregateCollection(
                    text(collectionNode, "name"),
                    text(collectionNode, "concept"),
                    optionalText(collectionNode, "via"),
                    text(collectionNode, "childField"),
                    optionalText(collectionNode, "ownership"),
                    optionalText(collectionNode, "orderBy"),
                    toAggregateCollections(collectionNode.get("collections")),
                    toObjectMap(collectionNode.get("metadata"))
            ));
        }
        return out;
    }

    private static CompiledDomainType toDomainType(JsonNode node) {
        return new CompiledDomainType(
                text(node, "name"),
                text(node, "baseType"),
                optionalText(node, "javaType"),
                toSchema(node.get("validationSchema")),
                toStringList(node.get("normalizationRules")),
                optionalText(node, "formatHint"),
                toStringList(node.get("examples")),
                toDomainTypeUi(node.get("ui")),
                toOrigin(node.get("origin"))
        );
    }

    private static CompiledConcept toConcept(JsonNode node) {
        List<CompiledField> fields = new ArrayList<>();
        for (JsonNode fieldNode : array(node, "fields")) {
            fields.add(toField(fieldNode));
        }

        List<String> expressionInvariants = toStringList(node.get("expressionInvariants"));

        List<CompiledInvariant> invariants = new ArrayList<>();
        for (JsonNode invariantNode : array(node, "invariants")) {
            List<String> invariantFields = toStringList(invariantNode.get("fields"));
            invariants.add(new CompiledInvariant(
                    optionalText(invariantNode, "ref"),
                    optionalText(invariantNode, "type"),
                    optionalText(invariantNode, "field"),
                    optionalText(invariantNode, "expression"),
                    // REG-97: NO back-fill. This used to read
                    //     invariantFields.isEmpty() && field != null ? List.of(field) : invariantFields
                    // which invented content the document did not contain: the compiler emits
                    // `"fields": []` for a single-field `required` invariant, and reading it back
                    // produced `["id"]`, so toJson(fromJson(toJson(m))) != toJson(m).
                    //
                    // "Canonical" is load-bearing in two places that assume a byte-stable form --
                    // npdev-generated/ is hash-verified at startup, and npdev.schema.fingerprint is
                    // an equality-over-canonical-form argument -- so a form whose value depends on
                    // round-trip count can produce a spurious hash mismatch or a spurious
                    // schema-impact prompt (a migration that appears necessary and is not).
                    //
                    // The READER is the side that changed, deliberately: a parser must be faithful
                    // and never invent. Fixing the COMPILER instead (emitting ["id"]) would have
                    // been equally idempotent but would change the canonical content of EVERY
                    // model, i.e. change every fingerprint -- causing exactly the spurious
                    // migration prompt this item is about. Callers already receive an empty list
                    // from a freshly compiled model, so nothing that works today starts failing.
                    invariantFields
            ));
        }

        return new CompiledConcept(
                text(node, "name"),
                text(node, "className"),
                text(node, "tableName"),
                fields,
                expressionInvariants,
                invariants,
                toLifecycle(node.get("lifecycle")),
                toPresentationMetadata(node.get("ui")),
                optionalText(node, "truthLevel"),
                optionalText(node, "module"),
                toIndexes(node.get("indexes")),
                toConceptAccess(node.get("access")),
                optionalText(node, "renamedFrom"),
                optionalText(node, "satelliteOf"),
                toOrigin(node.get("origin"))
        );
    }

    /**
     * LNCH-1 P0.2 (found by the reflective CanonicalJsonRoundTripCompletenessTest ratchet): this
     * reader previously passed {@code List.of()} for every concept's indexes unconditionally (the
     * writer never emitted the key either), so a concept's author-declared secondary indexes
     * (LNCH-6) never survived the compiled-model.json round trip.
     */
    private static List<CompiledIndex> toIndexes(JsonNode node) {
        List<CompiledIndex> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode indexNode : node) {
            out.add(new CompiledIndex(
                    optionalText(indexNode, "name"),
                    toStringList(indexNode.get("fields")),
                    booleanValue(indexNode, "unique")
            ));
        }
        return out;
    }

    /** LNCH-13: row-level authorization rule (access: {read, write}). */
    private static CompiledConceptAccess toConceptAccess(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return new CompiledConceptAccess(optionalText(node, "read"), optionalText(node, "write"));
    }

    /** PACK-2 (ledger; PACK-ROADMAP.md card PK-1 steps 5-7): reads {@code origin:
     *  {packId, packVersion, packDigest, sealed}}; null for an app's own root- or context-declared
     *  member, same "absent key -> null" convention {@link #toConceptAccess} just above uses. */
    private static CompiledOrigin toOrigin(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return new CompiledOrigin(
                optionalText(node, "packId"),
                optionalText(node, "packVersion"),
                optionalText(node, "packDigest"),
                booleanValue(node, "sealed")
        );
    }

    private static CompiledField toField(JsonNode node) {
        return new CompiledField(
                text(node, "name"),
                text(node, "dslType"),
                text(node, "javaType"),
                booleanValue(node, "id"),
                booleanValue(node, "required"),
                booleanValue(node, "unique"),
                toStringList(node.get("enumValues")),
                optionalText(node, "referenceTarget"),
                toReferenceSemantics(node.get("referenceSemantics")),
                optionalText(node, "domainType"),
                toSchema(node.get("schema")),
                toEnumOptions(node.get("enumOptions")),
                toPresentationMetadata(node.get("ui")),
                optionalText(node, "connectable"),
                optionalText(node, "renamedFrom"),
                toFileMetadata(node.get("file")),
                booleanValue(node, "sensitive"),
                toFieldPicker(node.get("picker"))
        );
    }

    /** B16/B19 (Move 9 A3): reads a field's declared picker filter/multiSelect. */
    private static CompiledFieldPicker toFieldPicker(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return new CompiledFieldPicker(optionalText(node, "filter"), booleanValue(node, "multiSelect"));
    }

    /**
     * HARDEN-OBJSTORE: this reader previously fell back to the 15-arg {@link CompiledField}
     * constructor (which always sets {@code file} to null), so a file field's
     * contentTypes/maxSizeBytes/multiple constraints never survived the compiled-model.json
     * round-trip -- silently defeating {@code FileUploadController}'s upload-time validation in
     * every generated app.
     */
    private static CompiledFileMetadata toFileMetadata(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return new CompiledFileMetadata(
                toStringList(node.get("contentTypes")),
                optionalLongObject(node.get("maxSizeBytes")),
                booleanValue(node, "multiple")
        );
    }

    private static CompiledPresentationMetadata toPresentationMetadata(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return new CompiledPresentationMetadata(
                optionalText(node, "label"),
                optionalText(node, "shortLabel"),
                optionalText(node, "description"),
                optionalText(node, "helpText"),
                optionalText(node, "placeholder"),
                optionalText(node, "group"),
                optionalText(node, "section"),
                optionalIntegerObject(node.get("order")),
                optionalBooleanObject(node.get("advanced")),
                optionalBooleanObject(node.get("deprecated")),
                toStringList(node.get("examples")),
                optionalText(node, "widget"),
                optionalText(node, "visibleWhen"),
                optionalText(node, "enabledWhen"),
                optionalText(node, "readonlyWhen"),
                optionalText(node, "requiredWhen"),
                optionalText(node, "pickerType"),
                optionalBooleanObject(node.get("allowInlineCreate")),
                toStringList(node.get("searchFields")),
                optionalText(node, "filterPreset"),
                optionalText(node, "tab"),
                optionalIntegerObject(node.get("column")),
                optionalIntegerObject(node.get("columnSpan")),
                optionalText(node, "width"),
                optionalBooleanObject(node.get("summaryCard")),
                optionalBooleanObject(node.get("listColumn")),
                optionalBooleanObject(node.get("showInDefaultWebUi")),
                optionalIntegerObject(node.get("listColumnOrder")),
                optionalIntegerObject(node.get("formColumns")),
                optionalText(node, "displayMode"),
                optionalText(node, "formPresentation"),
                optionalText(node, "defaultSort"),
                optionalText(node, "defaultGroup"),
                optionalText(node, "imageField"),
                optionalText(node, "customWidgetRef")
        );
    }

    private static List<CompiledEnumOption> toEnumOptions(JsonNode node) {
        List<CompiledEnumOption> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode item : node) {
            if (item == null || item.isNull() || item.isMissingNode()) {
                continue;
            }
            out.add(new CompiledEnumOption(
                    optionalText(item, "value"),
                    optionalText(item, "label"),
                    optionalIntegerObject(item.get("order")),
                    optionalText(item, "group"),
                    booleanValue(item, "default"),
                    booleanValue(item, "deprecated"),
                    optionalText(item, "iconHint"),
                    optionalText(item, "badgeHint"),
                    optionalText(item, "description")
            ));
        }
        return out;
    }

    private static CompiledReferenceSemantics toReferenceSemantics(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return new CompiledReferenceSemantics(
                optionalText(node, "target"),
                booleanValue(node, "multiple"),
                optionalText(node, "displayField"),
                toStringList(node.get("searchFields")),
                toStringList(node.get("previewFields")),
                optionalText(node, "inlineCreate"),
                optionalText(node, "displayTemplate"),
                toStringList(node.get("pickerColumns")),
                optionalText(node, "previewCardTemplate"),
                firstNonBlank(optionalText(node, "defaultFilter"), optionalText(node, "defaultFilterBehavior")),
                optionalText(node, "via"),
                optionalText(node, "onDelete")
        );
    }

    private static CompiledDomainTypeUi toDomainTypeUi(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return new CompiledDomainTypeUi(
                optionalText(node, "label"),
                optionalText(node, "placeholder"),
                optionalText(node, "helpText"),
                optionalText(node, "widget")
        );
    }

    private static CompiledLifecycle toLifecycle(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        List<CompiledStateMachineState> states = new ArrayList<>();
        for (JsonNode stateNode : array(node, "states")) {
            states.add(new CompiledStateMachineState(
                    optionalText(stateNode, "value"),
                    optionalText(stateNode, "label"),
                    optionalBoolean(stateNode.get("initial")) != null && optionalBoolean(stateNode.get("initial")),
                    optionalBoolean(stateNode.get("terminal")) != null && optionalBoolean(stateNode.get("terminal")),
                    toStringMap(stateNode.get("metadata"))
            ));
        }
        List<CompiledStateTransition> transitions = new ArrayList<>();
        for (JsonNode transitionNode : array(node, "transitions")) {
            List<String> requiredPayload = toStringList(transitionNode.get("requiredPayload"));
            if (requiredPayload.isEmpty()) {
                requiredPayload = toStringList(transitionNode.get("requires"));
            }
            transitions.add(new CompiledStateTransition(
                    optionalText(transitionNode, "from"),
                    optionalText(transitionNode, "to"),
                    requiredPayload,
                    optionalText(transitionNode, "event"),
                    optionalText(transitionNode, "guard"),
                    optionalText(transitionNode, "actionLabel"),
                    toStringMap(transitionNode.get("metadata")),
                    toActionMetadata(transitionNode.get("action"))
            ));
        }
        return new CompiledLifecycle(optionalText(node, "statusField"), states, transitions);
    }

    private static CompiledCapability toCapability(JsonNode node) {
        List<CompiledCapabilityOperation> operations = new ArrayList<>();
        for (JsonNode operationNode : array(node, "operations")) {
            operations.add(new CompiledCapabilityOperation(
                    text(operationNode, "name"),
                    toStringList(operationNode.get("input")),
                    toStringList(operationNode.get("output")),
                    toSchema(operationNode.get("inputSchema")),
                    toSchema(operationNode.get("outputSchema")),
                    toExecutionPolicy(operationNode.get("executionPolicy"))
            ));
        }
        return new CompiledCapability(text(node, "name"), optionalText(node, "type"), operations, toOrigin(node.get("origin")));
    }

    private static CompiledEvent toEvent(JsonNode node) {
        List<CompiledEventField> payload = new ArrayList<>();
        for (JsonNode payloadNode : array(node, "payload")) {
            payload.add(new CompiledEventField(text(payloadNode, "name"), text(payloadNode, "type")));
        }
        return new CompiledEvent(text(node, "name"), optionalText(node, "conceptName"), payload,
                optionalText(node, "triggerMode"), toOrigin(node.get("origin")));
    }

    private static CompiledFlow toFlow(JsonNode node) {
        List<CompiledFlowStep> steps = new ArrayList<>();
        for (JsonNode stepNode : array(node, "steps")) {
            steps.add(toFlowStep(stepNode));
        }
        return new CompiledFlow(
                text(node, "name"),
                text(node, "concept"),
                optionalText(node, "mode"),
                steps,
                toSchema(node.get("inputSchema")),
                toSchema(node.get("outputSchema")),
                toActionMetadata(node.get("action")),
                booleanValue(node, "startEndpoint"),
                toFlowSchedule(node.get("schedule")),
                toOrigin(node.get("origin"))
        );
    }

    private static CompiledFlowSchedule toFlowSchedule(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return new CompiledFlowSchedule(text(node, "cron"), toStringList(node.get("tenantScope")));
    }

    private static CompiledFlowStep toFlowStep(JsonNode node) {
        List<CompiledFlowStep> thenSteps = new ArrayList<>();
        for (JsonNode stepNode : array(node, "thenSteps")) {
            thenSteps.add(toFlowStep(stepNode));
        }

        List<CompiledFlowStep> elseSteps = new ArrayList<>();
        for (JsonNode stepNode : array(node, "elseSteps")) {
            elseSteps.add(toFlowStep(stepNode));
        }

        // LNCH-17 (found while adding onFailureSteps): loopSteps was never read here either,
        // matching the writer-side gap fixed in CompiledModelCanonicalJson -- see that fix's
        // comment for the bug-class context.
        List<CompiledFlowStep> loopSteps = new ArrayList<>();
        for (JsonNode stepNode : array(node, "loopSteps")) {
            loopSteps.add(toFlowStep(stepNode));
        }
        List<CompiledFlowStep> onFailureSteps = new ArrayList<>();
        for (JsonNode stepNode : array(node, "onFailureSteps")) {
            onFailureSteps.add(toFlowStep(stepNode));
        }
        // R2.5: mirrors the writer's onTimeoutSteps -- see CompiledModelCanonicalJson.toFlowSteps.
        List<CompiledFlowStep> onTimeoutSteps = new ArrayList<>();
        for (JsonNode stepNode : array(node, "onTimeoutSteps")) {
            onTimeoutSteps.add(toFlowStep(stepNode));
        }

        return new CompiledFlowStep(
                text(node, "name"),
                text(node, "type"),
                optionalText(node, "checkpoint"),
                optionalText(node, "scope"),
                toStringList(node.get("invariants")),
                optionalText(node, "eventName"),
                optionalText(node, "payloadRef"),
                toStringMap(node.get("eventDataRefs")),
                optionalText(node, "condition"),
                thenSteps,
                elseSteps,
                optionalText(node, "awaitEventName"),
                optionalText(node, "awaitRef"),
                optionalBoolean(node.get("awaitMatchCorrelation")),
                toStringMap(node.get("awaitPayloadMatch")),
                optionalLongObject(node.get("delaySeconds")),
                optionalText(node, "mapFromRef"),
                optionalText(node, "mapToRef"),
                optionalText(node, "returnValueRef"),
                toCapabilityCall(node.get("capabilityCall")),
                toActionMetadata(node.get("action")),
                optionalText(node, "generatedActionName"),
                optionalText(node, "collectionRef"),
                optionalText(node, "itemKey"),
                loopSteps,
                optionalIntegerObject(node.get("maxLoopIterations")),
                onFailureSteps,
                optionalText(node, "procedureName"),
                optionalBoolean(node.get("parallelAwait")),
                optionalLongObject(node.get("timeoutSeconds")),
                onTimeoutSteps
        );
    }

    private static CompiledCapabilityCall toCapabilityCall(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return new CompiledCapabilityCall(
                optionalText(node, "capabilityName"),
                optionalText(node, "capabilityType"),
                optionalText(node, "adapterId"),
                optionalText(node, "operation"),
                toStringList(node.get("argsRefs")),
                optionalText(node, "inputRef"),
                optionalText(node, "outputRef"),
                toSchema(node.get("inputSchema")),
                toSchema(node.get("outputSchema")),
                toExecutionPolicy(node.get("executionPolicy"))
        );
    }

    private static CompiledCapabilityExecutionPolicy toExecutionPolicy(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return CompiledCapabilityExecutionPolicy.defaults();
        }
        return new CompiledCapabilityExecutionPolicy(
                intValue(node, "retryCount", 1),
                longValue(node, "retryDelayMs", 0L),
                longValue(node, "timeoutMs", 0L),
                intValue(node, "circuitOpenAfterFailures", 0),
                longValue(node, "circuitOpenMs", 0L),
                intValue(node, "bulkheadMaxConcurrent", 0),
                optionalText(node, "idempotencyKeyField"),
                optionalText(node, "failureClassification")
        );
    }

    private static CompiledOrchestration toOrchestration(JsonNode node) {
        CompiledOrchestrationTrigger trigger = null;
        JsonNode triggerNode = node.get("trigger");
        if (triggerNode != null && !triggerNode.isNull() && !triggerNode.isMissingNode()) {
            String triggerType = optionalText(triggerNode, "type");
            String triggerEvent = optionalText(triggerNode, "event");
            if (triggerType != null || triggerEvent != null) {
                trigger = new CompiledOrchestrationTrigger(triggerType, triggerEvent);
            }
        }

        List<CompiledOrchestrationAction> actions = new ArrayList<>();
        for (JsonNode actionNode : array(node, "actions")) {
            CompiledOrchestrationAction action = toOrchestrationAction(actionNode);
            if (action != null) {
                actions.add(action);
            }
        }
        if (actions.isEmpty()) {
            CompiledOrchestrationAction primary = toOrchestrationAction(node.get("action"));
            if (primary != null) {
                actions.add(primary);
            }
        }

        return new CompiledOrchestration(
                text(node, "name"),
                optionalText(node, "condition"),
                trigger,
                actions
        );
    }

    private static CompiledOrchestrationAction toOrchestrationAction(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String type = optionalText(node, "type");
        String concept = optionalText(node, "concept");
        String capability = optionalText(node, "capability");
        String operation = optionalText(node, "operation");
        String event = optionalText(node, "event");
        Long delaySeconds = optionalLongObject(node.get("delaySeconds"));
        Map<String, String> map = toStringMap(node.get("map"));
        if (type == null && concept == null && capability == null && operation == null && event == null && delaySeconds == null && map.isEmpty()) {
            return null;
        }
        return new CompiledOrchestrationAction(
                type,
                concept,
                capability,
                operation,
                event,
                delaySeconds,
                map,
                toActionMetadata(node.get("action"))
        );
    }

    private static CompiledQuery toQuery(JsonNode node) {
        return new CompiledQuery(
                text(node, "name"),
                text(node, "concept"),
                optionalText(node, "where"),
                toStringList(node.get("orderBy")),
                optionalIntegerObject(node.get("limit")),
                toProcedureParameters(node.get("parameters")),
                toStringList(node.get("permissionRequirements")),
                optionalText(node, "tracePolicy"),
                optionalText(node, "auditPolicy"),
                toObjectMap(node.get("metadata")),
                toGroupByFields(node),
                toAggregateFunctions(node),
                optionalText(node, "having"),
                toOrigin(node.get("origin"))
        );
    }

    /** Move 10 B1: reads query.groupBy[] -- the canonical form is always the object shape. */
    private static List<CompiledGroupByField> toGroupByFields(JsonNode queryNode) {
        List<CompiledGroupByField> out = new ArrayList<>();
        for (JsonNode entry : array(queryNode, "groupBy")) {
            out.add(new CompiledGroupByField(text(entry, "field"), optionalText(entry, "bucket")));
        }
        return out;
    }

    /** Move 10 B1: reads query.aggregates[]. */
    private static List<CompiledAggregateFunction> toAggregateFunctions(JsonNode queryNode) {
        List<CompiledAggregateFunction> out = new ArrayList<>();
        for (JsonNode entry : array(queryNode, "aggregates")) {
            out.add(new CompiledAggregateFunction(
                    text(entry, "name"), text(entry, "fn"), optionalText(entry, "field")));
        }
        return out;
    }

    private static CompiledRuleProfile toRuleProfile(JsonNode node) {
        Boolean enabled = optionalBooleanObject(node.get("enabled"));
        return new CompiledRuleProfile(
                text(node, "name"),
                optionalText(node, "description"),
                toStringList(node.get("appliesTo")),
                enabled == null || enabled,
                toObjectMap(node.get("metadata"))
        );
    }

    private static CompiledProcedure toProcedure(JsonNode node) {
        return new CompiledProcedure(
                text(node, "name"),
                optionalText(node, "description"),
                toProcedureParameters(node.get("parameters")),
                toProcedureVariables(node.get("variables")),
                toProcedureSteps(node.get("steps")),
                toSchema(node.get("returns")),
                toStringList(node.get("permissionRequirements")),
                optionalText(node, "tracePolicy"),
                optionalText(node, "auditPolicy"),
                toGeneratedActionDescriptor(node.get("actionDescriptor")),
                toObjectMap(node.get("metadata"))
        );
    }

    private static CompiledGeneratedActionDescriptorSpec toGeneratedActionDescriptor(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject() || node.isEmpty()) {
            return null;
        }
        return new CompiledGeneratedActionDescriptorSpec(
                optionalText(node, "actionName"),
                toStringList(node.get("affectedConcepts")),
                optionalText(node, "sideEffectConcept"),
                optionalText(node, "eventNameOnSuccess"),
                optionalText(node, "auditResourceType"),
                optionalText(node, "idempotencyPolicy"),
                optionalText(node, "tracePolicy"),
                optionalText(node, "correlationPolicy"),
                booleanValue(node, "explicit")
        );
    }

    private static List<CompiledProcedureParameter> toProcedureParameters(JsonNode node) {
        List<CompiledProcedureParameter> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode parameterNode : node) {
            out.add(new CompiledProcedureParameter(
                    text(parameterNode, "name"),
                    text(parameterNode, "type"),
                    booleanValue(parameterNode, "required"),
                    toSchema(parameterNode.get("schema")),
                    optionalText(parameterNode, "description")
            ));
        }
        return out;
    }

    private static List<CompiledProcedureVariable> toProcedureVariables(JsonNode node) {
        List<CompiledProcedureVariable> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode variableNode : node) {
            out.add(new CompiledProcedureVariable(
                    text(variableNode, "name"),
                    optionalText(variableNode, "type"),
                    toSchema(variableNode.get("schema")),
                    toObjectValue(variableNode.get("initialValue"))
            ));
        }
        return out;
    }

    private static List<CompiledProcedureStep> toProcedureSteps(JsonNode node) {
        List<CompiledProcedureStep> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode stepNode : node) {
            out.add(new CompiledProcedureStep(
                    optionalText(stepNode, "name"),
                    text(stepNode, "type"),
                    optionalText(stepNode, "target"),
                    toObjectValue(stepNode.get("value")),
                    optionalText(stepNode, "condition"),
                    optionalText(stepNode, "items"),
                    optionalText(stepNode, "as"),
                    optionalText(stepNode, "concept"),
                    optionalText(stepNode, "query"),
                    toObjectMap(stepNode.get("data")),
                    optionalText(stepNode, "id"),
                    optionalText(stepNode, "procedure"),
                    optionalText(stepNode, "flow"),
                    optionalText(stepNode, "capability"),
                    optionalText(stepNode, "operation"),
                    optionalText(stepNode, "event"),
                    toObjectMap(stepNode.get("args")),
                    toProcedureSteps(stepNode.get("thenSteps")),
                    toProcedureSteps(stepNode.get("elseSteps")),
                    toProcedureSteps(stepNode.get("steps")),
                    optionalBooleanObject(stepNode.get("trace")),
                    optionalBooleanObject(stepNode.get("audit")),
                    toObjectMap(stepNode.get("metadata")),
                    toObjectMap(stepNode.get("set")),
                    optionalBooleanObject(stepNode.get("createIfMissing")),
                    toObjectMap(stepNode.get("select")),
                    toObjectValue(stepNode.get("left")),
                    toObjectValue(stepNode.get("right"))
            ));
        }
        return out;
    }

    private static CompiledPanel toPanel(JsonNode node) {
        return new CompiledPanel(
                text(node, "name"),
                text(node, "route"),
                optionalText(node, "title"),
                toPanelDataSources(node.get("dataSources")),
                toPanelLayout(node.get("layout")),
                toPanelFieldBindings(node.get("fieldBindings")),
                optionalText(node, "visibility"),
                optionalText(node, "enabledWhen"),
                toPanelActions(node.get("actions")),
                toObjectMap(node.get("explainability")),
                toObjectMap(node.get("metadata")),
                optionalText(node, "guidePage"),
                toOrigin(node.get("origin"))
        );
    }

    private static CompiledGuidePage toGuidePage(JsonNode node) {
        return new CompiledGuidePage(
                text(node, "name"),
                booleanValue(node, "default"),
                toGuidePageRegions(node.get("regions")),
                toGuidePageTheme(node.get("theme")),
                toGuidePageGadgets(node.get("gadgets"))
        );
    }

    private static CompiledGuidePageRegions toGuidePageRegions(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        return new CompiledGuidePageRegions(
                booleanValue(node, "top"),
                toGuidePageRegion(node.get("left")),
                toGuidePageRegion(node.get("right"))
        );
    }

    private static CompiledGuidePageRegion toGuidePageRegion(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        return new CompiledGuidePageRegion(
                booleanValue(node, "enabled"),
                booleanValue(node, "collapsible"),
                booleanValue(node, "defaultCollapsed"),
                intValue(node, "width", 0)
        );
    }

    private static CompiledGuidePageTheme toGuidePageTheme(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        return new CompiledGuidePageTheme(
                optionalText(node, "mode"),
                optionalText(node, "accent"),
                optionalText(node, "density"),
                optionalText(node, "logoText"),
                optionalText(node, "logoUrl")
        );
    }

    private static List<CompiledGuidePageGadget> toGuidePageGadgets(JsonNode node) {
        List<CompiledGuidePageGadget> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode gadgetNode : node) {
            out.add(new CompiledGuidePageGadget(
                    text(gadgetNode, "name"),
                    text(gadgetNode, "type"),
                    optionalText(gadgetNode, "title"),
                    optionalText(gadgetNode, "query"),
                    optionalText(gadgetNode, "x"),
                    optionalText(gadgetNode, "y"),
                    optionalText(gadgetNode, "series")
            ));
        }
        return out;
    }

    private static List<CompiledPanelDataSource> toPanelDataSources(JsonNode node) {
        List<CompiledPanelDataSource> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode dataSourceNode : node) {
            out.add(new CompiledPanelDataSource(
                    text(dataSourceNode, "name"),
                    optionalText(dataSourceNode, "concept"),
                    optionalText(dataSourceNode, "query"),
                    optionalText(dataSourceNode, "procedure"),
                    toObjectMap(dataSourceNode.get("params")),
                    optionalText(dataSourceNode, "parentDataSource"),
                    optionalText(dataSourceNode, "parentField"),
                    optionalText(dataSourceNode, "childField"),
                    toStringList(dataSourceNode.get("rowOps")),
                    toStringList(dataSourceNode.get("addFormFields")),
                    optionalText(dataSourceNode, "onRowLoad")
            ));
        }
        return out;
    }

    private static CompiledPanelLayout toPanelLayout(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        List<CompiledPanelLayout> children = new ArrayList<>();
        for (JsonNode childNode : array(node, "children")) {
            CompiledPanelLayout child = toPanelLayout(childNode);
            if (child != null) {
                children.add(child);
            }
        }
        return new CompiledPanelLayout(
                text(node, "type"),
                children,
                toStringList(node.get("fields")),
                toObjectMap(node.get("metadata"))
        );
    }

    private static List<CompiledPanelFieldBinding> toPanelFieldBindings(JsonNode node) {
        List<CompiledPanelFieldBinding> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode bindingNode : node) {
            out.add(new CompiledPanelFieldBinding(
                    text(bindingNode, "field"),
                    optionalText(bindingNode, "source"),
                    optionalText(bindingNode, "visibleWhen"),
                    optionalText(bindingNode, "enabledWhen"),
                    optionalText(bindingNode, "readonlyWhen"),
                    toPresentationMetadata(bindingNode.get("ui")),
                    booleanValue(bindingNode, "editable")
            ));
        }
        return out;
    }

    private static List<CompiledPanelAction> toPanelActions(JsonNode node) {
        List<CompiledPanelAction> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode actionNode : node) {
            out.add(new CompiledPanelAction(
                    text(actionNode, "name"),
                    optionalText(actionNode, "label"),
                    text(actionNode, "binding"),
                    optionalText(actionNode, "concept"),
                    optionalText(actionNode, "operation"),
                    optionalText(actionNode, "procedure"),
                    optionalText(actionNode, "flow"),
                    optionalText(actionNode, "visibleWhen"),
                    optionalText(actionNode, "enabledWhen"),
                    toStringList(actionNode.get("permissionRequirements")),
                    toObjectMap(actionNode.get("explainability")),
                    toObjectMap(actionNode.get("metadata")),
                    optionalText(actionNode, "scope"),
                    optionalText(actionNode, "dataSource"),
                    toStringList(actionNode.get("inputFields")),
                    optionalText(actionNode, "resultAs"),
                    optionalText(actionNode, "filename"),
                    optionalText(actionNode, "contentType")
            ));
        }
        return out;
    }

    private static CompiledActionMetadata toActionMetadata(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isObject()) {
            return null;
        }
        return new CompiledActionMetadata(
                optionalText(node, "label"),
                optionalText(node, "confirmationText"),
                optionalText(node, "successMessage"),
                optionalText(node, "failureHint"),
                optionalText(node, "dangerLevel"),
                optionalText(node, "visibleWhen"),
                optionalText(node, "permissionHint"),
                optionalText(node, "inputFormHint")
        );
    }

    private static CompiledSchema toSchema(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }

        Map<String, CompiledSchema> properties = new LinkedHashMap<>();
        JsonNode propertiesNode = node.get("properties");
        if (propertiesNode != null && propertiesNode.isObject()) {
            propertiesNode.fields().forEachRemaining(entry -> properties.put(entry.getKey(), toSchema(entry.getValue())));
        }

        return new CompiledSchema(
                optionalText(node, "type"),
                properties,
                toSchema(node.get("items")),
                toStringList(node.get("required")),
                toStringList(node.get("enumValues")),
                toDefaultValue(node.get("defaultValue")),
                optionalText(node, "defaultExpression"),
                optionalText(node, "derivedExpression"),
                optionalText(node, "description"),
                optionalIntegerObject(node.get("minLength")),
                optionalIntegerObject(node.get("maxLength")),
                optionalIntegerObject(node.get("precision")),
                optionalIntegerObject(node.get("scale")),
                optionalIntegerObject(node.get("minItems")),
                optionalIntegerObject(node.get("maxItems")),
                optionalBooleanObject(node.get("uniqueItems")),
                optionalText(node, "itemIdentityField"),
                optionalText(node, "duplicationPolicy"),
                optionalDoubleObject(node.get("min")),
                optionalDoubleObject(node.get("max")),
                optionalText(node, "regex")
        );
    }

    private static Object toDefaultValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return MAPPER.convertValue(node, Object.class);
    }

    private static Boolean optionalBooleanObject(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.asBoolean();
    }

    private static Iterable<JsonNode> array(JsonNode parent, String fieldName) {
        if (parent == null) {
            return List.of();
        }
        JsonNode node = parent.get(fieldName);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<JsonNode> out = new ArrayList<>();
        node.forEach(out::add);
        return out;
    }

    private static Iterable<JsonNode> array(JsonNode parent, String preferredFieldName, String fallbackFieldName) {
        List<JsonNode> preferred = toList(array(parent, preferredFieldName));
        if (!preferred.isEmpty()) {
            return preferred;
        }
        return array(parent, fallbackFieldName);
    }

    private static List<JsonNode> toList(Iterable<JsonNode> values) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode value : values) {
            out.add(value);
        }
        return out;
    }

    private static List<String> toStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode element : node) {
            out.add(element.isNull() ? "" : element.asText());
        }
        return out;
    }

    private static Map<String, String> toStringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        node.fields().forEachRemaining(entry -> out.put(entry.getKey(), entry.getValue().isNull() ? "" : entry.getValue().asText()));
        return out;
    }

    private static Map<String, Object> toObjectMap(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        node.fields().forEachRemaining(entry -> out.put(entry.getKey(), toObjectValue(entry.getValue())));
        return out;
    }

    private static Object toObjectValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return MAPPER.convertValue(node, Object.class);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static String defaulted(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean booleanValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.asBoolean(false);
    }

    private static Boolean optionalBoolean(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.asBoolean();
    }

    private static int intValue(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asInt(fallback);
    }

    private static long longValue(JsonNode node, String field, long fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asLong(fallback);
    }

    private static Integer optionalIntegerObject(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.asInt();
    }

    private static Double optionalDoubleObject(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.asDouble();
    }

    private static Long optionalLongObject(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.asLong();
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return null;
    }
}
