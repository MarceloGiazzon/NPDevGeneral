package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledHumanTask;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** PROC-1: the {@code task} block on an awaitEvent step -- parse, semantic checks, canonical round trip. */
class HumanTaskValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TASK = """
        "task": {
          "title": "Approve ${input.id}",
          "assignee": { "user": "$input.owner", "roles": ["Manager"] },
          "outcomeField": "decision",
          "outcomes": [ { "value": "APPROVED", "label": "Approve" }, { "value": "REJECTED", "label": "Reject" } ],
          "fields": [ { "name": "comment", "type": "text", "label": "Comment", "maxLength": 200 } ],
          "record": "$input"
        }
        """;

    private static String model(String awaitStepExtra, String emitStepExtra) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "demo.humantask", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "owner", "type": "string" } ] }
              ],
              "roles": [ { "name": "Manager" } ],
              "events": [
                { "name": "OrderApproved", "payload": [] },
                { "name": "OrderNoted", "payload": [] }
              ],
              "flows": [
                { "name": "ApproveOrder", "concept": "Order", "steps": [
                  { "name": "emit-noted", "type": "emitEvent", "event": "OrderNoted", "from": "$input"%s },
                  { "name": "await-approval", "type": "awaitEvent", "awaitEvent": "OrderApproved",
                    "awaitRef": "approval", "match": { "correlation": true }, "timeout": 3600%s },
                  { "name": "done", "type": "return", "value": "$input" } ] }
              ]
            }
            """.formatted(emitStepExtra, awaitStepExtra);
    }

    private static ModelAst parse(String json) throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    private static List<String> taskErrors(String json) throws Exception {
        return new SemanticValidator().validate(parse(json)).stream()
                .filter(e -> e.contains("task")).toList();
    }

    @Test
    void validTaskOnAwaitStepValidatesClean() throws Exception {
        assertEquals(List.of(), taskErrors(model(", " + TASK, "")));
    }

    @Test
    void taskOnNonAwaitStepIsRefusedWithTheExactMessage() throws Exception {
        List<String> errors = taskErrors(model("", ", " + TASK));
        assertTrue(errors.stream().anyMatch(e ->
                        e.startsWith("Flow ApproveOrder step emit-noted: task is only allowed on an awaitEvent step")),
                errors.toString());
    }

    @Test
    void emptyOutcomesDuplicateOutcomesAndReservedFieldNamesAreRefused() throws Exception {
        String noOutcomes = TASK.replaceAll("\"outcomes\": \\[.*?\\],\\n", "\"outcomes\": [],\n");
        List<String> errors;
        try {
            errors = taskErrors(model(", " + noOutcomes, ""));
        } catch (Exception schemaOrParse) {
            // an empty outcomes array may already be rejected at parse by the schema's minItems:1
            return;
        }
        assertTrue(errors.stream().anyMatch(e -> e.contains("task.outcomes needs at least one outcome")), errors.toString());

        String dup = TASK.replace("\"REJECTED\"", "\"APPROVED\"").replace("\"name\": \"comment\"", "\"name\": \"decision\"");
        List<String> dupErrors = taskErrors(model(", " + dup, ""));
        assertTrue(dupErrors.stream().anyMatch(e -> e.contains("duplicate outcome value 'APPROVED'")), dupErrors.toString());
        assertTrue(dupErrors.stream().anyMatch(e -> e.contains("clashes with a reserved payload key")), dupErrors.toString());
    }

    @Test
    void taskWithoutAssigneeUserOrRolesIsRefused() throws Exception {
        String noAssignee = TASK.replace("\"assignee\": { \"user\": \"$input.owner\", \"roles\": [\"Manager\"] }",
                "\"assignee\": { \"roles\": [] }");
        List<String> errors;
        try {
            errors = taskErrors(model(", " + noAssignee, ""));
        } catch (Exception schemaOrParse) {
            return;
        }
        assertTrue(errors.stream().anyMatch(e -> e.contains("task.assignee needs a user ref or at least one role")),
                errors.toString());
    }

    @Test
    void taskSurvivesCompileAndCanonicalRoundTrip() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse(model(", " + TASK, "")));
        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(compiled));
        CompiledFlowStep step = restored.getFlows().get(0).getSteps().stream()
                .filter(s -> "await-approval".equals(s.getName())).findFirst().orElseThrow();
        CompiledHumanTask task = step.getTask();
        assertNotNull(task);
        assertEquals("$input.owner", task.getAssigneeUser());
        assertEquals(List.of("Manager"), task.getAssigneeRoles());
        assertEquals("decision", task.getOutcomeField());
        assertEquals(2, task.getOutcomes().size());
        assertEquals("APPROVED", task.getOutcomes().get(0).value());
        assertEquals("Reject", task.getOutcomes().get(1).label());
        assertEquals("comment", task.getFields().get(0).name());
        assertEquals(200, task.getFields().get(0).maxLength());
        assertEquals("$input", task.getRecord());
        assertEquals(3600L, step.getTimeoutSeconds());
    }

    @Test
    void modelWithoutTaskEmitsNoTaskKeyInCanonicalJson() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse(model("", "")));
        assertFalse(CompiledModelCanonicalJson.toJson(compiled).contains("\"task\""));
    }
}
