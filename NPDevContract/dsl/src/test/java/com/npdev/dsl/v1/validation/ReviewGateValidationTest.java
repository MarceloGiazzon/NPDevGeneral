package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AI7-1: {@code externalAi.prompts[].review} and {@code task.proposal} -- the gate the validator enforces. */
class ReviewGateValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PROPOSAL_TASK = """
            "task": {
              "title": "Review ${input.id}",
              "assignee": { "roles": ["ADMIN"] },
              "outcomeField": "decision",
              "outcomes": [ { "value": "APPROVED" }, { "value": "REJECTED" } ],
              "proposal": { "value": "$answer", "current": "$input" }
            }
            """;

    private static final String PLAIN_TASK = """
            "task": {
              "title": "Review ${input.id}",
              "assignee": { "roles": ["ADMIN"] },
              "outcomes": [ { "value": "APPROVED" } ]
            }
            """;

    private static String model(String review, String stepsAfterAsk, String procedureCall) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "demo.review", "version": "1.0",
              "concepts": [
                { "name": "Mosaic", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ],
              "capabilities": [ { "name": "externalAi", "type": "ExternalAiCapability", "operations": ["generate"] } ],
              "bindings": [ { "capability": "externalAi", "adapter": "externalAi" } ],
              "events": [ { "name": "Reviewed", "payload": [] } ],
              "procedures": [ { "name": "AskInProcedure", "steps": [ %s
                { "name": "done", "type": "return", "value": "$input" } ] } ],
              "flows": [ { "name": "SuggestFlow", "input": { "concept": "Mosaic", "mode": "query" }, "steps": [
                { "name": "ask", "type": "capabilityCall", "capability": "externalAi", "operation": "generate",
                  "args": [ "Paint", "$input" ], "output": "$answer" },
                %s
                { "name": "done", "type": "return", "value": "$answer" } ] } ],
              "externalAi": { "egress": "apiEnabled", "vendors": ["gemini"], "prompts": [
                { "name": "Paint", "vendor": "gemini", "template": "Paint {{id}}"%s,
                  "outputSchema": { "type": "object", "properties": { "x": { "type": "string" } } } } ] }
            }
            """.formatted(procedureCall, stepsAfterAsk, review);
    }

    private static final String NO_PROCEDURE_CALL = """
            { "name": "noop", "type": "mapValue", "value": "$input", "target": "x" },""";

    private static final String PROCEDURE_ASK = """
            { "name": "ask-p", "type": "capabilityCall", "capability": "externalAi", "operation": "generate",
              "args": { "prompt": "Paint", "input": "$input" }, "target": "answer" },""";

    private static final String AWAIT = """
            { "name": "await-review", "type": "awaitEvent", "awaitEvent": "Reviewed", "awaitRef": "review",
              "match": { "correlation": true }, %s },""";

    private static ModelAst parse(String json) throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    private static List<String> errors(String json) throws Exception {
        return new SemanticValidator().validate(parse(json)).stream()
                .filter(e -> e.contains("review") || e.contains("proposal")).toList();
    }

    @Test
    void requiredPromptFollowedByItsProposalTaskPasses() throws Exception {
        assertEquals(List.of(), errors(model(", \"review\": \"required\"",
                AWAIT.formatted(PROPOSAL_TASK), NO_PROCEDURE_CALL)));
    }

    @Test
    void requiredPromptWithoutAnyReviewStepIsRejected() throws Exception {
        List<String> errors = errors(model(", \"review\": \"required\"", "", NO_PROCEDURE_CALL));
        assertTrue(errors.stream().anyMatch(e -> e.contains("review: required, so the next step must be an awaitEvent")),
                errors.toString());
    }

    @Test
    void requiredPromptWithATaskThatDoesNotPresentTheOutputIsRejected() throws Exception {
        List<String> errors = errors(model(", \"review\": \"required\"",
                AWAIT.formatted(PLAIN_TASK), NO_PROCEDURE_CALL));
        assertTrue(errors.stream().anyMatch(e -> e.contains("proposal.value is '$answer'")), errors.toString());
    }

    @Test
    void aStepBetweenTheCallAndTheReviewIsRejected() throws Exception {
        String between = "{ \"name\": \"peek\", \"type\": \"map\", \"input\": \"$answer\", \"output\": \"$copy\" },"
                + AWAIT.formatted(PROPOSAL_TASK);
        List<String> errors = errors(model(", \"review\": \"required\"", between, NO_PROCEDURE_CALL));
        assertTrue(errors.stream().anyMatch(e -> e.contains("review: required")), errors.toString());
    }

    @Test
    void optionalAndNonePromptsNeedNoGate() throws Exception {
        assertEquals(List.of(), errors(model(", \"review\": \"optional\"", "", NO_PROCEDURE_CALL)));
        assertEquals(List.of(), errors(model("", "", NO_PROCEDURE_CALL)));
    }

    @Test
    void aProcedureCannotCallARequiredPrompt() throws Exception {
        List<String> errors = errors(model(", \"review\": \"required\"",
                AWAIT.formatted(PROPOSAL_TASK), PROCEDURE_ASK));
        assertTrue(errors.stream().anyMatch(e -> e.contains("a procedure cannot wait for a human")), errors.toString());
    }

    @Test
    void anUnknownReviewModeIsRejectedBySchema() {
        String json = model(", \"review\": \"sometimes\"", "", NO_PROCEDURE_CALL);
        assertThrows(ModelSchemaValidationException.class, () -> parse(json));
    }

    @Test
    void proposalRefsMustBeFlowStateRefs() throws Exception {
        String task = PROPOSAL_TASK.replace("\"$answer\"", "\"answer\"");
        List<String> errors = errors(model("", AWAIT.formatted(task), NO_PROCEDURE_CALL));
        assertTrue(errors.stream().anyMatch(e -> e.contains("task.proposal.value 'answer' is not a flow-state ref")),
                errors.toString());
    }

    @Test
    void aTaskFieldMayNotShadowTheStampedProposalKey() throws Exception {
        String task = PROPOSAL_TASK.replace("\"outcomeField\"",
                "\"fields\": [ { \"name\": \"proposal\", \"type\": \"string\" } ], \"outcomeField\"");
        List<String> errors = errors(model("", AWAIT.formatted(task), NO_PROCEDURE_CALL));
        assertTrue(errors.stream().anyMatch(e -> e.contains("clashes with a payload key a task.proposal stamps")),
                errors.toString());
    }

    @Test
    void reviewAndProposalSurviveCompileAndCanonicalRoundTrip() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse(model(", \"review\": \"required\"",
                AWAIT.formatted(PROPOSAL_TASK), NO_PROCEDURE_CALL)));
        String json = CompiledModelCanonicalJson.toJson(compiled);
        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(json);
        assertEquals("required", restored.getExternalAi().findPrompt("Paint").orElseThrow().review());
        CompiledFlowStep step = restored.getFlows().get(0).getSteps().stream()
                .filter(s -> "await-review".equals(s.getName())).findFirst().orElseThrow();
        assertNotNull(step.getTask());
        assertEquals("$answer", step.getTask().getProposalValue());
        assertEquals("$input", step.getTask().getProposalCurrent());
    }

    @Test
    void aModelWithoutReviewKeepsItsCanonicalJsonFreeOfBothKeys() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse(model("",
                AWAIT.formatted(PLAIN_TASK), NO_PROCEDURE_CALL)));
        String json = CompiledModelCanonicalJson.toJson(compiled);
        assertFalse(json.contains("\"review\":"), "review=none must not appear in canonical JSON");
        assertFalse(json.contains("\"proposal\":"), "no proposal block must not appear in canonical JSON");
        assertEquals("none", CompiledModelCanonicalJsonReader.fromJson(json)
                .getExternalAi().findPrompt("Paint").orElseThrow().review());
    }

    @Test
    void aProposalCurrentThatIsNotARefAndAReservedOutcomeFieldAreRejected() throws Exception {
        String task = PROPOSAL_TASK.replace("\"current\": \"$input\"", "\"current\": \"input\"")
                .replace("\"outcomeField\": \"decision\"", "\"outcomeField\": \"recordId\"");
        List<String> errors = errors(model("", AWAIT.formatted(task), NO_PROCEDURE_CALL));
        assertTrue(errors.stream().anyMatch(e -> e.contains("task.proposal.current 'input' is not a flow-state ref")),
                errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("task.outcomeField 'recordId' clashes")), errors.toString());
    }

    @Test
    void aRequiredPromptCallWithNoOutputHasNothingToReview() throws Exception {
        String json = model(", \"review\": \"required\"", AWAIT.formatted(PROPOSAL_TASK), NO_PROCEDURE_CALL)
                .replace(", \"output\": \"$answer\"", "");
        List<String> errors = errors(json);
        assertTrue(errors.stream().anyMatch(e -> e.contains("has no output to review")), errors.toString());
    }

    @Test
    void theOlderConstructorsKeepWorkingAndDefaultToNoReviewAndNoProposal() {
        var prompt = new com.npdev.dsl.v1.ast.ExternalAiPromptAst("P", null, "gemini", null, "t", null, "{}", null);
        assertEquals("none", prompt.review());
        assertFalse(prompt.reviewRequired());
        var compiledPrompt = new com.npdev.dsl.v1.compiled.CompiledExternalAiPrompt("P", null, "gemini", null, "t", null, "{}", null);
        assertEquals("none", compiledPrompt.review());
        assertEquals("none", new com.npdev.dsl.v1.ast.ExternalAiPromptAst("P", null, "g", null, "t", null, "{}", null, "  ").review());
        var task = new com.npdev.dsl.v1.ast.HumanTaskAst("T", null, null, List.of("ADMIN"), null,
                List.of(new com.npdev.dsl.v1.ast.HumanTaskAst.Outcome("OK", null)), List.of(), null);
        assertEquals(null, task.getProposalValue());
        assertEquals(null, task.getProposalCurrent());
        var compiledTask = new com.npdev.dsl.v1.compiled.CompiledHumanTask("T", null, null, List.of("ADMIN"), null,
                List.of(new com.npdev.dsl.v1.compiled.CompiledHumanTask.Outcome("OK", null)), List.of(), null);
        assertEquals(null, compiledTask.getProposalValue());
        assertEquals(null, compiledTask.getProposalCurrent());
    }
}
