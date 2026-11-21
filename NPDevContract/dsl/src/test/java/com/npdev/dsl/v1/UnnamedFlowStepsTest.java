package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.StepAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-4 (owner 2026-11-21): a flow step needs only its {@code type}. The platform numbers an unnamed step
 * ({@code <flow>-step-<n>}), nested {@code then}/{@code else} lists get their own prefix so a name never collides,
 * and a model that omits every step name validates exactly like the one that writes them. This is what lets an AI
 * author skip the {@code name} key on every step (8% of the ride-hailing model).
 */
class UnnamedFlowStepsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FLOW_TAIL = """
        "steps": [
          { "type": "readConcept", "scope": "Ticket", "id": "$input.id", "output": "$t" },
          { "type": "branch", "condition": "$t.status == 'OPEN'",
            "then": [ { "type": "updateConcept", "scope": "Ticket", "id": "$t.id", "set": { "status": "CLOSED" }, "output": "$u" } ],
            "else": [ { "type": "updateConcept", "scope": "Ticket", "id": "$t.id", "set": { "status": "OPEN" }, "output": "$u" } ] },
          { "type": "return", "value": "$u" } ]""";

    private static String model(boolean named) {
        String steps = named
                ? FLOW_TAIL.replace("{ \"type\": \"readConcept\"", "{ \"name\": \"load\", \"type\": \"readConcept\"")
                : FLOW_TAIL;
        return """
            { "dslVersion": "3.0.0", "namespace": "d4.names", "version": "1.0",
              "concepts": [ { "name": "Ticket", "fields": [
                 { "name": "id", "type": "uuid", "id": true, "required": true },
                 { "name": "status", "type": "string" } ] } ],
              "flows": [ { "name": "CloseTicket", "concept": "Ticket",
                 "input": { "concept": "Ticket", "mode": "update" }, %s } ] }
            """.formatted(steps);
    }

    @Test
    void anUnnamedStepIsNumberedByItsFlowAndPosition() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(model(false)));
        FlowAst flow = ast.getFlows().get(0);
        List<StepAst> steps = flow.getSteps();
        assertEquals("CloseTicket-step-1", steps.get(0).getName());
        assertEquals("CloseTicket-step-2", steps.get(1).getName());
        assertEquals("CloseTicket-step-3", steps.get(2).getName());
    }

    @Test
    void nestedBranchesGetTheirOwnPrefixSoNamesNeverCollide() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(model(false)));
        StepAst branch = ast.getFlows().get(0).getSteps().get(1);
        String thenName = branch.getThenSteps().get(0).getName();
        String elseName = branch.getElseSteps().get(0).getName();
        assertTrue(thenName.contains(".then") && elseName.contains(".else"), thenName + " / " + elseName);
    }

    @Test
    void aModelWithNoStepNamesValidatesCleanly() throws Exception {
        ValidationResult result = new SemanticValidator()
                .validateWithWarnings(new JsonModelParser().parse(MAPPER.readTree(model(false))));
        assertTrue(result.getErrors().isEmpty(), "got: " + result.getErrors());
    }

    @Test
    void namingOneStepAndLeavingTheRestUnnamedStillValidates() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(model(true)));
        assertEquals("load", ast.getFlows().get(0).getSteps().get(0).getName());
        assertEquals("CloseTicket-step-2", ast.getFlows().get(0).getSteps().get(1).getName());
        ValidationResult result = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(result.getErrors().isEmpty(), "got: " + result.getErrors());
    }
}
