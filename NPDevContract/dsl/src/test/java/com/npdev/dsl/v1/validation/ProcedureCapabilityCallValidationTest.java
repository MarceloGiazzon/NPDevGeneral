package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** LIFT-QUERY-P3: a `callCapability` procedure step's capability/operation/arity is validated. */
class ProcedureCapabilityCallValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String modelJson(String capabilityJson, String argsJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.qcap", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "cliente", "type": "string" } ] }
              ],
              "queries": [
                { "name": "OrdersByCliente", "concept": "Order", "where": "cliente == 'acme'" }
              ],
              "capabilities": [ %s ],
              "procedures": [
                { "name": "SumOrders", "steps": [
                    { "name": "q", "type": "runQuery", "query": "OrdersByCliente", "concept": "Order", "target": "rows" },
                    { "name": "sum", "type": "callCapability", "capability": "Totals",
                      "operation": "sum", "args": %s, "target": "summary" }
                ] }
              ]
            }
            """.formatted(capabilityJson, argsJson);
    }

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static final String ONE_ARG_CAPABILITY = """
        { "name": "Totals", "operations": [ { "name": "sum",
            "input": { "type": "object", "properties": { "rows": { "type": "string" } } } } ] }
        """;

    @Test
    void matchingArityPasses() throws Exception {
        List<String> errors = validate(modelJson(ONE_ARG_CAPABILITY, "{\"rows\": \"rows\"}"));
        assertTrue(errors.stream().noneMatch(e -> e.contains("callCapability") || e.contains("expects")),
                "unexpected errors: " + errors);
    }

    @Test
    void mismatchedArityIsRejected() throws Exception {
        List<String> errors = validate(modelJson(ONE_ARG_CAPABILITY, "{\"rows\": \"rows\", \"extra\": \"bogus\"}"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("expects 1 arg(s)") && e.contains("but this call supplies 2")),
                "expected an arity-mismatch error, got: " + errors);
    }

    @Test
    void unknownCapabilityIsRejected() throws Exception {
        List<String> errors = validate(modelJson(ONE_ARG_CAPABILITY.replace("Totals", "OtherCapability"), "{\"rows\": \"rows\"}"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("capability not found: Totals")),
                "expected a capability-not-found error, got: " + errors);
    }

    @Test
    void unknownOperationIsRejected() throws Exception {
        List<String> errors = validate(modelJson(
                "{ \"name\": \"Totals\", \"operations\": [ { \"name\": \"count\", "
                        + "\"input\": { \"type\": \"object\", \"properties\": { \"rows\": { \"type\": \"string\" } } } } ] }",
                "{\"rows\": \"rows\"}"
        ));
        assertTrue(errors.stream().anyMatch(e -> e.contains("has no operation named sum")),
                "expected an unknown-operation error, got: " + errors);
    }
}
