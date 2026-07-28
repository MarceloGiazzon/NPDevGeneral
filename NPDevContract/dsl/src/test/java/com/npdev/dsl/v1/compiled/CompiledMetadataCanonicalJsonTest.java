package com.npdev.dsl.v1.compiled;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledMetadataCanonicalJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void canonicalDemoCompilesIntoDeterministicMetadataCatalogs() throws Exception {
        Path modelPath = resolvePath(List.of(
                Path.of("resources", "Models", "canonical-demo", "model.json"),
                Path.of("..", "resources", "Models", "canonical-demo", "model.json")
        ));

        ModelAst ast = new JsonModelParser().parse(modelPath);
        CompiledModel compiledModel = new ModelCompiler().compile(ast);

        String first = CompiledMetadataCanonicalJson.toJson(modelPath, compiledModel);
        String second = CompiledMetadataCanonicalJson.toJson(modelPath, compiledModel);

        assertEquals(first, second, "Compiled metadata output must be deterministic for the same model.");

        JsonNode root = MAPPER.readTree(first);
        assertEquals("1.0.0", root.path("metadataVersion").asText());
        assertTrue(root.path("catalogs").isObject(), "Expected catalogs root object.");

        JsonNode concepts = root.path("catalogs").path("concepts");
        JsonNode domainTypes = root.path("catalogs").path("domainTypes");
        JsonNode fields = root.path("catalogs").path("fields");
        JsonNode enums = root.path("catalogs").path("enums");
        JsonNode references = root.path("catalogs").path("references");
        JsonNode actions = root.path("catalogs").path("actions");
        JsonNode transitions = root.path("catalogs").path("transitions");
        JsonNode layout = root.path("catalogs").path("layout");
        JsonNode validation = root.path("catalogs").path("validation");

        assertTrue(concepts.isArray() && concepts.size() == 4, "Expected four concepts in canonical metadata.");
        assertTrue(domainTypes.isArray() && domainTypes.size() == 2, "Expected two domain types in canonical metadata.");
        assertTrue(findBy(domainTypes, "name", "MRN") != null, "Expected MRN domain type metadata.");
        assertTrue(findBy(concepts, "name", "Appointment") != null, "Expected Appointment concept metadata.");
        assertTrue(findConceptPresentation(concepts, "Appointment", "Appointment", "Appt", "Scheduling"),
                "Expected concept presentation metadata for Appointment.");
        assertTrue(findConceptLayout(concepts, "Appointment", "standard", 2, "-scheduledAt", "status"),
                "Expected concept-level layout metadata for Appointment.");
        assertTrue(findBy(fields, "fieldPath", "emergencyContact.name") != null,
                "Expected nested emergency contact field path in field catalog.");
        assertTrue(findFieldDomainType(fields, "Patient", "mrn", "MRN"),
                "Expected Patient.mrn to reference the MRN domain type.");
        assertTrue(findFieldPresentation(fields, "Patient", "mrn", "Medical record number", "Registration"),
                "Expected Patient.mrn presentation metadata in the field catalog.");
        assertTrue(findFieldInteraction(fields, "Appointment", "providerId", "status == 'Scheduled'", "search-dialog", "available-providers"),
                "Expected Appointment.providerId interaction metadata in the field catalog.");
        assertTrue(findFieldLayout(fields, "Appointment", "providerId", "Overview", 2, "lg", true, 20),
                "Expected Appointment.providerId layout metadata in the field catalog.");
        assertTrue(findRepeatedField(fields, "Patient", "allergies", 20, "code", "deny"),
                "Expected Patient.allergies repeated-section field metadata.");
        assertTrue(findBy(fields, "fieldPath", "allergies[].substance") != null,
                "Expected nested repeated item field path in field catalog.");
        assertTrue(findFieldValueBehavior(fields, "Patient", "preferredLanguage", "en-US", "", false),
                "Expected static default metadata for Patient.preferredLanguage.");
        assertTrue(findFieldValueBehavior(fields, "Patient", "reminderLanguage", null, "preferredLanguage", false),
                "Expected dynamic default metadata for Patient.reminderLanguage.");
        assertTrue(findFieldValueBehavior(fields, "Patient", "chartLabel", null, "concat(lastName, ', ', firstName)", true),
                "Expected derived-field metadata for Patient.chartLabel.");
        assertTrue(findBy(enums, "fieldPath", "status") != null, "Expected enum catalog entry for status.");
        assertTrue(findEnumValue(enums, "Appointment", "status", "Scheduled", "Scheduled", true, "Active"),
                "Expected enriched enum metadata for Appointment.status Scheduled.");
        assertTrue(findReference(references, "Appointment", "patientId", "Patient", "lastName", "allow", "{{lastName}}, {{firstName}} ({{mrn}})", "recent-patients"),
                "Expected Appointment.patientId enriched reference metadata.");
        assertTrue(findAction(actions, "flow", "CreateAppointment"),
                "Expected flow catalog entry for CreateAppointment.");
        assertTrue(findActionMetadata(actions, "flow", "CreateAppointment", "Create appointment", "appointments.create", "appointment-create"),
                "Expected flow-level action metadata for CreateAppointment.");
        assertTrue(findFlowStepMetadata(actions, "CreateAppointment", "capture-created-id", "map", "", "", 0, ""),
                "Expected assign/map flow-step metadata for CreateAppointment.");
        assertTrue(findFlowStepMetadata(actions, "CreateAppointment", "queue-appointment-reminder", "scheduleEvent", "AppointmentReminderDue", "then", 86400, "flow-correlation"),
                "Expected scheduled reminder flow-step metadata for CreateAppointment.");
        assertTrue(findActionMetadata(actions, "flowStep", "save-appointment", "Persist appointment", "appointments.create", "appointment-create"),
                "Expected flow-step action metadata for save-appointment.");
        assertTrue(findAction(actions, "orchestrationAction", "CompleteAppointmentFlow#2"),
                "Expected orchestration action entry for notification send.");
        assertTrue(findActionMetadata(actions, "orchestrationAction", "CompleteAppointmentFlow#2", "Send completion notification", "notifications.send", "appointment-completion-notification"),
                "Expected orchestration action metadata for notification send.");
        assertTrue(findTransition(transitions, "Appointment", "Scheduled", "CheckedIn"),
                "Expected Appointment Scheduled->CheckedIn transition metadata.");
        assertTrue(findTransitionActionMetadata(transitions, "Appointment", "Scheduled", "Cancelled", "Cancel appointment", "high", "appointments.cancel"),
                "Expected rich action metadata for Appointment Scheduled->Cancelled.");
        assertTrue(findLayout(layout, "Appointment", "status", "select"),
                "Expected explicit select widget metadata for Appointment.status.");
        assertTrue(findLayoutPresentation(layout, "Appointment", "status", "Status", "Visit progress"),
                "Expected enriched layout presentation metadata for Appointment.status.");
        assertTrue(findLayoutInteraction(layout, "Appointment", "checkOutTime", "status == 'CheckedIn' || status == 'Completed'", "status == 'CheckedIn'"),
                "Expected conditional interaction metadata for Appointment.checkOutTime.");
        assertTrue(findLayoutStructure(layout, "Appointment", "checkOutTime", "Visit lifecycle", 2, "md"),
                "Expected structured layout metadata for Appointment.checkOutTime.");
        assertTrue(findRepeatedLayout(layout, "Patient", "allergies", "list"),
                "Expected repeated-section layout metadata for Patient.allergies.");
        assertTrue(findLayoutSource(layout, "Patient", "mrn", "explicit-ui"),
                "Expected Patient.mrn layout metadata to preserve explicit presentation metadata.");
        assertTrue(findValidation(validation, "required", "Patient", "emergencyContact.name"),
                "Expected nested required validation metadata for emergencyContact.name.");
        assertTrue(findValidation(validation, "required", "Patient", "allergies[].substance"),
                "Expected nested required validation metadata for allergies[].substance.");
        assertTrue(findValidation(validation, "domainType", "Patient", "MRN"),
                "Expected domain type validation metadata for Patient.mrn.");
        assertTrue(findValidation(validation, "staticDefault", "Patient", "preferredLanguage"),
                "Expected static-default validation metadata for Patient.preferredLanguage.");
        assertTrue(findValidation(validation, "dynamicDefault", "Patient", "reminderLanguage"),
                "Expected dynamic-default validation metadata for Patient.reminderLanguage.");
        assertTrue(findValidation(validation, "derivedField", "Patient", "chartLabel"),
                "Expected derived-field validation metadata for Patient.chartLabel.");
        assertTrue(findValidation(validation, "flowInvariantRef", "Appointment", "PositiveDuration"),
                "Expected flow invariant reference metadata for PositiveDuration.");

        JsonNode invocations = root.path("catalogs").path("invocations");
        assertTrue(invocations.isArray(), "Expected invocations catalog array.");
        // 4 concepts x (list + pagedQuery + exportCsv + read + create/update/delete) = 4x7 = 28,
        // plus 1 flow (CreateAppointment) -- canonical-demo declares no file fields/panels/aggregates.
        assertEquals(29, invocations.size(), "Expected 29 invocation entries for canonical-demo.");

        JsonNode flowEntry = findBy(invocations, "id", "flow:CreateAppointment");
        assertTrue(flowEntry != null, "Expected a flow:CreateAppointment invocation entry.");
        assertEquals("Appointment", flowEntry.path("concept").asText());
        assertEquals("create", flowEntry.path("intent").asText());
        assertEquals("POST", flowEntry.path("method").asText());
        assertEquals("/api/v1/flows/CreateAppointment/execute", flowEntry.path("path").asText());
        assertEquals("/api/flows/CreateAppointment/execute", flowEntry.path("pathAliases").path(0).asText());
        assertTrue(flowEntry.path("preferred").asBoolean(), "A flow entry is always preferred.");
        assertEquals(200, flowEntry.path("execution").path("statusOnComplete").asInt());
        assertEquals(202, flowEntry.path("execution").path("statusOnWaiting").asInt());
        JsonNode inputFields = flowEntry.path("body").path("inputFields");
        assertTrue(inputFields.isArray() && inputFields.size() > 0,
                "Expected CreateAppointment's inputFields to be derived from its own concept (Appointment), "
                        + "since this model declares no flow.inputSchema.");
        assertTrue(findBy(inputFields, "fieldPath", "Appointment.providerId") != null,
                "Expected a non-id Appointment field among CreateAppointment's derived input fields.");

        // Appointment.create is flow-backed (CreateAppointment) -- the direct route must say so.
        JsonNode createDirectAppointment = findBy(invocations, "id", "createDirect:Appointment");
        assertTrue(createDirectAppointment != null, "Expected a createDirect:Appointment invocation entry.");
        assertEquals("POST", createDirectAppointment.path("method").asText());
        assertFalse(createDirectAppointment.path("preferred").asBoolean(),
                "createDirect:Appointment must be non-preferred: Appointment.create is flow-backed.");
        assertEquals("flow:CreateAppointment", createDirectAppointment.path("prefer").asText());
        assertTrue(!createDirectAppointment.path("preferReason").asText().isBlank(),
                "Expected a non-blank preferReason explaining the flow-bypass risk.");

        // Patient has no flow backing any of its CRUD modes -- its direct routes must be preferred.
        JsonNode createDirectPatient = findBy(invocations, "id", "createDirect:Patient");
        assertTrue(createDirectPatient != null, "Expected a createDirect:Patient invocation entry.");
        assertTrue(createDirectPatient.path("preferred").asBoolean(),
                "createDirect:Patient must be preferred: Patient.create has no flow backing it.");
        assertTrue(createDirectPatient.path("prefer").isMissingNode(),
                "A preferred entry must not carry a prefer pointer.");

        JsonNode readPatient = findBy(invocations, "id", "read:Patient");
        assertTrue(readPatient != null, "Expected a read:Patient invocation entry.");
        assertEquals("GET", readPatient.path("method").asText());
        assertTrue(readPatient.path("path").asText().endsWith("/{id}"),
                "Expected the read entry's path to carry an {id} path variable.");

        JsonNode pagedQueryPatient = findBy(invocations, "id", "pagedQuery:Patient");
        assertTrue(pagedQueryPatient != null, "Expected a pagedQuery:Patient invocation entry.");
        assertEquals("/api/v1/concepts/Patient/page", pagedQueryPatient.path("path").asText());

        // Determinism: id-ordering + no orderKey leakage (same discipline as every other catalog).
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode entry : invocations) {
            ids.add(entry.path("id").asText());
            assertTrue(entry.path("orderKey").isMissingNode(), "orderKey must never leak into the final JSON.");
        }
        List<String> sortedIds = new java.util.ArrayList<>(ids);
        sortedIds.sort(String::compareTo);
        assertEquals(sortedIds, ids, "Invocation entries must be sorted by id.");
    }

    private static JsonNode findBy(JsonNode arrayNode, String fieldName, String value) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return null;
        }
        for (JsonNode item : arrayNode) {
            if (value.equals(item.path(fieldName).asText())) {
                return item;
            }
        }
        return null;
    }

    private static boolean findReference(
            JsonNode references,
            String concept,
            String fieldPath,
            String targetConcept,
            String displayField,
            String inlineCreate,
            String displayTemplate,
            String defaultFilter
    ) {
        if (references == null || !references.isArray()) {
            return false;
        }
        for (JsonNode item : references) {
            if (concept.equals(item.path("concept").asText())
                    && fieldPath.equals(item.path("fieldPath").asText())
                    && targetConcept.equals(item.path("targetConcept").asText())
                    && displayField.equals(item.path("displayField").asText())
                    && displayTemplate.equals(item.path("displayTemplate").asText())
                    && defaultFilter.equals(item.path("defaultFilter").asText())
                    && inlineCreate.equals(item.path("inlineCreate").asText())
                    && item.path("searchFields").isArray()
                    && item.path("searchFields").size() >= 2
                    && item.path("pickerColumns").isArray()
                    && item.path("pickerColumns").size() >= 2
                    && item.path("previewFields").isArray()
                    && item.path("previewFields").size() >= 2) {
                return true;
            }
        }
        return false;
    }

    private static boolean findConceptPresentation(
            JsonNode concepts,
            String conceptName,
            String label,
            String shortLabel,
            String section
    ) {
        JsonNode concept = findBy(concepts, "name", conceptName);
        return concept != null
                && label.equals(concept.path("label").asText())
                && shortLabel.equals(concept.path("shortLabel").asText())
                && section.equals(concept.path("section").asText());
    }

    private static boolean findConceptLayout(
            JsonNode concepts,
            String conceptName,
            String displayMode,
            int formColumns,
            String defaultSort,
            String defaultGroup
    ) {
        JsonNode concept = findBy(concepts, "name", conceptName);
        return concept != null
                && displayMode.equals(concept.path("displayMode").asText())
                && formColumns == concept.path("formColumns").asInt(-1)
                && defaultSort.equals(concept.path("defaultSort").asText())
                && defaultGroup.equals(concept.path("defaultGroup").asText());
    }

    private static boolean findAction(JsonNode actions, String kind, String name) {
        if (actions == null || !actions.isArray()) {
            return false;
        }
        for (JsonNode item : actions) {
            if (kind.equals(item.path("kind").asText()) && name.equals(item.path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean findActionMetadata(
            JsonNode actions,
            String kind,
            String name,
            String label,
            String permissionHint,
            String inputFormHint
    ) {
        if (actions == null || !actions.isArray()) {
            return false;
        }
        for (JsonNode item : actions) {
            if (kind.equals(item.path("kind").asText())
                    && name.equals(item.path("name").asText())
                    && label.equals(item.path("label").asText())
                    && permissionHint.equals(item.path("permissionHint").asText())
                    && inputFormHint.equals(item.path("inputFormHint").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean findFlowStepMetadata(
            JsonNode actions,
            String ownerName,
            String name,
            String actionType,
            String eventName,
            String branchPath,
            long delaySeconds,
            String correlationHint
    ) {
        if (actions == null || !actions.isArray()) {
            return false;
        }
        for (JsonNode item : actions) {
            if (!"flowStep".equals(item.path("kind").asText())) {
                continue;
            }
            if (!ownerName.equals(item.path("ownerName").asText())
                    || !name.equals(item.path("name").asText())
                    || !actionType.equals(item.path("actionType").asText())) {
                continue;
            }
            if (!eventName.equals(item.path("eventName").asText())) {
                continue;
            }
            if (!branchPath.equals(item.path("branchPath").asText())) {
                continue;
            }
            if (!correlationHint.equals(item.path("correlationHint").asText())) {
                continue;
            }
            JsonNode delay = item.get("delaySeconds");
            long actualDelay = delay == null || delay.isNull() ? 0L : delay.asLong();
            if (actualDelay != delaySeconds) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean findTransition(JsonNode transitions, String concept, String from, String to) {
        if (transitions == null || !transitions.isArray()) {
            return false;
        }
        for (JsonNode item : transitions) {
            if (concept.equals(item.path("concept").asText())
                    && from.equals(item.path("from").asText())
                    && to.equals(item.path("to").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean findTransitionActionMetadata(
            JsonNode transitions,
            String concept,
            String from,
            String to,
            String label,
            String dangerLevel,
            String permissionHint
    ) {
        if (transitions == null || !transitions.isArray()) {
            return false;
        }
        for (JsonNode item : transitions) {
            if (concept.equals(item.path("concept").asText())
                    && from.equals(item.path("from").asText())
                    && to.equals(item.path("to").asText())
                    && label.equals(item.path("label").asText())
                    && dangerLevel.equals(item.path("dangerLevel").asText())
                    && permissionHint.equals(item.path("permissionHint").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean findLayout(JsonNode layout, String concept, String fieldPath, String widget) {
        if (layout == null || !layout.isArray()) {
            return false;
        }
        for (JsonNode item : layout) {
            if (concept.equals(item.path("concept").asText())
                    && fieldPath.equals(item.path("fieldPath").asText())
                    && widget.equals(item.path("widget").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean findLayoutPresentation(
            JsonNode layout,
            String concept,
            String fieldPath,
            String shortLabel,
            String section
    ) {
        if (layout == null || !layout.isArray()) {
            return false;
        }
        for (JsonNode item : layout) {
            if (concept.equals(item.path("concept").asText())
                    && fieldPath.equals(item.path("fieldPath").asText())
                    && shortLabel.equals(item.path("shortLabel").asText())
                    && section.equals(item.path("section").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean findLayoutSource(JsonNode layout, String concept, String fieldPath, String source) {
        if (layout == null || !layout.isArray()) {
            return false;
        }
        for (JsonNode item : layout) {
            if (concept.equals(item.path("concept").asText())
                    && fieldPath.equals(item.path("fieldPath").asText())
                    && source.equals(item.path("source").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean findRepeatedLayout(JsonNode layout, String concept, String fieldPath, String widget) {
        if (layout == null || !layout.isArray()) {
            return false;
        }
        for (JsonNode item : layout) {
            if (concept.equals(item.path("concept").asText())
                    && fieldPath.equals(item.path("fieldPath").asText())
                    && widget.equals(item.path("widget").asText())
                    && item.path("repeatedSection").asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    private static boolean findLayoutInteraction(
            JsonNode layout,
            String concept,
            String fieldPath,
            String visibleWhen,
            String enabledWhen
    ) {
        if (layout == null || !layout.isArray()) {
            return false;
        }
        for (JsonNode item : layout) {
            if (concept.equals(item.path("concept").asText())
                    && fieldPath.equals(item.path("fieldPath").asText())
                    && visibleWhen.equals(item.path("visibleWhen").asText())
                    && enabledWhen.equals(item.path("enabledWhen").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean findFieldDomainType(JsonNode fields, String concept, String fieldPath, String domainType) {
        if (fields == null || !fields.isArray()) {
            return false;
        }
        for (JsonNode item : fields) {
            if (concept.equals(item.path("concept").asText())
                    && fieldPath.equals(item.path("fieldPath").asText())
                    && domainType.equals(item.path("domainType").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean findFieldPresentation(
            JsonNode fields,
            String concept,
            String fieldPath,
            String label,
            String section
    ) {
        if (fields == null || !fields.isArray()) {
            return false;
        }
        for (JsonNode item : fields) {
            if (concept.equals(item.path("concept").asText())
                    && fieldPath.equals(item.path("fieldPath").asText())
                    && label.equals(item.path("label").asText())
                    && section.equals(item.path("section").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean findFieldInteraction(
            JsonNode fields,
            String concept,
            String fieldPath,
            String enabledWhen,
            String pickerType,
            String filterPreset
    ) {
        if (fields == null || !fields.isArray()) {
            return false;
        }
        for (JsonNode item : fields) {
            if (concept.equals(item.path("concept").asText())
                    && fieldPath.equals(item.path("fieldPath").asText())
                    && enabledWhen.equals(item.path("enabledWhen").asText())
                    && pickerType.equals(item.path("pickerType").asText())
                    && filterPreset.equals(item.path("filterPreset").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean findFieldLayout(
            JsonNode fields,
            String concept,
            String fieldPath,
            String tab,
            int column,
            String width,
            boolean listColumn,
            int listColumnOrder
    ) {
        if (fields == null || !fields.isArray()) {
            return false;
        }
        for (JsonNode item : fields) {
            if (concept.equals(item.path("concept").asText())
                    && fieldPath.equals(item.path("fieldPath").asText())
                    && tab.equals(item.path("tab").asText())
                    && column == item.path("column").asInt(-1)
                    && width.equals(item.path("width").asText())
                    && listColumn == item.path("listColumn").asBoolean(false)
                    && listColumnOrder == item.path("listColumnOrder").asInt(-1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean findLayoutStructure(
            JsonNode layout,
            String concept,
            String fieldPath,
            String tab,
            int column,
            String width
    ) {
        if (layout == null || !layout.isArray()) {
            return false;
        }
        for (JsonNode item : layout) {
            if (concept.equals(item.path("concept").asText())
                    && fieldPath.equals(item.path("fieldPath").asText())
                    && tab.equals(item.path("tab").asText())
                    && column == item.path("column").asInt(-1)
                    && width.equals(item.path("width").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean findRepeatedField(
            JsonNode fields,
            String concept,
            String fieldPath,
            int maxItems,
            String itemIdentityField,
            String duplicationPolicy
    ) {
        if (fields == null || !fields.isArray()) {
            return false;
        }
        for (JsonNode item : fields) {
            if (concept.equals(item.path("concept").asText())
                    && fieldPath.equals(item.path("fieldPath").asText())
                    && item.path("repeatedSection").asBoolean(false)
                    && maxItems == item.path("maxItems").asInt()
                    && itemIdentityField.equals(item.path("itemIdentityField").asText())
                    && duplicationPolicy.equals(item.path("duplicationPolicy").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean findFieldValueBehavior(
            JsonNode fields,
            String concept,
            String fieldPath,
            String defaultValue,
            String expression,
            boolean computed
    ) {
        if (fields == null || !fields.isArray()) {
            return false;
        }
        for (JsonNode item : fields) {
            if (!concept.equals(item.path("concept").asText())
                    || !fieldPath.equals(item.path("fieldPath").asText())) {
                continue;
            }
            boolean defaultMatches = defaultValue == null
                    || defaultValue.equals(item.path("defaultValue").asText());
            boolean expressionMatches = expression.equals(item.path("defaultExpression").asText())
                    || expression.equals(item.path("derivedExpression").asText());
            if (defaultMatches && expressionMatches && computed == item.path("computed").asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    private static boolean findEnumValue(
            JsonNode enums,
            String concept,
            String fieldPath,
            String value,
            String label,
            boolean defaultValue,
            String group
    ) {
        if (enums == null || !enums.isArray()) {
            return false;
        }
        for (JsonNode item : enums) {
            if (concept.equals(item.path("concept").asText())
                    && fieldPath.equals(item.path("fieldPath").asText())
                    && value.equals(item.path("value").asText())
                    && label.equals(item.path("label").asText())
                    && defaultValue == item.path("default").asBoolean(false)
                    && group.equals(item.path("group").asText())
                    && !item.path("badgeHint").asText().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean findValidation(JsonNode validation, String kind, String concept, String marker) {
        if (validation == null || !validation.isArray()) {
            return false;
        }
        for (JsonNode item : validation) {
            if (!kind.equals(item.path("kind").asText()) || !concept.equals(item.path("concept").asText())) {
                continue;
            }
            if (marker.equals(item.path("fieldPath").asText()) || marker.equals(item.path("invariantRef").asText())) {
                return true;
            }
        }
        return false;
    }

    private static Path resolvePath(List<Path> candidates) {
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("Unable to resolve compiled metadata test model from candidates: " + candidates);
    }
}
