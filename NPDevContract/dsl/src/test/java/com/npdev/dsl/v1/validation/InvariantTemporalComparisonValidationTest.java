package com.npdev.dsl.v1.validation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** REG-269: ordering a date against something that is not a date is refused, never silently accepted. */
class InvariantTemporalComparisonValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String expression) throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "hr.leave", "version": "1.0",
              "concepts": [
                { "name": "Request", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "startDate", "type": "date" },
                  { "name": "endDate", "type": "date" },
                  { "name": "stamp", "type": "datetime" },
                  { "name": "days", "type": "decimal" },
                  { "name": "note", "type": "string" } ],
                  "invariants": [ { "name": "inv1", "type": "expression", "expression": "%s" } ] }
              ]
            }
            """.formatted(expression);
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static boolean refused(List<String> errors) {
        return errors.stream().anyMatch(e -> e.contains("compares a date field"));
    }

    @Test
    void dateAgainstDateIsAccepted() throws Exception {
        assertTrue(!refused(validate("endDate >= startDate")));
        assertTrue(!refused(validate("endDate == null || endDate >= startDate")));
        assertTrue(!refused(validate("stamp > endDate")));
    }

    @Test
    void dateAgainstIsoLiteralIsAccepted() throws Exception {
        assertTrue(!refused(validate("startDate >= '2026-01-01'")));
    }

    @Test
    void dateAgainstANumberIsRefused() throws Exception {
        assertTrue(refused(validate("endDate >= 5")), "date vs number must be refused");
    }

    @Test
    void dateAgainstANonDateFieldIsRefused() throws Exception {
        assertTrue(refused(validate("endDate >= days")), "date vs decimal field must be refused");
        assertTrue(refused(validate("note < startDate")), "string field vs date must be refused");
    }

    @Test
    void dateAgainstFreeTextIsRefused() throws Exception {
        assertTrue(refused(validate("endDate > 'tomorrow'")));
    }

    @Test
    void numericComparisonsAreNotAffected() throws Exception {
        assertTrue(!refused(validate("days > 0")));
    }
}
