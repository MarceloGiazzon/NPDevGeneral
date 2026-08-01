package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 12 P1.4 (item 2 / REG-101, fix shape (c)): {@code queries[].where} is now refused at
 * AUTHORING time when it cannot compile under the grammar the kernel enforces at runtime -- the
 * durable fix the ledger item asked for, replacing {@code check-query-predicate-compilable.py}'s
 * Python reimplementation of the same grammar (now deleted). Goes through the real
 * {@link JsonModelParser} + {@link SemanticValidator} front door, per the REG-89 lesson.
 *
 * <p>Reproduces REG-101's own corpus witness, {@code pack-sample}'s {@code SalesByStore}
 * ({@code where: "storeId == :storeId"}, {@code parameters: [{name: storeId}]}) as the positive
 * case, and its two negative siblings: a placeholder naming an undeclared parameter, and a
 * predicate the grammar cannot parse at all.
 */
class QueryWhereCompilabilityValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String modelJson(String whereClause, String parametersJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.qwhere", "version": "1.0",
              "concepts": [
                { "name": "Sale", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "storeId", "type": "string" },
                  { "name": "amount", "type": "integer" } ] }
              ],
              "queries": [
                { "name": "SalesByStore", "concept": "Sale", "where": "%s", "parameters": [ %s ] }
              ]
            }
            """.formatted(whereClause, parametersJson);
    }

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static final String STORE_ID_PARAMETER =
            "{ \"name\": \"storeId\", \"type\": \"uuid\", \"required\": true }";

    @Test
    void aBindPlaceholderMatchingADeclaredParameterCompilesClean() throws Exception {
        List<String> errors = validate(modelJson("storeId == :storeId", STORE_ID_PARAMETER));
        assertTrue(errors.stream().noneMatch(e -> e.contains("where")), "unexpected: " + errors);
    }

    @Test
    void aBindPlaceholderWithNoDeclaredParameterIsRejected() throws Exception {
        List<String> errors = validate(modelJson("storeId == :storeId", ""));
        assertTrue(errors.stream().anyMatch(e ->
                        e.contains("bind placeholder :storeId") && e.contains("not declared in this query's parameters[]")),
                "a placeholder naming an undeclared parameter must be refused at authoring time, not left "
                        + "to silently compare every row against the literal \":storeId\" (REG-101): " + errors);
    }

    @Test
    void aGenuinelyUncompilablePredicateIsRejected() throws Exception {
        List<String> errors = validate(modelJson("storeId in ('a','b')", STORE_ID_PARAMETER));
        assertTrue(errors.stream().anyMatch(e -> e.contains("where cannot be compiled")), "expected: " + errors);
    }

    @Test
    void anOrdinaryLiteralWhereWithNoPlaceholderStillCompilesClean() throws Exception {
        List<String> errors = validate(modelJson("storeId == 'store-a'", ""));
        assertTrue(errors.stream().noneMatch(e -> e.contains("where")), "unexpected: " + errors);
    }
}
