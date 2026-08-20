package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RUN-22 (R5.8, Roadmap Collection 2026-08-18, "Effective-dated values"): a concept declaring
 * {@code temporal: true} must also declare its own {@code validFrom}/{@code validTo} fields
 * (type: date, required: true) -- the generic concept-CRUD read endpoint's {@code asOf} handling
 * (business-concept-crud-controller.mustache) filters unconditionally on those two literal field
 * names, so a temporal concept missing either would silently 400 or silently return nothing at
 * read time instead of failing loudly here, at author time.
 */
class ConceptTemporalValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static String modelWithConceptFields(String extraFieldsJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "temporal.validation", "version": "1.0",
              "concepts": [
                { "name": "WidgetPrice", "temporal": true, "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true }
                  %s
                ] }
              ]
            }
            """.formatted(extraFieldsJson);
    }

    @Test
    void temporalConceptMissingBothValidFromAndValidToFailsWithBothNamedErrors() throws Exception {
        List<String> errors = validate(modelWithConceptFields(""));
        assertTrue(
                errors.stream().anyMatch(e -> e.contains("WidgetPrice") && e.contains("\"validFrom\"")
                        && e.contains("temporal:true requires")),
                "expected a named error for the missing validFrom field, got: " + errors);
        assertTrue(
                errors.stream().anyMatch(e -> e.contains("WidgetPrice") && e.contains("\"validTo\"")
                        && e.contains("temporal:true requires")),
                "expected a named error for the missing validTo field, got: " + errors);
    }

    @Test
    void temporalConceptWithWrongTypeValidFromFails() throws Exception {
        List<String> errors = validate(modelWithConceptFields("""
            , { "name": "validFrom", "type": "string", "required": true },
              { "name": "validTo", "type": "date", "required": true }
            """));
        assertTrue(
                errors.stream().anyMatch(e -> e.contains("validFrom") && e.contains("type: date")),
                "expected a type error for validFrom declared as string, got: " + errors);
    }

    @Test
    void temporalConceptWithOptionalValidToFails() throws Exception {
        List<String> errors = validate(modelWithConceptFields("""
            , { "name": "validFrom", "type": "date", "required": true },
              { "name": "validTo", "type": "date", "required": false }
            """));
        assertTrue(
                errors.stream().anyMatch(e -> e.contains("validTo") && e.contains("required:true")),
                "expected a required:true error for an optional validTo, got: " + errors);
    }

    @Test
    void temporalConceptWithValidWindowFieldsPasses() throws Exception {
        List<String> errors = validate(modelWithConceptFields("""
            , { "name": "validFrom", "type": "date", "required": true },
              { "name": "validTo", "type": "date", "required": true }
            """));
        assertTrue(errors.stream().noneMatch(e -> e.contains("temporal:true requires")),
                "a concept correctly declaring validFrom/validTo should pass, got: " + errors);
    }

    @Test
    void nonTemporalConceptIsNeverCheckedEvenWithoutValidFromValidTo() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "temporal.validation", "version": "1.0",
              "concepts": [
                { "name": "OrdinaryThing", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "amount", "type": "decimal", "required": true }
                ] }
              ]
            }
            """;
        List<String> errors = validate(json);
        assertTrue(errors.stream().noneMatch(e -> e.contains("temporal:true requires")),
                "a concept that never opts into temporal:true must never be checked, got: " + errors);
    }
}
