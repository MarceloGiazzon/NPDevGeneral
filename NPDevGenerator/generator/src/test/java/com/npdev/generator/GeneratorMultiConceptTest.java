package com.npdev.generator;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratorMultiConceptTest {

    @Test
    void generatesCrudArtifactsForAllConcepts() throws Exception {
        CompiledModel model = compile("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "name", "type": "string", "required": true },
                        { "name": "email", "type": "string", "required": true }
                      ],
                      "invariants": [
                        { "name": "EmailUnique", "type": "unique", "fields": ["email"] }
                      ]
                    },
                    {
                      "name": "Patient",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "mrn", "type": "string" },
                        { "name": "firstName", "type": "string", "required": true },
                        { "name": "lastName", "type": "string", "required": true },
                        { "name": "dateOfBirth", "type": "string", "required": true }
                      ],
                      "invariants": [
                        { "name": "PatientMrnUnique", "type": "unique", "fields": ["mrn"] }
                      ]
                    }
                  ],
                  "bindings": [
                    { "capability": "persistence", "adapter": "repository" },
                    { "capability": "eventBus", "adapter": "inproc" }
                  ],
                  "events": [
                    { "name": "UserCreated" },
                    { "name": "PatientCreated" }
                  ]
                }
                """);

        Path out = Files.createTempDirectory("npdev-multi-concept-");
        Path migrations = Files.createTempDirectory("npdev-multi-concept-migrations-");

        GeneratorFacade facade = new GeneratorFacade(
                new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy())
        );
        facade.generate(model, out, migrations);

        Path patientEntity = out.resolve("src/main/java/com/npdev/generated/entities/Patient.java");
        Path patientRepository = out.resolve("src/main/java/com/npdev/generated/repositories/PatientRepository.java");
        Path patientService = out.resolve("src/main/java/com/npdev/generated/services/PatientServiceBase.java");
        Path patientController = out.resolve("src/main/java/com/npdev/generated/controllers/PatientController.java");

        assertTrue(Files.exists(patientEntity), "Expected Patient entity generation");
        assertTrue(Files.notExists(patientRepository),
                "Generated concept persistence should use runtime ConceptStore/PersistenceCapability, not direct Spring repositories");
        assertTrue(Files.exists(patientService), "Expected Patient service generation");
        assertTrue(Files.exists(patientController), "Expected Patient controller generation");

        String patientServiceContent = Files.readString(patientService);
        assertTrue(patientServiceContent.contains("enforceWithKernel(\"Patient\""),
                "Expected Patient service to delegate invariants to runtime kernel");
        assertTrue(patientServiceContent.contains("GeneratedCrudRuntimeSupport"),
                "Expected Patient service to delegate reusable CRUD runtime support");
        assertTrue(patientServiceContent.contains("runtimeSupport"),
                "Expected Patient service to use shared runtime support entrypoint");
        assertTrue(patientServiceContent.contains("runtimeSupport.resolveCurrentCrudContext"),
                "Expected Patient service to resolve real ExecutionContext via runtime support");
        assertTrue(patientServiceContent.contains("runtimeSupport.checkCrudPermission"),
                "Expected Patient service to check permissions via runtime support");
        assertTrue(patientServiceContent.contains("runtimeSupport.auditCrudMutation"),
                "Expected Patient service to emit audit records via runtime support");
        assertTrue(patientServiceContent.contains("runtimeSupport.checkCrudIdempotency"),
                "Expected Patient service to check idempotency via runtime support");
        assertTrue(patientServiceContent.contains("runtimeSupport.recordCrudIdempotencySuccess"),
                "Expected Patient service to record idempotency success via runtime support");
        assertFalse(patientServiceContent.contains("ExecutionContext.anonymous().withTag(\"executionMode\", \"headless\")"),
                "Expected Patient service to use real context, not hardcoded anonymous headless context");

        String patientControllerContent = Files.readString(patientController);
        assertTrue(patientControllerContent.contains("@RequestMapping(\"/api/patients\")"),
                "Expected Patient controller to expose /api/patients route");
    }

    @Test
    void sameModelGenerationProducesDeterministicChecksums() throws Exception {
        CompiledModel model = compile("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "name", "type": "string", "required": true }
                      ]
                    },
                    {
                      "name": "Patient",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "firstName", "type": "string", "required": true }
                      ]
                    }
                  ]
                }
                """);

        Path outOne = Files.createTempDirectory("npdev-multi-concept-determinism-one-");
        Path outTwo = Files.createTempDirectory("npdev-multi-concept-determinism-two-");
        Path migrationsOne = Files.createTempDirectory("npdev-multi-concept-determinism-mig-one-");
        Path migrationsTwo = Files.createTempDirectory("npdev-multi-concept-determinism-mig-two-");

        GeneratorFacade facade = new GeneratorFacade(
                new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(outOne, new RegenerationPolicy())
        );
        facade.generate(model, outOne, migrationsOne);
        new GeneratorFacade(
                new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(outTwo, new RegenerationPolicy())
        ).generate(model, outTwo, migrationsTwo);

        assertEquals(checksumTree(outOne), checksumTree(outTwo),
                "The same model should produce deterministic generated file checksums.");
    }

    private static CompiledModel compile(String json) throws Exception {
        Path modelPath = Files.createTempFile("npdev-multi-concept-model-", ".json");
        Files.writeString(modelPath, json, StandardCharsets.UTF_8);

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no semantic errors, got: " + errors);

        return new ModelCompiler().compile(ast);
    }

    private static List<String> checksumTree(Path root) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String relative = root.relativize(path).toString().replace('\\', '/');
                        return !relative.equals("src/main/resources/npdev/db/schema-realization-manifest.json")
                                && !relative.equals("src/main/resources/npdev/support/generated-folder.signature.properties")
                                && !relative.equals("src/main/resources/npdev/store/pack-catalog.json");
                    })
                    .sorted()
                    .map(path -> {
                        try {
                            byte[] bytes = Files.readAllBytes(path);
                            byte[] hash = digest.digest(bytes);
                            StringBuilder builder = new StringBuilder();
                            for (byte value : hash) {
                                builder.append(String.format("%02x", value));
                            }
                            return root.relativize(path).toString().replace('\\', '/') + "=" + builder;
                        } catch (Exception exception) {
                            throw new IllegalStateException("Unable to checksum generated output.", exception);
                        }
                    })
                    .collect(Collectors.toList());
        }
    }
}

