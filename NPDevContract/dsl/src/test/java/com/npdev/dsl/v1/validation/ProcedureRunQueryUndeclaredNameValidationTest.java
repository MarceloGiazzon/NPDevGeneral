package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 12 P1.2 (item 1 / REG-100 X0-7): a {@code runQuery} procedure step naming a query that is
 * not declared in the model used to be a runtime-only concern (the kernel now refuses it with
 * {@code QUERY_NOT_FOUND}, proven by {@code DefaultProcedureExecutorRunQueryTest} in
 * {@code NPDevKernel}). The model-level door was believed shut too
 * ({@code PackValidation.validateProcedureSteps}'s {@code PROCEDURE_QUERY_STEP_TYPES} check), but
 * per the REG-89 lesson -- kernel-only tests build a {@code ProcedureStep} directly and can never see
 * a validator gap -- that belief had no test going through the real {@link JsonModelParser} +
 * {@link SemanticValidator} front door. This is that test.
 */
class ProcedureRunQueryUndeclaredNameValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String modelJson(String queryName) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.runquery", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "total", "type": "integer" } ] }
              ],
              "queries": [
                { "name": "OrdersByTotal", "concept": "Order", "where": "total > 0" }
              ],
              "procedures": [
                { "name": "ListOrders", "steps": [
                    { "name": "q", "type": "runQuery", "query": "%s", "concept": "Order", "target": "rows" }
                ] }
              ]
            }
            """.formatted(queryName);
    }

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    @Test
    void runQueryNamingADeclaredQueryPasses() throws Exception {
        List<String> errors = validate(modelJson("OrdersByTotal"));
        assertTrue(errors.stream().noneMatch(e -> e.contains("query not found")), "unexpected: " + errors);
    }

    @Test
    void runQueryNamingAnUndeclaredQueryIsRejectedAtCompileTime() throws Exception {
        List<String> errors = validate(modelJson("NotDeclared"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("query not found: NotDeclared")),
                "a runQuery step naming an undeclared query must be refused at authoring time, not left "
                        + "to fall through to an unfiltered runtime list (X0-7): " + errors);
    }
}
