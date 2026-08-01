package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REG-89: {@code patchConcept}'s author-time "id is required" rule predates
 * {@code createIfMissing} (Move 5 Wave 1B, REG-77's create half) and was never relaxed for it,
 * so the runtime's own documented contract was unreachable from any model.
 *
 * <p>{@code DefaultProcedureExecutor.patchConcept}'s doc comment states it plainly: opting into
 * {@code createIfMissing} "tolerates a blank/unresolved idRef (nothing to look up yet) and, on a
 * miss, builds a brand-new record from {@code set} alone with a freshly generated id --
 * deliberately NOT the (missing) lookup id, so a caller that queried for a match first (e.g. via a
 * prior {@code listConcepts} step) and found none can still invoke this with a blank idRef."
 * {@code PackValidation.validateProcedurePatchConcept} nonetheless rejected exactly that model.
 *
 * <p>The workaround the corpus was forced into is visible in {@code dsl-conformance-max}'s own
 * fixture comment -- "id references a key nothing populates" -- i.e. a deliberately dangling ref
 * declared only to satisfy a validator, relying on it resolving to null at runtime. A model should
 * not have to lie to reach a shipped runtime feature.
 */
class ProcedurePatchConceptCreateIfMissingValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String modelJson(String stepJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.qpatchcreate", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "quantity", "type": "long" } ] }
              ],
              "procedures": [
                { "name": "EnsureOrder", "steps": [ %s ] }
              ]
            }
            """.formatted(stepJson);
    }

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    /** The RED case: create-only, no id to look up yet -- the runtime supports it, so must the validator. */
    @Test
    void createIfMissingWithNoIdIsAccepted() throws Exception {
        List<String> errors = validate(modelJson("""
            { "name": "ensure", "type": "patchConcept", "concept": "Order",
              "createIfMissing": true, "set": { "quantity": 0 }, "target": "ensured" }
            """));
        assertTrue(errors.stream().noneMatch(e -> e.contains("id is required for patchConcept")),
                "createIfMissing tolerates a blank idRef at runtime; the validator must not reject it. Got: " + errors);
    }

    /** createIfMissing with an id is still perfectly legal -- it is the patch-or-create (upsert) shape. */
    @Test
    void createIfMissingWithAnIdIsStillAccepted() throws Exception {
        List<String> errors = validate(modelJson("""
            { "name": "ensure", "type": "patchConcept", "concept": "Order", "id": "$input.orderId",
              "createIfMissing": true, "set": { "quantity": 0 }, "target": "ensured" }
            """));
        assertTrue(errors.stream().noneMatch(e -> e.contains("id is required for patchConcept")),
                "unexpected errors: " + errors);
    }

    /** The guard must NOT be weakened for a plain patch: without createIfMissing, id is still mandatory. */
    @Test
    void plainPatchWithoutIdIsStillRejected() throws Exception {
        List<String> errors = validate(modelJson("""
            { "name": "patch", "type": "patchConcept", "concept": "Order",
              "set": { "quantity": 0 }, "target": "patched" }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("id is required for patchConcept")),
                "a patch-not-upsert step has nothing to read without an id; expected the error. Got: " + errors);
    }

    /** Relaxing the id rule must not relax the set rule -- a create still needs fields to create FROM. */
    @Test
    void createIfMissingWithoutSetIsStillRejected() throws Exception {
        List<String> errors = validate(modelJson("""
            { "name": "ensure", "type": "patchConcept", "concept": "Order",
              "createIfMissing": true, "target": "ensured" }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("set is required for patchConcept")),
                "expected the missing-set error, got: " + errors);
    }

    /** ...nor the field-name check REG-71 added: a typo'd field is still caught on the create path. */
    @Test
    void createIfMissingStillChecksSetFieldNames() throws Exception {
        List<String> errors = validate(modelJson("""
            { "name": "ensure", "type": "patchConcept", "concept": "Order",
              "createIfMissing": true, "set": { "quantidade": 0 }, "target": "ensured" }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("set names a field not declared on Order: quantidade")),
                "expected the undeclared-field error, got: " + errors);
    }
}
