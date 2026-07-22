package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledMetadataCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalDemoRegressionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void canonicalModelAndConfigValidateStructurally() throws Exception {
        JsonSchema modelSchema = readSchema(resolveCanonicalModelSchema());
        JsonSchema configSchema = readSchema(resolveCanonicalConfigSchema());

        Set<ValidationMessage> modelViolations = modelSchema.validate(readJson(resolveCanonicalModel()));
        Set<ValidationMessage> configViolations = configSchema.validate(readJson(resolveCanonicalConfig()));

        assertTrue(modelViolations.isEmpty(), "Expected canonical demo model to validate structurally, got: " + modelViolations);
        assertTrue(configViolations.isEmpty(), "Expected canonical demo config to validate structurally, got: " + configViolations);
    }

    @Test
    void canonicalDemoValidatesSemanticallyAndCompilesAsStableBaseline() throws Exception {
        ModelAst ast = new JsonModelParser().parse(resolveCanonicalModel());
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no semantic errors, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertEquals("canonical.clinicdemo", compiled.getNamespace());
        assertEquals("1.0", compiled.getVersion());
        assertEquals(4, compiled.getConcepts().size());
        assertEquals(List.of("Appointment", "InsuranceClaim", "Patient", "Provider"),
                compiled.getConcepts().stream().map(CompiledConcept::getName).sorted().toList());
        assertEquals(List.of("MRN", "NPI"),
                compiled.getDomainTypes().stream().map(domainType -> domainType.getName()).sorted().toList());
        assertEquals(1, compiled.getFlows().size());
        assertEquals(1, compiled.getOrchestrationRules().size());

        CompiledFlow flow = compiled.findFlow("CreateAppointment").orElseThrow();
        assertEquals("Appointment", flow.getConcept());
        assertEquals("create", flow.getMode());
        assertNotNull(flow.getAction());
        assertEquals("Create appointment", flow.getAction().getLabel());
        assertEquals("appointments.create", flow.getAction().getPermissionHint());

        CompiledConcept appointment = compiled.findConcept("Appointment").orElseThrow();
        assertNotNull(appointment.getLifecycle());
        assertEquals("status", appointment.getLifecycle().getStatusField());
        assertEquals(4, appointment.getLifecycle().getStates().size());
        assertEquals(3, appointment.getLifecycle().getTransitions().size());

        CompiledField patientReference = appointment.getFields().stream()
                .filter(field -> field.getName().equals("patientId"))
                .findFirst()
                .orElseThrow();
        assertEquals("Patient", patientReference.getReferenceTarget());
        assertNotNull(patientReference.getReferenceSemantics());
        assertEquals("{{lastName}}, {{firstName}} ({{mrn}})", patientReference.getReferenceSemantics().getDisplayTemplate());

        CompiledField providerReference = appointment.getFields().stream()
                .filter(field -> field.getName().equals("providerId"))
                .findFirst()
                .orElseThrow();
        assertEquals("Provider", providerReference.getReferenceTarget());

        CompiledConcept patient = compiled.findConcept("Patient").orElseThrow();
        CompiledField mrn = patient.getFields().stream()
                .filter(field -> field.getName().equals("mrn"))
                .findFirst()
                .orElseThrow();
        assertEquals("MRN", mrn.getDomainType());

        CompiledField allergies = patient.getFields().stream()
                .filter(field -> field.getName().equals("allergies"))
                .findFirst()
                .orElseThrow();
        assertNotNull(allergies.getSchema());
        assertEquals("array", allergies.getSchema().getType());
        assertNotNull(allergies.getSchema().getItems());
        assertEquals("object", allergies.getSchema().getItems().getType());
        assertEquals("deny", allergies.getSchema().getDuplicationPolicy());
        assertEquals("code", allergies.getSchema().getItemIdentityField());
    }

    @Test
    void canonicalDemoCompiledMetadataMatchesBaselineExpectations() throws Exception {
        ModelAst ast = new JsonModelParser().parse(resolveCanonicalModel());
        CompiledModel compiled = new ModelCompiler().compile(ast);

        JsonNode root = MAPPER.readTree(CompiledMetadataCanonicalJson.toJson(resolveCanonicalModel(), compiled));
        JsonNode catalogs = root.path("catalogs");

        assertEquals("1.0.0", root.path("metadataVersion").asText());
        assertEquals("canonical.clinicdemo", root.path("namespace").asText());
        assertEquals(4, catalogs.path("concepts").size());
        assertEquals(36, catalogs.path("fields").size());
        assertEquals(9, catalogs.path("actions").size());
        assertEquals(5, catalogs.path("references").size());

        JsonNode createAppointment = findByName(catalogs.path("actions"), "CreateAppointment");
        assertEquals("flow", createAppointment.path("kind").asText());
        assertEquals("appointments.create", createAppointment.path("permissionHint").asText());
        assertEquals("appointment-create", createAppointment.path("inputFormHint").asText());

        JsonNode appointmentConcept = findByName(catalogs.path("concepts"), "Appointment");
        assertEquals("status", appointmentConcept.path("lifecycleStatusField").asText());
        assertEquals(4, appointmentConcept.path("stateCount").asInt());
        assertEquals(3, appointmentConcept.path("transitionCount").asInt());
        assertTrue(appointmentConcept.path("flowNames").toString().contains("CreateAppointment"));

        JsonNode patientReference = findByFieldPath(catalogs.path("references"), "patientId");
        assertEquals("Patient", patientReference.path("targetConcept").asText());
        assertEquals("recent-patients", patientReference.path("defaultFilter").asText());

        JsonNode layoutEntry = catalogs.path("layout").findValuesAsText("tab").stream()
                .filter("Overview"::equals)
                .findFirst()
                .map(value -> root)
                .orElse(null);
        assertNotNull(layoutEntry, "Expected at least one layout catalog entry targeting the Overview tab.");

        boolean hasTransitionRequiredCheckIn = catalogs.path("validation").findValuesAsText("transition").stream()
                .anyMatch("Scheduled->CheckedIn"::equals);
        assertTrue(hasTransitionRequiredCheckIn, "Expected validation catalog to include Scheduled->CheckedIn transition requirements.");
    }

    private static JsonNode findByName(JsonNode array, String name) {
        return stream(array)
                .stream()
                .filter(node -> name.equals(node.path("name").asText()))
                .findFirst()
                .orElseThrow();
    }

    private static JsonNode findByFieldPath(JsonNode array, String fieldPath) {
        return stream(array)
                .stream()
                .filter(node -> fieldPath.equals(node.path("fieldPath").asText()))
                .findFirst()
                .orElseThrow();
    }

    private static List<JsonNode> stream(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<JsonNode> nodes = new ArrayList<>();
        array.elements().forEachRemaining(nodes::add);
        return nodes;
    }

    private static JsonNode readJson(Path path) throws Exception {
        return MAPPER.readTree(Files.readString(path));
    }

    private static JsonSchema readSchema(Path path) throws Exception {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        return factory.getSchema(Files.readString(path));
    }

    private static Path resolveCanonicalModel() {
        return resolvePath(
                Path.of("..", "resources", "Models", "canonical-demo", "model.json"),
                Path.of("resources", "Models", "canonical-demo", "model.json")
        );
    }

    private static Path resolveCanonicalConfig() {
        return resolvePath(
                Path.of("..", "resources", "Models", "canonical-demo", "config.json"),
                Path.of("resources", "Models", "canonical-demo", "config.json")
        );
    }

    private static Path resolveCanonicalModelSchema() {
        return resolvePath(
                Path.of("..", "resources", "Schemas", "model.schema.json"),
                Path.of("resources", "Schemas", "model.schema.json")
        );
    }

    private static Path resolveCanonicalConfigSchema() {
        return resolvePath(
                Path.of("..", "resources", "Schemas", "config.schema.json"),
                Path.of("resources", "Schemas", "config.schema.json")
        );
    }

    private static Path resolvePath(Path first, Path second) {
        if (Files.exists(first)) {
            return first.normalize();
        }
        if (Files.exists(second)) {
            return second.normalize();
        }
        throw new IllegalStateException("Unable to resolve canonical demo regression path.");
    }
}
