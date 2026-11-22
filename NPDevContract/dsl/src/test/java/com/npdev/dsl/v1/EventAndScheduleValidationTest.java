package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two things an author can get wrong that the JSON schema alone lets through, and that validation must name: an event
 * declared twice (or with a payload field declared twice, refused while the model is resolved), and a schedule whose
 * cron has the wrong number of fields. The wording of each error is the contract an author or an AI helper reads
 * and acts on.
 */
class EventAndScheduleValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String events, String flows) throws Exception {
        String json = """
            {
              "dslVersion": "3.0.0", "namespace": "demo.validation", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ],
              "events": %s,
              "flows": %s
            }
            """.formatted(events, flows);
        return new SemanticValidator().validate(new JsonModelParser().parse(MAPPER.readTree(json)));
    }

    private static final String RETURN_FLOW = """
        [ { "name": "Tick", "concept": "Order", "steps": [ { "name": "done", "type": "return", "value": "$input" } ] } ]
        """;

    private static String scheduledFlow(String schedule) {
        return """
            [ { "name": "Tick", "concept": "Order", "trigger": { "schedule": %s },
                "steps": [ { "name": "done", "type": "return", "value": "$input" } ] } ]
            """.formatted(schedule);
    }

    @Test
    void anEventDeclaredTwiceIsNamed() throws Exception {
        List<String> errors = validate("[ { \"name\": \"OrderPlaced\" }, { \"name\": \"orderplaced\" } ]", RETURN_FLOW);

        assertTrue(errors.stream().anyMatch(e -> e.contains("Duplicate event name")), errors.toString());
    }

    @Test
    void aPayloadFieldDeclaredTwiceNamesTheEventAndTheField() throws Exception {
        List<String> errors = validate(
                "[ { \"name\": \"OrderPlaced\", \"payload\": [ { \"name\": \"id\", \"type\": \"uuid\" }, { \"name\": \"id\", \"type\": \"uuid\" } ] } ]",
                RETURN_FLOW);

        assertTrue(errors.stream().anyMatch(e -> e.contains("Event OrderPlaced declares duplicate payload field id")),
                errors.toString());
    }

    @Test
    void aCronWithFourFieldsIsRefusedWithTheCountItHas() throws Exception {
        List<String> errors = validate("[]", scheduledFlow("{ \"cron\": \"0 0 * *\" }"));

        assertTrue(errors.stream().anyMatch(e -> e.contains("schedule.cron must have 5 or 6 space-separated fields, got 4")),
                errors.toString());
    }

    @Test
    void aFiveFieldAndASixFieldCronBothPassTheStructuralCheck() throws Exception {
        for (String cron : List.of("*/5 * * * *", "0 */5 * * * *")) {
            List<String> errors = validate("[]", scheduledFlow("{ \"cron\": \"" + cron + "\", \"tenantScope\": [ \"dev\" ] }"));

            assertTrue(errors.stream().noneMatch(e -> e.contains("schedule.cron")), cron + " -> " + errors);
        }
    }
}
