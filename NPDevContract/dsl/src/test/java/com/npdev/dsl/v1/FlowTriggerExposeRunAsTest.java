package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FORM P3-T5a (one flow, schema): {@code flows[].trigger}, {@code expose}, {@code runAs} and the merged
 * step vocabulary's concept steps survive the full chain a new flow field must (parser, validator,
 * compiler, canonical JSON writer and reader), and stay out of the canonical JSON when they are the
 * default so no existing app's golden output moves. The flow engine runs the concept steps (P3-T5b); event and webhook triggers are started by FlowEventTriggerService / WebhookInboundController (P3-T5c).
 */
class FlowTriggerExposeRunAsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String model(String flowExtras, String steps) {
        return """
            {
              "dslVersion": "3.0.0", "namespace": "demo.flows", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "total", "type": "integer" } ] }
              ],
              "events": [ { "name": "OrderPlaced", "payload": [] } ],
              "flows": [
                { "name": "Ship", "concept": "Order" %s, "steps": %s }
              ]
            }
            """.formatted(flowExtras.isBlank() ? "" : ", " + flowExtras, steps);
    }

    /** Declares the query the concept-step test names ({@code runQuery} must name a declared query). */
    private static String withQuery(String json) {
        return json.replace("\"events\":", "\"queries\": [ { \"name\": \"OrdersByTotal\", \"concept\": \"Order\", \"where\": \"total == $params.total\", \"parameters\": [ { \"name\": \"total\", \"type\": \"integer\" } ] } ], \"events\":");
    }

    private static final String ONE_STEP = "[ { \"name\": \"done\", \"type\": \"return\", \"value\": \"input\" } ]";

    private static ModelAst parse(String json) throws IOException {
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    private static List<String> validate(String json) throws IOException {
        return new SemanticValidator().validate(parse(json));
    }

    @Test
    void theDefaultsStayNullAndOutOfTheCanonicalJson() throws Exception {
        FlowAst flow = parse(model("\"trigger\": \"api\", \"expose\": \"api\", \"runAs\": \"caller\"", ONE_STEP)).getFlows().get(0);
        assertNull(flow.getTrigger());
        assertNull(flow.getExpose());
        assertNull(flow.getRunAs());
        CompiledModel compiled = new ModelCompiler().compile(parse(model("", ONE_STEP)));
        String json = CompiledModelCanonicalJson.toJson(compiled);
        assertFalse(json.contains("\"trigger\"") || json.contains("\"expose\"") || json.contains("\"runAs\""),
                "a default must not appear in the canonical JSON");
    }

    @Test
    void eventScheduleAndWebhookTriggersParseAndRoundTrip() throws Exception {
        FlowAst event = parse(model("\"trigger\": { \"event\": \"OrderPlaced\", \"condition\": \"input.total > 5\" }, "
                + "\"expose\": \"internal\", \"runAs\": \"system\"", ONE_STEP)).getFlows().get(0);
        assertEquals("event", event.getTrigger().kind());
        assertEquals("OrderPlaced", event.getTrigger().event());
        assertEquals("input.total > 5", event.getTrigger().condition());
        assertEquals("internal", event.getExpose());
        assertEquals("system", event.getRunAs());

        FlowAst schedule = parse(model("\"trigger\": { \"schedule\": { \"cron\": \"0 8 * * *\" } }", ONE_STEP)).getFlows().get(0);
        assertEquals("schedule", schedule.getTrigger().kind());
        assertEquals("0 8 * * *", schedule.getSchedule().getCron(), "trigger.schedule lands in the flow's own schedule");

        FlowAst webhook = parse(model("\"trigger\": { \"webhook\": \"stripe\" }", ONE_STEP)).getFlows().get(0);
        assertEquals("webhook", webhook.getTrigger().kind());
        assertEquals("stripe", webhook.getTrigger().webhook());

        CompiledModel compiled = new ModelCompiler().compile(parse(model(
                "\"trigger\": { \"event\": \"OrderPlaced\", \"condition\": \"input.total > 5\" }, "
                        + "\"expose\": \"ui\", \"runAs\": \"system\"", ONE_STEP)));
        CompiledFlow restored = CompiledModelCanonicalJsonReader
                .fromJson(CompiledModelCanonicalJson.toJson(compiled)).getFlows().get(0);
        assertEquals("event", restored.getTrigger().kind());
        assertEquals("OrderPlaced", restored.getTrigger().event());
        assertEquals("input.total > 5", restored.getTrigger().condition());
        assertEquals("ui", restored.getExpose());
        assertEquals("system", restored.getRunAs());
    }

    @Test
    void malformedTriggersExposeAndRunAsAreRefused() {
        for (String extras : new String[] {
                "\"trigger\": \"event\"",
                "\"trigger\": {}",
                "\"trigger\": { \"event\": \"A\", \"webhook\": \"w\" }",
                "\"trigger\": { \"webhook\": \"w\", \"condition\": \"true\" }",
                "\"trigger\": { \"event\": \"A\", \"typo\": 1 }",
                "\"trigger\": { \"schedule\": { \"cron\": \"0 8 * * *\" } }, \"schedule\": { \"cron\": \"0 9 * * *\" }",
                "\"expose\": \"public\"",
                "\"runAs\": \"root\""}) {
            assertThrows(IOException.class, () -> parse(model(extras, ONE_STEP)), extras);
        }
    }

    @Test
    void anEventTriggerMustNameADeclaredEventWithAValidCondition() throws Exception {
        assertTrue(validate(model("\"trigger\": { \"event\": \"OrderPlaced\", \"condition\": \"input.total > 5\" }", ONE_STEP))
                .stream().noneMatch(e -> e.contains("trigger")));
        assertTrue(validate(model("\"trigger\": { \"event\": \"Nope\" }", ONE_STEP))
                .stream().anyMatch(e -> e.contains("trigger.event references unknown event Nope")));
        assertTrue(validate(model("\"trigger\": { \"event\": \"OrderPlaced\", \"condition\": \"input.total >\" }", ONE_STEP))
                .stream().anyMatch(e -> e.contains("trigger.condition is not a valid expression")));
    }

    @Test
    void aWebhookTriggerMustNameACapabilityWithAnInboundDoor() throws Exception {
        String stripe = "\"capabilities\": [ { \"name\": \"stripe\", \"inbound\": { \"eventName\": \"OrderPlaced\" } } ], ";
        String declared = model("\"trigger\": { \"webhook\": \"stripe\" }", ONE_STEP).replace("\"events\":", stripe + "\"events\":");
        assertTrue(validate(declared).stream().noneMatch(e -> e.contains("trigger.webhook")));
        String unknown = model("\"trigger\": { \"webhook\": \"ghost\" }", ONE_STEP).replace("\"events\":", stripe + "\"events\":");
        assertTrue(validate(unknown).stream().anyMatch(e -> e.contains("trigger.webhook references ghost")));
        assertTrue(validate(model("\"trigger\": { \"webhook\": \"ghost\" }", ONE_STEP))
                .stream().anyMatch(e -> e.contains("trigger.webhook references ghost")));
    }

    @Test
    void theConceptStepsParseValidateAndRoundTrip() throws Exception {
        String steps = """
            [
              { "name": "s-set", "type": "set", "value": "input.total * 2", "output": "doubled" },
              { "name": "s-read", "type": "readConcept", "scope": "Order", "id": "input.id", "output": "found" },
              { "name": "s-delete", "type": "deleteConcept", "scope": "Order", "id": "input.id" },
              { "name": "s-list", "type": "listConcepts", "scope": "Order", "output": "all" },
              { "name": "s-run", "type": "runQuery", "query": "OrdersByTotal", "params": { "total": "input.total" }, "output": "rows" },
              { "name": "s-call", "type": "callFlow", "flow": "Ship", "output": "again" }
            ]
            """;
        String json = withQuery(model("", steps));
        assertTrue(validate(json).isEmpty(), "unexpected: " + validate(json));
        CompiledModel compiled = new ModelCompiler().compile(parse(json));
        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(compiled));
        List<CompiledFlowStep> restoredSteps = restored.getFlows().get(0).getSteps();
        assertEquals("set", restoredSteps.get(0).getType());
        assertEquals("input.total * 2", restoredSteps.get(0).getReturnValueRef());
        assertEquals("doubled", restoredSteps.get(0).getMapToRef());
        assertEquals("input.id", restoredSteps.get(1).getRecordIdRef());
        assertEquals("OrdersByTotal", restoredSteps.get(4).getQueryName());
        assertEquals(java.util.Map.of("total", "input.total"), restoredSteps.get(4).getQueryParams());
        assertEquals("Ship", restoredSteps.get(5).getFlowName());
    }

    @Test
    void aConceptStepMissingItsKeyIsAValidationError() throws Exception {
        for (String step : new String[] {
                "{ \"name\": \"a\", \"type\": \"set\", \"output\": \"x\" }",
                "{ \"name\": \"a\", \"type\": \"readConcept\", \"output\": \"x\" }",
                "{ \"name\": \"a\", \"type\": \"deleteConcept\" }",
                "{ \"name\": \"a\", \"type\": \"listConcepts\" }",
                "{ \"name\": \"a\", \"type\": \"runQuery\", \"output\": \"x\" }",
                "{ \"name\": \"a\", \"type\": \"callFlow\" }"}) {
            // the schema's per-type "required" refuses it first; the semantic validator is the second line
            try {
                List<String> errors = validate(model("", "[ " + step + " ]"));
                assertTrue(errors.stream().anyMatch(e -> e.contains("step must define")), step + " -> " + errors);
            } catch (com.npdev.dsl.v1.validation.ModelSchemaValidationException schemaRefusal) {
                assertTrue(schemaRefusal.getMessage().contains("Model schema validation failed"), schemaRefusal.getMessage());
            }
        }
    }

    @Test
    void aRunQueryOrCallFlowThatNamesNothingIsAValidationErrorAtAnyDepth() throws Exception {
        String steps = """
            [
              { "name": "go", "type": "branch", "condition": "input.total > 1",
                "then": [ { "name": "q", "type": "runQuery", "query": "NoSuchQuery", "output": "rows" } ],
                "else": [ { "name": "c", "type": "callFlow", "flow": "NoSuchFlow", "output": "x" } ] }
            ]
            """;

        List<String> errors = validate(model("", steps));

        assertTrue(errors.stream().anyMatch(e -> e.contains("runQuery names unknown query NoSuchQuery")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("callFlow names unknown flow NoSuchFlow")), errors.toString());
    }

    @Test
    void theReferenceIndexSeesAFlowStepsQueryAndFlowSoRenamesAndGhostsAreCaught() throws Exception {
        String steps = """
            [
              { "name": "q", "type": "runQuery", "query": "OrdersByTotal", "output": "rows" },
              { "name": "ghost", "type": "runQuery", "query": "Ghost", "output": "more" },
              { "name": "c", "type": "callFlow", "flow": "Ship", "output": "x" }
            ]
            """;

        com.npdev.dsl.v1.xref.ReferenceIndex index = com.npdev.dsl.v1.xref.ReferenceIndex.build(parse(withQuery(model("", steps))));

        assertTrue(index.edges().stream().anyMatch(e -> e.path().endsWith("steps[0].query")
                        && e.toName().equals("OrdersByTotal")
                        && e.resolution() == com.npdev.dsl.v1.xref.Resolution.RESOLVED),
                "a declared query is indexed and resolved");
        assertTrue(index.edges().stream().anyMatch(e -> e.path().endsWith("steps[1].query")
                        && e.resolution() == com.npdev.dsl.v1.xref.Resolution.UNRESOLVED),
                "a ghost query is reported unresolved");
        assertTrue(index.edges().stream().anyMatch(e -> e.path().endsWith("steps[2].flow")
                        && e.toName().equals("Ship")
                        && e.resolution() == com.npdev.dsl.v1.xref.Resolution.RESOLVED),
                "a callFlow target is indexed so a flow rename cascades");
    }
}
