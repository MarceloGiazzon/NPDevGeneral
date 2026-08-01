package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REG-98 (MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 0.1, fix shape (a)): two differently-named
 * concepts must not be able to silently derive the same physical table name.
 *
 * <p>{@code fieldCollision} below is the exact repro from the ledger item: two concepts, one field
 * each, that previously validated with 0 errors while both compiling to the SQL table
 * {@code order_lines}.
 */
class TableNameCollisionValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static String modelWithConceptNames(String firstName, String secondName) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.tablecollision", "version": "1.0",
              "concepts": [
                { "name": "%s", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "%s", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """.formatted(firstName, secondName);
    }

    @Test
    void spaceVersusCamelCaseConceptNamesCollideOnOrderLines() throws Exception {
        List<String> errors = validate(modelWithConceptNames("OrderLine", "Order Line"));
        assertTrue(
                errors.stream().anyMatch(e -> e.contains("OrderLine") && e.contains("Order Line")
                        && e.contains("order_lines")),
                "expected a table-name collision error naming both concepts and \"order_lines\", got: " + errors);
    }

    @Test
    void hyphenAndDotVariantsAlsoCollide() throws Exception {
        List<String> errors = validate(modelWithConceptNames("Order-Line", "Order.Line"));
        assertTrue(
                errors.stream().anyMatch(e -> e.contains("Order-Line") && e.contains("Order.Line")
                        && e.contains("order_lines")),
                "expected a table-name collision error for hyphen/dot variants, got: " + errors);
    }

    @Test
    void distinctConceptNamesWithDistinctTablesPass() throws Exception {
        List<String> errors = validate(modelWithConceptNames("OrderLine", "OrderHeader"));
        assertTrue(errors.stream().noneMatch(e -> e.contains("physical table name")),
                "unexpected table-name collision error for genuinely distinct concepts, got: " + errors);
    }

    @Test
    void sameConceptNameIsStillReportedAsDuplicateConceptNotTableCollision() throws Exception {
        List<String> errors = validate(modelWithConceptNames("OrderLine", "OrderLine"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("Duplicate concept name")),
                "expected the existing duplicate-concept-name error, got: " + errors);
        assertTrue(errors.stream().noneMatch(e -> e.contains("physical table name")),
                "a same-name duplicate should be reported once, by the existing duplicate-name check, "
                        + "not again as a table collision, got: " + errors);
    }
}
