package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O5 (Move 11 W4). {@link FlowStepTypeConformanceTest} is this test's twin, and its existence for
 * {@code flowStep} while nothing existed for {@code procedureStep} is exactly the hole REG-89 fell
 * through: {@code createIfMissing} shipped in Move 5, was "fixed" by REG-83, was re-specced in Move
 * 9 -- and for two moves it could not be declared in ANY model, because
 * {@code PackValidation.validateProcedurePatchConcept} still demanded an {@code id}. Every kernel
 * test for it passed the whole time, because kernel tests build a {@code ProcedureStep} object
 * directly and never go through {@link SemanticValidator}. The runtime worked; the front door was
 * locked; the tests all started inside the house.
 *
 * <p>So this test starts at the front door: it builds a real model JSON, parses it with the real
 * {@link JsonModelParser}, and validates it with the real {@link SemanticValidator}.
 *
 * <p><b>Two assertions, and the second is stronger than the flow twin's on purpose.</b>
 * <ol>
 *   <li>Every {@code procedureStep.type} in model.schema.json has an example here -- so adding a
 *       26th type without a model-level test fails the build rather than shipping untested.</li>
 *   <li>The fixture validates with ZERO errors. {@code FlowStepTypeConformanceTest} only asserts
 *       the validator never says "unsupported step type", which would NOT have caught REG-89: that
 *       step type was perfectly well supported, it just could not be declared with the one flag
 *       that makes it useful. A conformance test that tolerates errors cannot see a rule that
 *       forbids a legal declaration.</li>
 * </ol>
 */
class ProcedureStepTypeConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * One minimal, GENUINELY VALID step per canonical {@code procedureStep.type}. "Valid" is the
     * whole point -- every cross-reference below (concept, query, procedure, capability, event) names
     * a real declaration in the model, so any error at all is a real finding.
     *
     * <p>The alias pairs (assign/mapValue, condition/if, loop/forEach, procedureCall/callProcedure,
     * capabilityCall/callCapability, conceptDelete/deleteConcept, eventPublish/publishEvent,
     * conceptCreate/conceptUpdate/saveConcept, readConcept/listConcepts) are each listed separately
     * rather than deduplicated: the schema declares them as distinct enum values, so each is
     * separately declarable by an author and each therefore needs its own witness.
     */
    private static final String STEPS_JSON = """
        [
          { "name": "s-assign",         "type": "assign",         "target": "greeting", "value": "hello" },
          { "name": "s-mapvalue",       "type": "mapValue",       "target": "total",    "value": "$input.total" },
          { "name": "s-condition",      "type": "condition",      "condition": "true",
            "then": [ { "name": "s-condition-then", "type": "return", "value": "$total" } ] },
          { "name": "s-if",             "type": "if",             "condition": "true",
            "then": [ { "name": "s-if-then", "type": "return", "value": "$total" } ] },
          { "name": "s-loop",           "type": "loop",           "items": "$input.lines", "as": "line",
            "steps": [ { "name": "s-loop-body", "type": "assign", "target": "seen", "value": "$line" } ] },
          { "name": "s-foreach",        "type": "forEach",        "items": "$input.lines", "as": "line",
            "steps": [ { "name": "s-foreach-body", "type": "assign", "target": "seen", "value": "$line" } ] },
          { "name": "s-maplist",        "type": "mapList",        "items": "$input.lines", "as": "line",
            "select": { "sku": "$line.sku" }, "target": "mappedLines" },
          { "name": "s-conceptquery",   "type": "conceptQuery",   "concept": "Order", "query": "OrdersByTotal", "target": "found" },
          { "name": "s-readconcept",    "type": "readConcept",    "concept": "Order", "id": "$input.orderId", "target": "order" },
          { "name": "s-listconcepts",   "type": "listConcepts",   "concept": "Order", "target": "orders" },
          { "name": "s-runquery",       "type": "runQuery",       "concept": "Order", "query": "OrdersByTotal", "target": "queried" },
          { "name": "s-conceptcreate",  "type": "conceptCreate",  "concept": "Order", "data": { "total": 1 }, "target": "created" },
          { "name": "s-conceptupdate",  "type": "conceptUpdate",  "concept": "Order", "id": "$input.orderId", "data": { "total": 2 }, "target": "updated" },
          { "name": "s-saveconcept",    "type": "saveConcept",    "concept": "Order", "data": { "total": 3 }, "target": "saved" },
          { "name": "s-conceptdelete",  "type": "conceptDelete",  "concept": "Order", "id": "$input.orderId" },
          { "name": "s-deleteconcept",  "type": "deleteConcept",  "concept": "Order", "id": "$input.orderId" },
          { "name": "s-patchconcept",   "type": "patchConcept",   "concept": "Order", "id": "$input.orderId",
            "set": { "total": 4 } },
          { "name": "s-patch-create",   "type": "patchConcept",   "concept": "Order", "createIfMissing": true,
            "set": { "total": 5 } },
          { "name": "s-procedurecall",  "type": "procedureCall",  "procedure": "ConformanceHelper", "target": "calledA" },
          { "name": "s-callprocedure",  "type": "callProcedure",  "procedure": "ConformanceHelper", "target": "calledB" },
          { "name": "s-capabilitycall", "type": "capabilityCall", "capability": "persistence", "operation": "save",
            "args": { "record": "$input" }, "target": "persistedA" },
          { "name": "s-callcapability", "type": "callCapability", "capability": "persistence", "operation": "save",
            "args": { "record": "$input" }, "target": "persistedB" },
          { "name": "s-eventpublish",   "type": "eventPublish",   "event": "OrderTouched" },
          { "name": "s-publishevent",   "type": "publishEvent",   "event": "OrderTouched" },
          { "name": "s-computevalue",   "type": "computeValue",   "operation": "add",
            "left": 1, "right": "$total", "target": "grandTotal" },
          { "name": "s-return",         "type": "return",         "value": "$grandTotal" }
        ]
        """;

    private static Path schemaPath() {
        // The NPDevContract/dsl module runs with its own module dir as the working dir.
        Path fromModule = Path.of("../schemas/model.schema.json");
        if (Files.isRegularFile(fromModule)) {
            return fromModule;
        }
        return Path.of("NPDevContract/schemas/model.schema.json");
    }

    private static Set<String> canonicalProcedureStepTypes() throws IOException {
        JsonNode root = MAPPER.readTree(schemaPath().toFile());
        JsonNode defs = root.has("$defs") ? root.get("$defs") : root.get("definitions");
        JsonNode enumNode = defs.get("procedureStep").get("properties").get("type").get("enum");
        Set<String> types = new LinkedHashSet<>();
        enumNode.forEach(n -> types.add(n.asText()));
        return types;
    }

    @Test
    @DisplayName("O5/REG-89: every procedureStep.type is declarable in a real model, and validates clean")
    void everyCanonicalProcedureStepTypeIsDeclarableAndValidatesClean() throws Exception {
        Set<String> canonical = canonicalProcedureStepTypes();
        JsonNode steps = MAPPER.readTree(STEPS_JSON);
        Set<String> fixtureTypes = new LinkedHashSet<>();
        steps.forEach(s -> fixtureTypes.add(s.get("type").asText()));

        Set<String> missingFromFixture = new LinkedHashSet<>(canonical);
        missingFromFixture.removeAll(fixtureTypes);
        assertTrue(missingFromFixture.isEmpty(),
                "canonical procedureStep.type value(s) with no example in this test's own fixture -- add one, "
                        + "or the new step type ships with no model-level validation test at all (REG-89's exact "
                        + "shape): " + missingFromFixture);

        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(conformanceModelJson()));
        List<String> errors = new SemanticValidator().validate(ast);

        assertTrue(errors.isEmpty(),
                "every step above is a legal declaration, so any validation error is a rule that forbids a "
                        + "step the runtime supports -- REG-89 in one line. Errors:\n  "
                        + String.join("\n  ", errors));
    }

    private static String conformanceModelJson() {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.procstepconformance", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "total", "type": "integer" } ] }
              ],
              "capabilities": [
                { "name": "persistence", "type": "PersistenceCapability", "operations": ["save"] }
              ],
              "events": [
                { "name": "OrderTouched", "payload": [] }
              ],
              "queries": [
                { "name": "OrdersByTotal", "concept": "Order" }
              ],
              "procedures": [
                { "name": "ConformanceHelper", "steps": [
                  { "name": "helper-return", "type": "return", "value": "$input" } ] },
                { "name": "ConformanceProcedure", "steps": %s }
              ]
            }
            """.formatted(STEPS_JSON);
    }
}
