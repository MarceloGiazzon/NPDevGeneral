package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** F4 (docs/FINAL_OPEN_ITEMS_PLAN.md, REG-65): "the class fix," not just the one-instance fix --
 * asserts every canonical {@code flowStep.type} value in model.schema.json is handled by
 * FlowValidation's step-type switch. This is what generatedAction's own bug looked like from the
 * schema's side: a canonical enum value with no corresponding validator case, silently rejected as
 * "unsupported step type" for every real author. This test fails at build time on that shape instead
 * of waiting for a fixture author (dsl-conformance-max) to trip over it by accident.
 *
 * <p>Deliberately does not require every synthetic step to validate error-FREE -- a step referencing
 * an undeclared capability/event is a different, legitimate kind of error. It only asserts the
 * switch never falls through to its {@code default -> "unsupported step type"} branch for any
 * schema-declared type. */
class FlowStepTypeConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One minimal step per canonical type, each satisfying only ITS OWN schema-required fields
     * (model.schema.json $defs.flowStep.allOf) -- cross-references (capability/event names) are
     * dummy values on purpose; only "unsupported step type" is asserted against. */
    private static final String STEPS_JSON = """
        [
          { "name": "s-invariant",    "type": "invariantCheck", "scope": "Order" },
          { "name": "s-capability",   "type": "capabilityCall",  "capability": "persistence", "operation": "save" },
          { "name": "s-generated",    "type": "generatedAction", "actionName": "ScoreOrderRisk" },
          { "name": "s-emit",         "type": "emitEvent",       "event": "OrderScored", "from": "s-generated" },
          { "name": "s-schedule",     "type": "scheduleEvent",   "event": "OrderReminder", "from": "s-generated", "delaySeconds": 60 },
          { "name": "s-branch",       "type": "branch",          "condition": "true",
            "then": [ { "name": "s-branch-then", "type": "return", "value": "input" } ] },
          { "name": "s-await",        "type": "awaitEvent",      "awaitEvent": "OrderApproved" },
          { "name": "s-create",       "type": "createConcept",   "scope": "Order", "input": "input", "output": "created" },
          { "name": "s-update",       "type": "updateConcept",   "scope": "Order", "input": "input", "output": "updated" },
          { "name": "s-map",          "type": "map",             "input": "input", "output": "mapped" },
          { "name": "s-foreach",      "type": "forEach",         "collection": "input.lines", "itemKey": "line",
            "steps": [ { "name": "s-foreach-body", "type": "return", "value": "line" } ] },
          { "name": "s-callprocedure","type": "callProcedure",  "procedure": "ConformanceProcedure", "output": "called" },
          { "name": "s-return",       "type": "return",          "value": "input" }
        ]
        """;

    private static Path schemaPath() {
        // NPDevContract/dsl module runs with its own module dir as the working dir.
        Path fromModule = Path.of("../schemas/model.schema.json");
        if (Files.isRegularFile(fromModule)) {
            return fromModule;
        }
        return Path.of("NPDevContract/schemas/model.schema.json");
    }

    private static Set<String> canonicalFlowStepTypes() throws IOException {
        JsonNode root = MAPPER.readTree(schemaPath().toFile());
        JsonNode defs = root.has("$defs") ? root.get("$defs") : root.get("definitions");
        JsonNode enumNode = defs.get("flowStep").get("properties").get("type").get("enum");
        Set<String> types = new LinkedHashSet<>();
        enumNode.forEach(n -> types.add(n.asText()));
        return types;
    }

    @Test
    void everyCanonicalFlowStepTypeIsCoveredByTheStepsFixtureAboveAndByFlowValidation() throws Exception {
        Set<String> canonical = canonicalFlowStepTypes();
        JsonNode steps = MAPPER.readTree(STEPS_JSON);
        Set<String> fixtureTypes = new LinkedHashSet<>();
        steps.forEach(s -> fixtureTypes.add(s.get("type").asText()));

        Set<String> missingFromFixture = new LinkedHashSet<>(canonical);
        missingFromFixture.removeAll(fixtureTypes);
        assertTrue(missingFromFixture.isEmpty(),
                "canonical flowStep.type value(s) with no example in this test's own fixture -- "
                        + "add one so this test actually exercises it: " + missingFromFixture);

        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.conformance", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "total", "type": "integer" } ] }
              ],
              "capabilities": [
                { "name": "persistence", "type": "PersistenceCapability", "operations": ["save"] }
              ],
              "events": [
                { "name": "OrderScored", "payload": [] },
                { "name": "OrderReminder", "payload": [] },
                { "name": "OrderApproved", "payload": [] }
              ],
              "procedures": [
                { "name": "ConformanceProcedure", "steps": [
                  { "name": "return-input", "type": "return", "value": "$input" } ] }
              ],
              "flows": [
                { "name": "ConformanceFlow", "concept": "Order", "steps": %s }
              ]
            }
            """.formatted(STEPS_JSON);

        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        List<String> errors = new SemanticValidator().validate(ast);

        for (String type : canonical) {
            boolean unsupported = errors.stream().anyMatch(e ->
                    e.contains("unsupported step type") && e.toLowerCase(java.util.Locale.ROOT)
                            .contains(type.toLowerCase(java.util.Locale.ROOT)));
            if (unsupported) {
                fail("FlowValidation has no case for canonical flowStep.type '" + type + "' -- "
                        + "schema and validator have diverged. Errors: " + errors);
            }
        }
    }
}
