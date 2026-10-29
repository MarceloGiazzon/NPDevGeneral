package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiled.CompiledProcedureParameter;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REG-313 (b): a query parameter may declare a {@code defaultExpression} -- {@code addDays(today(), -7)}
 * -- that the runtime evaluates when the caller does not supply the parameter. It must survive parse ->
 * compile -> canonical JSON (the generator only ever reads canonical JSON), and validation must refuse
 * the forms the runtime cannot honour: a field reference (there is no record in scope), a function the
 * runtime does not have, a syntax error, and a procedure parameter (procedure inputs are not bound by
 * this rule, so a default there would be silently ignored).
 */
class QueryParameterDefaultExpressionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String modelJson(String queryParam, String procedureParam) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "trial.overdue", "version": "1.0",
              "concepts": [
                { "name": "Visit", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "dueDate", "type": "date" } ] }
              ],
              "queries": [
                { "name": "OverdueVisits", "concept": "Visit", "where": "dueDate < :asOf",
                  "parameters": [ %s ] }
              ],
              "procedures": [
                { "name": "Touch", "parameters": [ %s ],
                  "steps": [ { "name": "r", "type": "return", "value": "$input.x" } ] }
              ]
            }
            """.formatted(queryParam, procedureParam);
    }

    private static final String PLAIN_PROC_PARAM = "{ \"name\": \"x\", \"type\": \"string\" }";

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static String asOf(String expression) {
        return "{ \"name\": \"asOf\", \"type\": \"date\", \"defaultExpression\": \"" + expression + "\" }";
    }

    @Test
    void theDefaultSurvivesParseCompileAndTheCanonicalJsonRoundTrip() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(
                modelJson(asOf("addDays(today(), -7)"), PLAIN_PROC_PARAM)));
        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertEquals("addDays(today(), -7)", parameter(compiled).defaultExpression(), "after compile");

        String json = CompiledModelCanonicalJson.toJson(compiled);
        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(json);
        assertEquals("addDays(today(), -7)", parameter(restored).defaultExpression(), "after canonical JSON");
        assertEquals(json, CompiledModelCanonicalJson.toJson(restored), "canonical JSON stays idempotent");
    }

    @Test
    void aParameterWithoutADefaultKeepsTheCanonicalJsonItAlwaysHad() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(modelJson(
                "{ \"name\": \"asOf\", \"type\": \"date\", \"required\": true }", PLAIN_PROC_PARAM)));
        String json = CompiledModelCanonicalJson.toJson(new ModelCompiler().compile(ast));
        com.fasterxml.jackson.databind.JsonNode node = parameterNode(MAPPER.readTree(json), "asOf");
        assertNotNull(node, "the asOf parameter must be in the canonical JSON");
        assertFalse(node.has("defaultExpression"),
                "an absent default must not appear in canonical JSON (it would change every existing model's output)");
        assertNull(parameter(CompiledModelCanonicalJsonReader.fromJson(json)).defaultExpression());
    }

    /** The first object named {@code name} that looks like a parameter (it has a "required" flag). */
    private static com.fasterxml.jackson.databind.JsonNode parameterNode(
            com.fasterxml.jackson.databind.JsonNode root, String name) {
        if (root.isObject() && name.equals(root.path("name").asText()) && root.has("required")) {
            return root;
        }
        for (com.fasterxml.jackson.databind.JsonNode child : root) {
            com.fasterxml.jackson.databind.JsonNode found = parameterNode(child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void aValidDefaultPassesValidation() throws Exception {
        assertEquals(List.of(), validate(modelJson(asOf("addDays(today(), -7)"), PLAIN_PROC_PARAM)));
        assertEquals(List.of(), validate(modelJson(asOf("today()"), PLAIN_PROC_PARAM)));
        assertEquals(List.of(), validate(modelJson(asOf("'2026-01-01'"), PLAIN_PROC_PARAM)));
    }

    @Test
    void aFieldReferenceIsRefusedBecauseNoRecordIsInScope() throws Exception {
        List<String> errors = validate(modelJson(asOf("addDays(dueDate, 1)"), PLAIN_PROC_PARAM));
        assertTrue(errors.stream().anyMatch(e -> e.contains("asOf") && e.contains("dueDate")
                && e.contains("defaultExpression")), "got: " + errors);
    }

    @Test
    void anUnavailableFunctionIsRefused() throws Exception {
        List<String> errors = validate(modelJson(asOf("addMonths(today(), 1)"), PLAIN_PROC_PARAM));
        assertTrue(errors.stream().anyMatch(e -> e.contains("asOf") && e.contains("addMonths")), "got: " + errors);
    }

    @Test
    void aSyntaxErrorIsRefused() throws Exception {
        List<String> errors = validate(modelJson(asOf("addDays(today(,"), PLAIN_PROC_PARAM));
        assertTrue(errors.stream().anyMatch(e -> e.contains("asOf") && e.contains("defaultExpression")),
                "got: " + errors);
    }

    @Test
    void aRequiredParameterCannotAlsoCarryADefault() throws Exception {
        List<String> errors = validate(modelJson(
                "{ \"name\": \"asOf\", \"type\": \"date\", \"required\": true, \"defaultExpression\": \"today()\" }",
                PLAIN_PROC_PARAM));
        assertTrue(errors.stream().anyMatch(e -> e.contains("asOf") && e.contains("required")
                && e.contains("defaultExpression")), "got: " + errors);
    }

    @Test
    void aProcedureParameterDefaultIsRefusedBecauseNothingWouldEvaluateIt() throws Exception {
        List<String> errors = validate(modelJson(asOf("today()"),
                "{ \"name\": \"x\", \"type\": \"string\", \"defaultExpression\": \"today()\" }"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("Procedure Touch") && e.contains("defaultExpression")
                && e.contains("query")), "got: " + errors);
    }

    private static CompiledProcedureParameter parameter(CompiledModel model) {
        return model.getQueries().get(0).parameters().get(0);
    }
}
