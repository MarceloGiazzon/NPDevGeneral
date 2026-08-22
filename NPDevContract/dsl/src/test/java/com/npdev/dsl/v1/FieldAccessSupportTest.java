package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.FieldAccessAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledFieldAccess;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationResult;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5.5 (roadmap Wave 1, 2026-08-19): {@code field.access: {read, write}} threaded end to end --
 * parse (JsonModelParser -> FieldAst.getAccess()), compile (ModelCompiler -> CompiledField.getAccess()),
 * and compile-time validation (ConceptValidation.validateFieldAccessRules) -- the same shape/grammar
 * {@code concept.access} already gets, one rung down the role-ceiling -> row-scope -> field-scope
 * ladder. Mirrors {@code InteractionMetadataSupportTest}'s parser+compiler+validator pattern.
 */
class FieldAccessSupportTest {

    @Test
    void parserCompilerAndValidatorSupportFieldAccess() throws Exception {
        Path modelPath = Files.createTempFile("npdev-field-access-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "field.access.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Payroll",
                      "ui": { "label": "Payroll" },
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "employeeName", "type": "string", "required": true },
                        {
                          "name": "salary",
                          "type": "decimal",
                          "precision": 12,
                          "scale": 2,
                          "ui": { "label": "Salary" },
                          "access": {
                            "read": "$user.roles == 'MANAGER'",
                            "write": "$user.roles == 'MANAGER'"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        FieldAccessAst parsedAccess = ast.getConcepts().get(0).getFields().get(2).getAccess();
        assertEquals("$user.roles == 'MANAGER'", parsedAccess.getRead());
        assertEquals("$user.roles == 'MANAGER'", parsedAccess.getWrite());

        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(validation.getErrors().isEmpty(), "Expected no semantic errors, got: " + validation.getErrors());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledField compiledSalary = compiled.getConcepts().iterator().next().getFields().stream()
                .filter(field -> "salary".equals(field.getName()))
                .findFirst()
                .orElseThrow();
        CompiledFieldAccess compiledAccess = compiledSalary.getAccess();
        assertEquals("$user.roles == 'MANAGER'", compiledAccess.getRead());
        assertEquals("$user.roles == 'MANAGER'", compiledAccess.getWrite());

        CompiledField compiledEmployeeName = compiled.getConcepts().iterator().next().getFields().stream()
                .filter(field -> "employeeName".equals(field.getName()))
                .findFirst()
                .orElseThrow();
        assertNull(compiledEmployeeName.getAccess(), "a field with no declared access must compile to null, not an empty rule");
    }

    @Test
    void fieldAccessReferencingAnUnknownFieldIsRejectedAtCompileTime() throws Exception {
        Path modelPath = Files.createTempFile("npdev-field-access-invalid-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "field.access.invalid.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Payroll",
                      "ui": { "label": "Payroll" },
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "salary",
                          "type": "decimal",
                          "precision": 12,
                          "scale": 2,
                          "access": {
                            "write": "doesNotExist == 'MANAGER'"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);

        assertFalse(validation.getErrors().isEmpty(), "an access.write rule referencing an unknown field must be rejected");
        assertTrue(
                validation.getErrors().stream().anyMatch(error -> error.contains("doesNotExist")),
                "Expected an error naming the unknown field, got: " + validation.getErrors()
        );
    }
}
