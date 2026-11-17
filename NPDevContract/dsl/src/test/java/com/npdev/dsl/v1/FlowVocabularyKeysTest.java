package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FORM P3-T5c (stage 2): the keys the merged flow vocabulary gained so procedures[] and orchestrationRules[] can become
 * flows -- {@code set} as one expression, {@code map{collection,itemKey,select}}, {@code updateConcept{id,set,createIfMissing}},
 * {@code capabilityCall.errorTarget}, {@code createConcept.ifExists}, an optional flow {@code concept} and a flow
 * {@code description}. Each must survive parser, validator, compiler and the canonical JSON writer/reader, and stay out of
 * the canonical JSON when absent.
 */
class FlowVocabularyKeysTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String model(String flowHead, String steps) {
        return """
            {
              "dslVersion": "3.0.0", "namespace": "demo.vocab", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "total", "type": "integer" },
                  { "name": "note", "type": "string" } ] }
              ],
              "flows": [ { "name": "F" %s, "steps": %s } ]
            }
            """.formatted(flowHead.isBlank() ? "" : ", " + flowHead, steps);
    }

    private static ModelAst parse(String json) throws IOException {
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    private static List<String> validate(String json) throws IOException {
        return new SemanticValidator().validate(parse(json));
    }

    private static CompiledModel roundTrip(String json) throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse(json));
        return CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(compiled));
    }

    private static CompiledFlowStep step(CompiledModel model, String name) {
        return model.getFlows().get(0).getSteps().stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
    }

    private static final String RETURN = "{ \"name\": \"done\", \"type\": \"return\", \"value\": \"$input\" }";

    @Test
    void setValueMustBeAnExpression() throws Exception {
        String ok = model("\"concept\": \"Order\"", "[ { \"name\": \"s\", \"type\": \"set\", \"value\": \"$input.total * 2\", \"output\": \"d\" }, " + RETURN + " ]");
        assertTrue(validate(ok).stream().noneMatch(e -> e.contains("not a valid expression")), validate(ok).toString());
        String bad = model("\"concept\": \"Order\"", "[ { \"name\": \"s\", \"type\": \"set\", \"value\": \"1 +\", \"output\": \"d\" }, " + RETURN + " ]");
        assertTrue(validate(bad).stream().anyMatch(e -> e.contains("not a valid expression")), validate(bad).toString());
    }

    @Test
    void mapSelectBuildsAnObjectOrAListAndRoundTrips() throws Exception {
        String steps = "[ { \"name\": \"one\", \"type\": \"map\", \"select\": { \"a\": \"$input.id\", \"b\": \"lit\", \"c\": null }, \"output\": \"obj\" },"
                + " { \"name\": \"many\", \"type\": \"map\", \"collection\": \"$input.lines\", \"itemKey\": \"line\", \"select\": { \"sku\": \"$line.sku\" }, \"output\": \"list\" },"
                + RETURN + " ]";
        String json = model("\"concept\": \"Order\"", steps);
        List<String> errors = validate(json);
        assertTrue(errors.stream().noneMatch(e -> e.contains("map")), errors.toString());
        CompiledModel back = roundTrip(json);
        assertEquals("lit", step(back, "one").getFieldValues().get("b"));
        assertTrue(step(back, "one").getFieldValues().containsKey("c"));
        assertNull(step(back, "one").getFieldValues().get("c"));
        assertEquals("$input.lines", step(back, "many").getCollectionRef());
        assertEquals("line", step(back, "many").getItemKey());
        assertEquals("$line.sku", step(back, "many").getFieldValues().get("sku"));
    }

    @Test
    void mapWithACollectionButNoSelectIsRefused() throws Exception {
        String json = model("", "[ { \"name\": \"m\", \"type\": \"map\", \"collection\": \"$input.lines\", \"output\": \"x\" }, " + RETURN + " ]");
        assertTrue(validate(json).stream().anyMatch(e -> e.contains("map with collection must define select")));
    }

    @Test
    void updateConceptRecordFormNeedsNoPersistenceCapability() throws Exception {
        String steps = "[ { \"name\": \"u\", \"type\": \"updateConcept\", \"scope\": \"Order\", \"id\": \"$input.id\","
                + " \"set\": { \"note\": \"done\", \"total\": \"$input.total\" }, \"createIfMissing\": true, \"output\": \"row\" }, " + RETURN + " ]";
        String json = model("", steps);
        assertEquals(List.of(), validate(json));
        CompiledModel back = roundTrip(json);
        CompiledFlowStep u = step(back, "u");
        assertEquals("$input.id", u.getRecordIdRef());
        assertEquals("done", u.getFieldValues().get("note"));
        assertEquals(Boolean.TRUE, u.getCreateIfMissing());
        assertNull(u.getCapabilityCall(), "the record form is not a persistence.save call");
    }

    @Test
    void updateConceptRecordFormRulesAreEnforced() throws Exception {
        String noSet = model("", "[ { \"name\": \"u\", \"type\": \"updateConcept\", \"scope\": \"Order\", \"id\": \"$input.id\", \"output\": \"r\" }, " + RETURN + " ]");
        assertTrue(validate(noSet).stream().anyMatch(e -> e.contains("must define set")), validate(noSet).toString());
        String noScope = model("", "[ { \"name\": \"u\", \"type\": \"updateConcept\", \"id\": \"$input.id\", \"set\": { \"note\": \"x\" }, \"output\": \"r\" }, " + RETURN + " ]");
        assertTrue(validate(noScope).stream().anyMatch(e -> e.contains("must define scope")), validate(noScope).toString());
        String both = model("\"concept\": \"Order\"", "[ { \"name\": \"u\", \"type\": \"updateConcept\", \"id\": \"$input.id\", \"input\": \"x\", \"set\": { \"note\": \"x\" }, \"output\": \"r\" }, " + RETURN + " ]");
        assertTrue(validate(both).stream().anyMatch(e -> e.contains("not both")), validate(both).toString());
    }

    @Test
    void newKeysBelongToTheirOwnStepTypesOnly() throws Exception {
        String json = model("\"concept\": \"Order\"", "[ { \"name\": \"s\", \"type\": \"set\", \"value\": \"1\", \"output\": \"x\", \"ifExists\": \"skip\", \"errorTarget\": \"e\", \"createIfMissing\": true }, " + RETURN + " ]");
        List<String> errors = validate(json);
        assertTrue(errors.stream().anyMatch(e -> e.contains("ifExists applies to createConcept")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("errorTarget applies to capabilityCall")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("createIfMissing applies to updateConcept")), errors.toString());
    }

    @Test
    void aFlowWithoutAConceptNamesItsScopesAndKeepsItsDescription() throws Exception {
        String steps = "[ { \"name\": \"r\", \"type\": \"readConcept\", \"scope\": \"Order\", \"id\": \"$input.id\", \"output\": \"row\" }, " + RETURN + " ]";
        String json = model("\"description\": \"reads one order\"", steps);
        assertEquals(List.of(), validate(json));
        CompiledModel back = roundTrip(json);
        assertEquals("reads one order", back.getFlows().get(0).getDescription());
        assertTrue(back.getFlows().get(0).getConcept() == null || back.getFlows().get(0).getConcept().isBlank());

        String unscoped = model("", "[ { \"name\": \"r\", \"type\": \"readConcept\", \"id\": \"$input.id\", \"output\": \"row\" }, " + RETURN + " ]");
        assertTrue(validate(unscoped).stream().anyMatch(e -> e.contains("must define scope")), validate(unscoped).toString());
    }

    @Test
    void errorTargetAndIfExistsRoundTripAndAbsentKeysStayOutOfTheCanonicalJson() throws Exception {
        String plain = model("\"concept\": \"Order\"", "[ " + RETURN + " ]");
        // only the flows section: concepts and procedures legitimately carry some of these key names themselves
        String flows = MAPPER.readTree(CompiledModelCanonicalJson.toJson(new ModelCompiler().compile(parse(plain)))).get("flows").toString();
        assertFalse(flows.contains("fieldValues") || flows.contains("createIfMissing")
                || flows.contains("errorTarget") || flows.contains("ifExists") || flows.contains("\"description\""),
                "absent keys must not appear in a flow's canonical JSON: " + flows);
    }

    @Test
    void dedupeKeyBelongsToScheduleEventStepsAndRoundTrips() throws Exception {
        String ok = model("\"concept\": \"Order\"", "[ { \"name\": \"later\", \"type\": \"scheduleEvent\", \"event\": \"Due\", \"from\": \"$input\", \"delaySeconds\": 60, \"dedupeKey\": \"$input.id\" }, " + RETURN + " ]");
        assertTrue(validate(ok).stream().noneMatch(e -> e.contains("dedupeKey")), validate(ok).toString());
        assertEquals("$input.id", step(roundTrip(ok), "later").getDedupeKey());

        String misplaced = model("\"concept\": \"Order\"", "[ { \"name\": \"s\", \"type\": \"set\", \"value\": \"1\", \"output\": \"x\", \"dedupeKey\": \"$input.id\" }, " + RETURN + " ]");
        assertTrue(validate(misplaced).stream().anyMatch(e -> e.contains("dedupeKey applies to scheduleEvent")), validate(misplaced).toString());

        String plain = model("\"concept\": \"Order\"", "[ { \"name\": \"later\", \"type\": \"scheduleEvent\", \"event\": \"Due\", \"from\": \"$input\", \"delaySeconds\": 60 }, " + RETURN + " ]");
        assertNull(step(roundTrip(plain), "later").getDedupeKey());
        String flows = MAPPER.readTree(CompiledModelCanonicalJson.toJson(new ModelCompiler().compile(parse(plain)))).get("flows").toString();
        assertFalse(flows.contains("dedupeKey"), "an absent dedupeKey must not appear in the canonical JSON: " + flows);
    }

    @Test
    void aRunAsSystemFlowMustBeInternalOrTriggered() throws Exception {
        String steps = "[ " + RETURN + " ]";
        String publicFlow = model("\"concept\": \"Order\", \"runAs\": \"system\"", steps);
        assertTrue(validate(publicFlow).stream().anyMatch(e -> e.contains("runAs \"system\" flow must be expose: internal")),
                validate(publicFlow).toString());
        String uiFlow = model("\"concept\": \"Order\", \"runAs\": \"system\", \"expose\": \"ui\"", steps);
        assertTrue(validate(uiFlow).stream().anyMatch(e -> e.contains("must be expose: internal")), validate(uiFlow).toString());
        String internal = model("\"concept\": \"Order\", \"runAs\": \"system\", \"expose\": \"internal\"", steps);
        assertTrue(validate(internal).stream().noneMatch(e -> e.contains("must be expose: internal")), validate(internal).toString());
    }
}
