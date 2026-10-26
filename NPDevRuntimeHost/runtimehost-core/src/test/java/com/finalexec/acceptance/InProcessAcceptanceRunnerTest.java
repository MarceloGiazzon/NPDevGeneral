package com.finalexec.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI-8: the in-process acceptance runner executes a procedure scenario against the compiled model
 * with the SAME ProcedureRunner the app uses. The query-where assertion is the one that proves it:
 * a runner that ignored {@code where} would close the already-CLOSED row too and see 3 rows.
 */
class InProcessAcceptanceRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "demo.tasks", "version": "1.0",
          "concepts": [
            { "name": "Task", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "title", "type": "string", "required": true },
              { "name": "status", "type": "enum", "required": true, "enumValues": ["OPEN", "CLOSED"] } ] }
          ],
          "bindings": [ { "adapter": "externalAi", "capability": "vendor" } ],
          "capabilities": [ { "name": "vendor", "operations": ["ask"], "type": "ExternalAiCapability" } ],
          "queries": [ { "name": "OpenTasks", "concept": "Task", "where": "status == 'OPEN'" } ],
          "flows": [
            { "name": "AskFlow", "concept": "Task", "steps": [
              { "name": "ask", "type": "capabilityCall", "capability": "vendor", "operation": "ask",
                "args": ["$input"], "output": "$answer" } ] }
          ],
          "procedures": [
            { "name": "CloseAll", "steps": [
              { "name": "open", "type": "runQuery", "query": "OpenTasks", "concept": "Task", "target": "open" },
              { "name": "close-each", "type": "forEach", "items": "$open", "as": "task", "steps": [
                { "name": "close", "type": "patchConcept", "concept": "Task", "id": "$task.id",
                  "set": { "status": "CLOSED" }, "target": "closed" } ] },
              { "name": "done", "type": "return", "value": "$open" } ] },
            { "name": "AskVendor", "steps": [
              { "name": "ask", "type": "capabilityCall", "capability": "vendor", "operation": "ask",
                "args": { "prompt": "$input" }, "target": "answer" },
              { "name": "done", "type": "return", "value": "$answer" } ] }
          ]
        }
        """;

    private static CompiledModel model() throws Exception {
        return new ModelCompiler().compile(new JsonModelParser().parse(MAPPER.readTree(MODEL)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> scenario(String json) throws Exception {
        return MAPPER.readValue(json, Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void procedureRunsAgainstSeededRowsAndHonoursQueryWhere() throws Exception {
        Map<String, Object> scenario = scenario("""
            {
              "given": [ { "concept": "Task", "rows": [
                { "id": "t1", "title": "one", "status": "OPEN" },
                { "id": "t2", "title": "two", "status": "OPEN" },
                { "title": "three", "status": "CLOSED" } ] } ],
              "when": { "procedure": "CloseAll", "input": {} }
            }
            """);

        Map<String, Object> observed = InProcessAcceptanceRunner.observe(model(), scenario);

        assertEquals(true, observed.get("ok"), () -> String.valueOf(observed));
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) ((Map<String, Object>) observed.get("concepts")).get("Task");
        assertEquals(3, tasks.size());
        assertTrue(tasks.stream().allMatch(t -> "CLOSED".equals(t.get("status"))), String.valueOf(tasks));
        // runQuery saw exactly the 2 OPEN rows (the already-CLOSED row was filtered by `where`).
        assertEquals(2, ((List<?>) observed.get("result")).size());
    }

    @Test
    void unstubbedCapabilityFailsInsteadOfReachingAVendor() throws Exception {
        Map<String, Object> observed = InProcessAcceptanceRunner.observe(model(),
                scenario("{ \"when\": { \"procedure\": \"AskVendor\", \"input\": { \"q\": \"hi\" } } }"));

        assertEquals(false, observed.get("ok"));
        String failure = observed.get("failureCode") + " " + observed.get("failureMessage");
        assertTrue(failure.contains("CAPABILITY_NOT_STUBBED"), failure);
    }

    @Test
    void stubbedCapabilityAnswerIsTheProcedureResult() throws Exception {
        Map<String, Object> observed = InProcessAcceptanceRunner.observe(model(), scenario("""
            { "stubs": { "vendor.ask": { "answer": "42" } },
              "when": { "procedure": "AskVendor", "input": { "q": "hi" } } }
            """));

        assertEquals(true, observed.get("ok"), () -> String.valueOf(observed));
        assertEquals(Map.of("answer", "42"), observed.get("result"));
    }

    @Test
    void flowScenarioRunsTheKernelFlowWithTheStubbedCapability() throws Exception {
        Map<String, Object> observed = InProcessAcceptanceRunner.observe(model(), scenario("""
            { "stubs": { "vendor.ask": { "answer": "42" } },
              "when": { "flow": "AskFlow", "input": { "q": "hi" } } }
            """));

        assertEquals(true, observed.get("ok"), () -> String.valueOf(observed));
        assertEquals("OK", observed.get("status"));
    }

    @Test
    void flowScenarioWithAnUnstubbedCapabilityFailsInsteadOfReachingAVendor() throws Exception {
        Map<String, Object> observed = InProcessAcceptanceRunner.observe(model(),
                scenario("{ \"when\": { \"flow\": \"AskFlow\", \"input\": { \"q\": \"hi\" } } }"));

        assertEquals(false, observed.get("ok"), () -> String.valueOf(observed));
        assertEquals("CAPABILITY_FAILED", observed.get("status"));
        assertTrue(String.valueOf(observed.get("failureMessage")).contains("no stubs[\"vendor.ask\"]"), () -> String.valueOf(observed));
    }

    @Test
    void scenarioKindDetection() throws Exception {
        assertTrue(InProcessAcceptanceRunner.isInProcess(scenario("{ \"when\": { \"procedure\": \"P\" } }")));
        assertFalse(InProcessAcceptanceRunner.isInProcess(scenario("{ \"when\": { \"method\": \"GET\", \"path\": \"/x\" } }")));
        assertTrue(InProcessAcceptanceRunner.isInProcess(scenario("{ \"when\": { \"flow\": \"F\" } }")));
        assertTrue(InProcessAcceptanceRunner.scenarioHasFlow(scenario("{ \"when\": { \"flow\": \"F\" } }")));
    }
}
