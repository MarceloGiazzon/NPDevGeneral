package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REG-313 (b): model validation accepts {@code today()}/{@code addDays}/{@code daysBetween} in a field
 * default, a derived field and an invariant -- and, for the same reason REG-284 exists, still refuses a
 * date function the runtime does not have (it used to validate clean and evaluate to null at save time).
 */
class DateFunctionsValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String modelJson(String windowStartExpr, String dueDateDefault, String invariantExpr) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "trial.visits", "version": "1.0",
              "concepts": [
                { "name": "Visit",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "enrolmentDate", "type": "date" },
                    { "name": "visitDate", "type": "date" },
                    { "name": "windowStart", "type": "date", "derivedExpression": "%s" },
                    { "name": "dueDate", "type": "date", "defaultExpression": "%s" } ],
                  "invariants": [ { "name": "VisitInWindow", "expr": "%s" } ] }
              ]
            }
            """.formatted(windowStartExpr, dueDateDefault, invariantExpr);
    }

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    @Test
    void theDateFunctionsAreAcceptedInDefaultsDerivedFieldsAndInvariants() throws Exception {
        List<String> errors = validate(modelJson(
                "addDays(enrolmentDate, 77)",
                "addDays(today(), 7)",
                "visitDate >= addDays(enrolmentDate, 77) && daysBetween(enrolmentDate, visitDate) <= 91 && visitDate <= today()"));
        assertEquals(List.of(), errors);
    }

    @Test
    void aDateFunctionTheRuntimeDoesNotHaveIsStillRefusedAndTheMessageNamesTheRealOnes() throws Exception {
        List<String> errors = validate(modelJson(
                "addMonths(enrolmentDate, 3)",
                "addDays(today(), 7)",
                "visitDate >= enrolmentDate"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("addMonths")
                        && e.contains("addDays") && e.contains("daysBetween") && e.contains("today")),
                "expected addMonths refused with the available list naming the date functions, got: " + errors);
    }
}
