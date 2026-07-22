package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledCapability;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialSamplesRegressionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<SampleExpectation> EXPECTATIONS = List.of(
            new SampleExpectation(
                    "simple-user-registry",
                    "trial.userregistry",
                    "User",
                    "CreateUser",
                    List.of("UserCreated"),
                    List.of("persistence"),
                    8082
            ),
            new SampleExpectation(
                    "simple-contact-intake",
                    "trial.contactintake",
                    "ContactMessage",
                    "SubmitContactMessage",
                    List.of("ContactMessageReceived"),
                    List.of("persistence", "notification"),
                    8083
            ),
            new SampleExpectation(
                    "medium-expense-approval",
                    "trial.expenseapproval",
                    "ExpenseRequest",
                    "SubmitExpense",
                    List.of("ExpenseSubmitted", "ExpenseApproved"),
                    List.of("persistence", "notification", "webhook"),
                    8084
            )
    );

    @Test
    void officialSampleModelsAndConfigsValidateStructurallyAndSemantically() throws Exception {
        JsonSchema modelSchema = readSchema(resolveModelSchema());
        JsonSchema configSchema = readSchema(resolveConfigSchema());

        for (SampleExpectation expectation : EXPECTATIONS) {
            Set<ValidationMessage> modelViolations = modelSchema.validate(readJson(resolveSampleModel(expectation.id())));
            Set<ValidationMessage> configViolations = configSchema.validate(readJson(resolveSampleConfig(expectation.id())));
            assertTrue(modelViolations.isEmpty(),
                    "Expected sample model to validate structurally for " + expectation.id() + ", got: " + modelViolations);
            assertTrue(configViolations.isEmpty(),
                    "Expected sample config to validate structurally for " + expectation.id() + ", got: " + configViolations);

            ModelAst ast = new JsonModelParser().parse(resolveSampleModel(expectation.id()));
            List<String> errors = new SemanticValidator().validate(ast);
            assertTrue(errors.isEmpty(),
                    "Expected sample to validate semantically for " + expectation.id() + ", got: " + errors);
        }
    }

    @Test
    void officialSamplesCompileWithExpectedCuratedBehavior() throws Exception {
        for (SampleExpectation expectation : EXPECTATIONS) {
            CompiledModel compiled = new ModelCompiler().compile(new JsonModelParser().parse(resolveSampleModel(expectation.id())));

            assertEquals(expectation.namespace(), compiled.getNamespace(), "Namespace drift for " + expectation.id());
            assertEquals(1, compiled.getConcepts().size(), "Expected one primary concept for " + expectation.id());

            CompiledConcept entity = compiled.findConcept(expectation.entityName()).orElseThrow();
            assertEquals(expectation.entityName(), entity.getName(), "Primary concept drift for " + expectation.id());

            CompiledFlow flow = compiled.findFlow(expectation.mainFlow()).orElseThrow();
            assertEquals(expectation.entityName(), flow.getConcept(), "Flow concept drift for " + expectation.id());
            assertEquals("create", flow.getMode(), "Main flow mode drift for " + expectation.id());

            List<String> capabilityNames = compiled.getCapabilities().stream()
                    .map(CompiledCapability::getName)
                    .sorted()
                    .toList();
            for (String capabilityName : expectation.capabilities()) {
                assertTrue(capabilityNames.contains(capabilityName),
                        "Expected capability " + capabilityName + " for sample " + expectation.id());
            }

            List<String> eventNames = compiled.getEvents().stream()
                    .map(event -> event.getName())
                    .sorted()
                    .toList();
            assertEquals(expectation.events().stream().sorted().toList(), eventNames,
                    "Event set drift for " + expectation.id());

            switch (expectation.id()) {
                case "simple-user-registry" -> assertSimpleUserRegistry(flow);
                case "simple-contact-intake" -> assertSimpleContactIntake(flow);
                case "medium-expense-approval" -> assertMediumExpenseApproval(flow);
                default -> throw new IllegalStateException("Unhandled sample expectation: " + expectation.id());
            }
        }
    }

    @Test
    void officialSampleDocsStayAlignedWithModelBehavior() throws Exception {
        for (SampleExpectation expectation : EXPECTATIONS) {
            JsonNode manifest = readJson(resolveSampleManifest(expectation.id()));
            String readme = Files.readString(resolveSampleReadme(expectation.id()));
            String expectedBehavior = Files.readString(resolveSampleExpectedBehavior(expectation.id()));
            String expectedEndpoints = Files.readString(resolveSampleExpectedEndpoints(expectation.id()));
            String expectedDiagnostics = Files.readString(resolveSampleExpectedDiagnostics(expectation.id()));
            JsonNode config = readJson(resolveSampleConfig(expectation.id()));

            assertEquals(expectation.id(), manifest.path("id").asText(), "Manifest id drift for " + expectation.id());
            assertEquals(expectation.mainFlow(), manifest.path("mainFlow").asText(),
                    "Manifest mainFlow drift for " + expectation.id());
            assertTrue(readme.contains("One-command run"),
                    "Expected one-command run instruction in README for " + expectation.id());
            assertTrue(readme.contains("expected-behavior.md"),
                    "Expected README standard contents to include expected-behavior.md for " + expectation.id());
            assertTrue(expectedBehavior.contains(expectation.mainFlow()),
                    "Expected behavior doc should mention main flow for " + expectation.id());
            assertTrue(expectedBehavior.toLowerCase().contains("expected behavior"),
                    "Expected behavior heading for " + expectation.id());
            assertTrue(expectedEndpoints.contains("GET /api/flows"),
                    "Expected flow discovery endpoint note for " + expectation.id());
            assertTrue(expectedEndpoints.contains("POST /api/flows/" + expectation.mainFlow() + "/execute"),
                    "Expected main flow endpoint note for " + expectation.id());
            assertTrue(expectedEndpoints.contains("GET /api/admin/model/export"),
                    "Expected admin model export note for " + expectation.id());
            assertTrue(expectedDiagnostics.toLowerCase().contains("runtime evidence"),
                    "Expected runtime evidence section for " + expectation.id());
            assertEquals(expectation.port(), config.path("runtime").path("serverPort").asInt(),
                    "Stable runtime port drift for " + expectation.id());

            if ("simple-user-registry".equals(expectation.id())) {
                assertTrue(expectedBehavior.contains("UserCreated"),
                        "Expected UserCreated note for simple-user-registry.");
                assertTrue(expectedDiagnostics.contains("UserCreated"),
                        "Expected UserCreated evidence note for simple-user-registry.");
            }
            if ("simple-contact-intake".equals(expectation.id())) {
                assertTrue(expectedBehavior.toLowerCase().contains("notification"),
                        "Expected notification behavior note for simple-contact-intake.");
                assertTrue(expectedDiagnostics.toLowerCase().contains("notification"),
                        "Expected notification evidence note for simple-contact-intake.");
            }
            if ("medium-expense-approval".equals(expectation.id())) {
                assertTrue(expectedBehavior.toLowerCase().contains("waiting"),
                        "Expected waiting note for medium-expense-approval.");
                assertTrue(expectedBehavior.toLowerCase().contains("resume"),
                        "Expected resume note for medium-expense-approval.");
                assertTrue(expectedEndpoints.contains("POST /api/events/publish"),
                        "Expected event publish endpoint note for medium-expense-approval.");
                assertTrue(expectedDiagnostics.toLowerCase().contains("waiting state"),
                        "Expected waiting-state note for medium-expense-approval.");
                assertTrue(expectedDiagnostics.toLowerCase().contains("resumes"),
                        "Expected resume note for medium-expense-approval.");
            }
        }
    }

    private static void assertSimpleUserRegistry(CompiledFlow flow) {
        assertTrue(flow.getSteps().stream().anyMatch(step -> "invariant".equals(step.getType())),
                "Expected invariant enforcement in simple-user-registry.");
        assertTrue(flow.getSteps().stream().anyMatch(step -> "capability".equals(step.getType())
                && step.getCapabilityCall() != null
                && "persistence".equals(step.getCapabilityCall().getCapabilityName())
                && "save".equals(step.getCapabilityCall().getOperation())),
                "Expected persistence save capability step in simple-user-registry.");
        assertTrue(flow.getSteps().stream().anyMatch(step -> "UserCreated".equals(step.getEventName())),
                "Expected UserCreated emission in simple-user-registry.");
    }

    private static void assertSimpleContactIntake(CompiledFlow flow) {
        assertTrue(flow.getSteps().stream().anyMatch(step -> "capability".equals(step.getType())
                && step.getCapabilityCall() != null
                && "notification".equals(step.getCapabilityCall().getCapabilityName())),
                "Expected notification capability call in simple-contact-intake.");
        assertTrue(flow.getSteps().stream().anyMatch(step -> "ContactMessageReceived".equals(step.getEventName())),
                "Expected ContactMessageReceived emission in simple-contact-intake.");
    }

    private static void assertMediumExpenseApproval(CompiledFlow flow) {
        CompiledFlowStep branchStep = flow.getSteps().stream()
                .filter(step -> "branch".equals(step.getType()))
                .findFirst()
                .orElseThrow();

        assertEquals("$saved.needsManagerApproval == true", branchStep.getCondition());
        assertFalse(branchStep.getThenSteps().isEmpty(), "Expected then-steps in medium-expense-approval branch.");
        assertFalse(branchStep.getElseSteps().isEmpty(), "Expected else-steps in medium-expense-approval branch.");

        CompiledFlowStep awaitStep = branchStep.getThenSteps().stream()
                .filter(step -> "await".equals(step.getType()))
                .findFirst()
                .orElseThrow();
        assertEquals("ExpenseApproved", awaitStep.getAwaitEventName());
        assertEquals(Boolean.TRUE, awaitStep.getAwaitMatchCorrelation());

        CompiledFlowStep notificationStep = branchStep.getThenSteps().stream()
                .filter(step -> "capability".equals(step.getType())
                        && step.getCapabilityCall() != null
                        && "notification".equals(step.getCapabilityCall().getCapabilityName()))
                .findFirst()
                .orElseThrow();
        assertNotNull(notificationStep.getCapabilityCall());

        CompiledFlowStep webhookStep = branchStep.getThenSteps().stream()
                .filter(step -> "capability".equals(step.getType())
                        && step.getCapabilityCall() != null
                        && "webhook".equals(step.getCapabilityCall().getCapabilityName()))
                .findFirst()
                .orElseThrow();
        assertNotNull(webhookStep.getCapabilityCall());
    }

    private static JsonNode readJson(Path path) throws Exception {
        String content = Files.readString(path);
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
        }
        return MAPPER.readTree(content);
    }

    private static JsonSchema readSchema(Path path) throws Exception {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        return factory.getSchema(Files.readString(path));
    }

    private static Path resolveSampleModel(String sampleId) {
        return resolvePath(
                Path.of("..", "resources", "Models", "official-samples", sampleId, "model.json"),
                Path.of("resources", "Models", "official-samples", sampleId, "model.json")
        );
    }

    private static Path resolveSampleConfig(String sampleId) {
        return resolvePath(
                Path.of("..", "resources", "Models", "official-samples", sampleId, "config.json"),
                Path.of("resources", "Models", "official-samples", sampleId, "config.json")
        );
    }

    private static Path resolveSampleManifest(String sampleId) {
        return resolvePath(
                Path.of("..", "resources", "Models", "official-samples", sampleId, "manifest.json"),
                Path.of("resources", "Models", "official-samples", sampleId, "manifest.json")
        );
    }

    private static Path resolveSampleExpectedEndpoints(String sampleId) {
        return resolvePath(
                Path.of("..", "resources", "Models", "official-samples", sampleId, "expected-endpoints.md"),
                Path.of("resources", "Models", "official-samples", sampleId, "expected-endpoints.md")
        );
    }

    private static Path resolveSampleReadme(String sampleId) {
        return resolvePath(
                Path.of("..", "resources", "Models", "official-samples", sampleId, "README.md"),
                Path.of("resources", "Models", "official-samples", sampleId, "README.md")
        );
    }

    private static Path resolveSampleExpectedBehavior(String sampleId) {
        return resolvePath(
                Path.of("..", "resources", "Models", "official-samples", sampleId, "expected-behavior.md"),
                Path.of("resources", "Models", "official-samples", sampleId, "expected-behavior.md")
        );
    }

    private static Path resolveSampleExpectedDiagnostics(String sampleId) {
        return resolvePath(
                Path.of("..", "resources", "Models", "official-samples", sampleId, "expected-diagnostics.md"),
                Path.of("resources", "Models", "official-samples", sampleId, "expected-diagnostics.md")
        );
    }

    private static Path resolveModelSchema() {
        return resolvePath(
                Path.of("..", "resources", "Schemas", "model.schema.json"),
                Path.of("resources", "Schemas", "model.schema.json")
        );
    }

    private static Path resolveConfigSchema() {
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
        throw new IllegalStateException("Unable to resolve official sample regression path.");
    }

    private record SampleExpectation(
            String id,
            String namespace,
            String entityName,
            String mainFlow,
            List<String> events,
            List<String> capabilities,
            int port
    ) {
    }
}
