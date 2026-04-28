package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledEntity;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DslSpecializationTest {

    @Test
    void compilesInheritedFieldsAndInvariants() throws Exception {
        String json = """
                {
                  "namespace": "billing",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "Invoice",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "code", "type": "string", "required": true },
                        { "name": "amount", "type": "long", "required": true }
                      ],
                      "invariants": [
                        { "rule": "unique(code)" }
                      ]
                    },
                    {
                      "name": "MedicalInvoice",
                      "extends": "Invoice",
                      "fields": [
                        { "name": "doctorId", "type": "uuid", "required": true }
                      ]
                    }
                  ]
                }
                """;

        ModelAst ast = parseJson(json);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no semantic errors, got: " + errors);

        CompiledEntity compiled = new ModelCompiler()
                .compile(ast)
                .findEntity("MedicalInvoice")
                .orElseThrow();

        assertEquals(4, compiled.getFields().size());
        assertTrue(hasField(compiled.getFields(), "id"));
        assertTrue(hasField(compiled.getFields(), "code"));
        assertTrue(hasField(compiled.getFields(), "amount"));
        assertTrue(hasField(compiled.getFields(), "doctorId"));
        assertTrue(isUnique(compiled.getFields(), "code"));
    }

    @Test
    void validatorRejectsUnknownParentAndCycle() throws Exception {
        String unknownParentJson = """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "Child",
                      "extends": "MissingParent",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """;

        List<String> unknownParentErrors = new SemanticValidator().validate(parseJson(unknownParentJson));
        assertTrue(unknownParentErrors.stream().anyMatch(e ->
                e.contains("extends unknown base MissingParent")
                        || e.contains("BASE_NOT_FOUND")));

        String cycleJson = """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "A",
                      "extends": "B",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    },
                    {
                      "name": "B",
                      "extends": "A",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """;

        List<String> cycleErrors = new SemanticValidator().validate(parseJson(cycleJson));
        assertTrue(cycleErrors.stream().anyMatch(e ->
                e.contains("Inheritance cycle detected")
                        || e.contains("ILLEGAL_OVERRIDE")));
    }

    @Test
    void validatorRejectsDuplicateFieldNamesAcrossInheritance() throws Exception {
        String json = """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "concepts": [
                    {
                      "name": "Base",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "code", "type": "string", "required": true }
                      ]
                    },
                    {
                      "name": "Derived",
                      "extends": "Base",
                      "fields": [
                        { "name": "code", "type": "long", "required": true }
                      ]
                    }
                  ]
                }
                """;

        List<String> errors = new SemanticValidator().validate(parseJson(json));
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e ->
                e.contains("duplicate field name in inheritance")
                        || e.contains("ILLEGAL_OVERRIDE")));
    }

    private static ModelAst parseJson(String json) throws Exception {
        Path modelFile = Files.createTempFile("npdev-specialization-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(modelFile);
    }

    private static boolean hasField(List<CompiledField> fields, String name) {
        return fields.stream().anyMatch(f -> f.getName().equals(name));
    }

    private static boolean isUnique(List<CompiledField> fields, String name) {
        return fields.stream().anyMatch(f -> f.getName().equals(name) && f.isUnique());
    }
}

