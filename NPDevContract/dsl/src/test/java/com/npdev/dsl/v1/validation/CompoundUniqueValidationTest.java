package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** LIFT-UNIQUE-P1: multi-field `unique` invariants compile and validate. */
class CompoundUniqueValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String modelJson(String fieldsJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.uq", "version": "1.0",
              "concepts": [
                { "name": "Membership", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "tenantId", "type": "string" },
                  { "name": "email", "type": "string" } ],
                  "invariants": [ { "name": "uq1", "type": "unique", "fields": %s } ] }
              ]
            }
            """.formatted(fieldsJson);
    }

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    @Test
    void compoundUniqueCompilesAndValidates() throws Exception {
        String json = modelJson("[\"tenantId\", \"email\"]");
        List<String> errors = validate(json);
        assertTrue(errors.isEmpty(), "unexpected errors: " + errors);

        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        CompiledModel compiled = new ModelCompiler().compile(ast);
        Optional<CompiledInvariant> uniqueInvariant = compiled.getConcepts().stream()
                .findFirst().orElseThrow().getInvariants().stream()
                .filter(inv -> "unique".equalsIgnoreCase(inv.getType()))
                .findFirst();
        assertTrue(uniqueInvariant.isPresent(), "expected a compiled unique invariant");
        assertEquals(List.of("tenantId", "email"), uniqueInvariant.get().getFields());
    }

    @Test
    void emptyFieldsIsRejected() throws Exception {
        List<String> errors = validate(modelJson("[]"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("invariant unique: must declare fields")),
                "expected a must-declare-fields error, got: " + errors);
    }

    @Test
    void duplicateFieldIsRejected() throws Exception {
        List<String> errors = validate(modelJson("[\"email\", \"email\"]"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("duplicate field")),
                "expected a duplicate-field error, got: " + errors);
    }

    @Test
    void unknownFieldIsRejected() throws Exception {
        List<String> errors = validate(modelJson("[\"email\", \"bogus\"]"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("references unknown field bogus")),
                "expected an unknown-field error, got: " + errors);
    }

    @Test
    void singleFieldUniqueStillCompilesAsBefore() throws Exception {
        String json = modelJson("[\"email\"]");
        List<String> errors = validate(json);
        assertTrue(errors.isEmpty(), "unexpected errors: " + errors);

        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        CompiledModel compiled = new ModelCompiler().compile(ast);
        boolean fieldMarkedUnique = compiled.getConcepts().stream()
                .findFirst().orElseThrow().getFields().stream()
                .anyMatch(f -> "email".equalsIgnoreCase(f.getName()) && f.isUnique());
        assertTrue(fieldMarkedUnique, "single-field unique should still mark CompiledField.unique");
    }
}
