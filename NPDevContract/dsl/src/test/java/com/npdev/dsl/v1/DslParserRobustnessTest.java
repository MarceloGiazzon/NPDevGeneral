package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.ModelSchemaValidationException;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationDiagnostic;
import com.npdev.dsl.v1.validation.ValidationLayer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DslParserRobustnessTest {

    @Test
    void schemaInvalidModelFailsBeforeManualParserWithPathBasedDiagnostics() throws Exception {
        Path modelPath = Files.createTempFile("npdev-cp14-schema-first-", ".json");
        Files.writeString(modelPath, """
                {
                  "$schema": "NPDevContract/schemas/model.schema.json",
                  "schemaVersion": "1.0.0",
                  "namespace": "cp14.schemafirst",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Patient",
                      "fields": [
                        { "name": "mrn", "required": true }
                      ]
                    }
                  ]
                }
                """);

        Exception exception = assertThrows(Exception.class, () -> new JsonModelParser().parse(modelPath));
        ModelSchemaValidationException schemaException = assertInstanceOf(ModelSchemaValidationException.class, exception);
        assertFalse(schemaException.getDiagnostics().isEmpty(), "Expected schema diagnostics.");
        assertTrue(schemaException.getMessage().contains("Model schema validation failed"));
        assertFalse(schemaException.getMessage().contains("Missing/blank required field"),
                "Schema-first validation must fail before manual requiredText parsing.");

        ValidationDiagnostic diagnostic = schemaException.getDiagnostics().stream()
                .filter(entry -> entry.getPath() != null && entry.getPath().contains("concepts[0].fields[0]"))
                .findFirst()
                .orElse(schemaException.getFirstDiagnostic());
        assertNotNull(diagnostic);
        assertEquals(ValidationLayer.STRUCTURAL, diagnostic.getLayer());
        assertNotNull(diagnostic.getPath());
        assertTrue(diagnostic.getPath().startsWith("$"), "Expected JSON-path style diagnostic path: " + diagnostic.getPath());
        assertNotNull(diagnostic.getSuggestedFix());
        assertTrue(diagnostic.getHelpKey().startsWith("validation.structural."));
    }

    @Test
    void canonicalAndOfficialPositiveModelsRetainCompiledAstBehavior() throws Exception {
        CompiledModel canonical = parseAndCompile(resolveCanonicalModel());
        assertEquals("canonical.clinicdemo", canonical.getNamespace());
        assertEquals(4, canonical.getConcepts().size());
        assertTrue(new SemanticValidator().validate(new JsonModelParser().parse(resolveCanonicalModel())).isEmpty());

        for (Path sampleModel : List.of(
                resolveSampleModel("simple-user-registry"),
                resolveSampleModel("simple-contact-intake"),
                resolveSampleModel("medium-expense-approval")
        )) {
            ModelAst ast = new JsonModelParser().parse(sampleModel);
            assertTrue(new SemanticValidator().validate(ast).isEmpty(), "Expected semantic pass for " + sampleModel);
            CompiledModel compiled = new ModelCompiler().compile(ast);
            assertEquals(1, compiled.getConcepts().size(), "Primary official sample AST behavior drifted for " + sampleModel);
            assertFalse(compiled.getFlows().isEmpty(), "Official sample flow behavior drifted for " + sampleModel);
        }
    }

    @Test
    void semanticDiagnosticsRetainPathAndSuggestedFix() throws Exception {
        Path modelPath = Files.createTempFile("npdev-cp14-semantic-diagnostic-", ".json");
        Files.writeString(modelPath, """
                {
                  "$schema": "NPDevContract/schemas/model.schema.json",
                  "schemaVersion": "1.0.0",
                  "namespace": "cp14.semantic",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Patient",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "providerId", "type": "reference", "ref": "Provider", "required": true }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        ValidationDiagnostic diagnostic = new SemanticValidator().validateWithWarnings(ast).getDiagnostics().stream()
                .filter(entry -> "unknown_reference_target".equals(entry.getCode()))
                .findFirst()
                .orElseThrow();

        assertEquals("concepts[Patient].fields[providerId]", diagnostic.getPath());
        assertNotNull(diagnostic.getSuggestedFix());
        assertTrue(diagnostic.getSuggestedFix().contains("existing concept"));
    }

    private static CompiledModel parseAndCompile(Path path) throws Exception {
        return new ModelCompiler().compile(new JsonModelParser().parse(path));
    }

    private static Path resolveCanonicalModel() {
        return resolvePath(
                Path.of("..", "resources", "Models", "canonical-demo", "model.json"),
                Path.of("resources", "Models", "canonical-demo", "model.json")
        );
    }

    private static Path resolveSampleModel(String sampleId) {
        return resolvePath(
                Path.of("..", "resources", "Models", "official-samples", sampleId, "model.json"),
                Path.of("resources", "Models", "official-samples", sampleId, "model.json")
        );
    }

    private static Path resolvePath(Path first, Path second) {
        if (Files.exists(first)) {
            return first.normalize();
        }
        if (Files.exists(second)) {
            return second.normalize();
        }
        throw new IllegalStateException("Unable to resolve test path: " + first + " or " + second);
    }
}
