package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.CompactSteps;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P8 (owner D-4, 2026-11-21): a simple flow step may be one string; it lowers to exactly the object form. */
class CompactStepsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode lower(String text) throws Exception {
        return CompactSteps.expandStep(TextNode.valueOf(text), "test");
    }

    private static void assertLowersTo(String text, String expectedJson) throws Exception {
        assertEquals(JSON.readTree(expectedJson), lower(text), text);
    }

    @Test
    void everyCompactFormLowersToTheObjectAPersonCouldHaveWritten() throws Exception {
        assertLowersTo("return $final", "{\"type\":\"return\",\"value\":\"$final\"}");
        assertLowersTo("set riderId = $me.id", "{\"type\":\"set\",\"value\":\"$me.id\",\"output\":\"riderId\"}");
        assertLowersTo("read Ride $input.id as ride",
                "{\"type\":\"readConcept\",\"scope\":\"Ride\",\"id\":\"$input.id\",\"output\":\"ride\"}");
        assertLowersTo("create Ride from $rideIn as created",
                "{\"type\":\"createConcept\",\"scope\":\"Ride\",\"input\":\"$rideIn\",\"output\":\"created\"}");
        assertLowersTo("call MeasureTrip with $created as m",
                "{\"type\":\"callFlow\",\"flow\":\"MeasureTrip\",\"input\":\"$created\",\"output\":\"m\"}");
        assertLowersTo("call Tidy", "{\"type\":\"callFlow\",\"flow\":\"Tidy\"}");
        assertLowersTo("emit RideDone from $saved", "{\"type\":\"emitEvent\",\"event\":\"RideDone\",\"from\":\"$saved\"}");
        assertLowersTo("cap geo.closest($a, $b, \"x, y\") as best",
                "{\"type\":\"capabilityCall\",\"capability\":\"geo\",\"operation\":\"closest\","
                        + "\"args\":[\"$a\",\"$b\",\"\\\"x, y\\\"\"],\"output\":\"best\"}");
    }

    @Test
    void anObjectStepIsReturnedUntouched() throws Exception {
        JsonNode object = JSON.readTree("{\"type\":\"return\",\"value\":\"$x\"}");
        assertTrue(object == CompactSteps.expandStep(object, "test"));
    }

    @Test
    void aStringThatFitsNoFormIsRefusedWithTheListOfForms() {
        IOException refusal = assertThrows(IOException.class, () -> lower("branch $a == 1"));
        assertTrue(refusal.getMessage().contains("read <Scope> <id>") && refusal.getMessage().contains("written as an object"),
                refusal.getMessage());
        assertThrows(IOException.class, () -> lower("cap geo.closest($a, ) as b"));
        assertThrows(IOException.class, () -> lower("cap geo.closest(\"open) as b"));
    }

    @Test
    void aFlowWrittenWithCompactStepsCompilesLikeItsObjectForm() throws Exception {
        String compact = model("""
                "read Room $input.id as room", "set label = $room.name", "return $label"
                """);
        String objects = model("""
                {"type":"readConcept","scope":"Room","id":"$input.id","output":"room"},
                {"type":"set","value":"$room.name","output":"label"},
                {"type":"return","value":"$label"}
                """);
        var ast = new JsonModelParser().parse(write(compact));
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), errors.toString());
        List<CompiledFlowStep> a = new ModelCompiler().compile(ast).getFlows().get(0).getSteps();
        List<CompiledFlowStep> b = new ModelCompiler().compile(new JsonModelParser().parse(write(objects)))
                .getFlows().get(0).getSteps();
        assertEquals(b.size(), a.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(b.get(i).getType(), a.get(i).getType());
            assertEquals(b.get(i).getName(), a.get(i).getName());
            assertEquals(b.get(i).getScope(), a.get(i).getScope());
            assertEquals(b.get(i).getRecordIdRef(), a.get(i).getRecordIdRef());
            assertEquals(b.get(i).getReturnValueRef(), a.get(i).getReturnValueRef());
        }
    }

    @Test
    void compactStepsWorkInsideBranchBodiesToo() throws Exception {
        String json = model("""
                "read Room $input.id as room",
                {"type":"branch","condition":"$room.name == 'x'","then":["return $room"],"else":["return $input"]}
                """);
        CompiledModel compiled = new ModelCompiler().compile(new JsonModelParser().parse(write(json)));
        CompiledFlowStep branch = compiled.getFlows().get(0).getSteps().get(1);
        assertEquals("return", branch.getThenSteps().get(0).getType());
        assertEquals("return", branch.getElseSteps().get(0).getType());
    }

    private static String model(String steps) {
        return """
                {"namespace": "demo.compact", "dslVersion": "3.0.0", "version": "1.0",
                 "concepts": [{"name": "Room", "fields": [{"name": "id", "type": "uuid", "id": true},
                                                          {"name": "name", "type": "string", "required": true}]}],
                 "flows": [{"name": "Look", "input": {"concept": "Room", "mode": "query"},
                   "steps": [""" + steps + """
                   ]}]}
                """;
    }

    private static Path write(String json) throws Exception {
        Path file = Files.createTempFile("npdev-compact-", ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }
}
