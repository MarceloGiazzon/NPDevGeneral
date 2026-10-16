package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B13 conversions: unknown concept/field/shape is a coded semantic error with a path and suggestedFix. */
class ConversionValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String model(String conversions) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "demo.conversion", "version": "1.0",
              "concepts": [
                { "name": "Customer", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "name", "type": "string" },
                  { "name": "firstName", "type": "string" },
                  { "name": "lastName", "type": "string" },
                  { "name": "displayName", "type": "string" } ] }
              ],
              "conversions": %s
            }
            """.formatted(conversions);
    }

    private static List<ValidationDiagnostic> errors(String conversions) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(model(conversions)));
        return new SemanticValidator().validateWithWarnings(ast).getDiagnostics().stream()
                .filter(d -> d.getSeverity() == ValidationSeverity.ERROR).toList();
    }

    private static String describe(List<ValidationDiagnostic> found) {
        return found.stream().map(d -> d.getCode() + "@" + d.getPath() + " " + d.getMessage()).toList().toString();
    }

    @Test
    void validConversionProducesNoErrors() throws Exception {
        assertEquals(List.of(), errors("""
            [ { "id": "c1", "concept": "Customer", "op": "copy", "from": "name", "to": "displayName" } ]"""));
    }

    @Test
    void misspelledFieldIsOneCodedErrorWithPathAndClosestFieldSuggestion() throws Exception {
        List<ValidationDiagnostic> found = errors("""
            [ { "id": "c1", "concept": "Customer", "op": "copy", "from": "nmae", "to": "displayName" } ]""");
        assertEquals(1, found.size(), describe(found));
        ValidationDiagnostic diagnostic = found.get(0);
        assertEquals("conversion_unknown_field", diagnostic.getCode());
        assertEquals("conversions[0].from", diagnostic.getPath());
        assertEquals("B13", diagnostic.getBoundaryId());
        assertTrue(diagnostic.getSuggestedFix().contains("'name'"), diagnostic.getSuggestedFix());
    }

    @Test
    void unknownConceptIsCodedAndSuggestsTheClosestConcept() throws Exception {
        List<ValidationDiagnostic> found = errors("""
            [ { "id": "c1", "concept": "Custmer", "op": "copy", "from": "name", "to": "displayName" } ]""");
        assertEquals(1, found.size(), describe(found));
        assertEquals("conversion_unknown_concept", found.get(0).getCode());
        assertEquals("conversions[0].concept", found.get(0).getPath());
        assertTrue(found.get(0).getSuggestedFix().contains("'Customer'"), found.get(0).getSuggestedFix());
    }

    @Test
    void javaHookClaimNamingAnUnknownFieldIsPathedToTheClaim() throws Exception {
        List<ValidationDiagnostic> found = errors("""
            [ { "id": "c1", "concept": "Customer", "claims": [ "displayName", "ghost" ],
                "javaHook": { "source": "hooks/src/main/java", "class": "demo.Hook", "method": "run" } } ]""");
        assertEquals(1, found.size(), describe(found));
        assertEquals("conversion_unknown_field", found.get(0).getCode());
        assertEquals("conversions[0].claims[1]", found.get(0).getPath());
    }

    @Test
    void everyUnknownSourceFieldOfAMergeIsPathedToItsIndex() throws Exception {
        List<ValidationDiagnostic> found = errors("""
            [ { "id": "c1", "concept": "Customer", "op": "merge", "from": [ "firstNme", "lastNme" ],
                "to": "displayName" } ]""");
        assertEquals(List.of(
                "conversion_unknown_field@conversions[0].from[0]",
                "conversion_unknown_field@conversions[0].from[1]"),
                found.stream().map(d -> d.getCode() + "@" + d.getPath()).toList());
    }
}
