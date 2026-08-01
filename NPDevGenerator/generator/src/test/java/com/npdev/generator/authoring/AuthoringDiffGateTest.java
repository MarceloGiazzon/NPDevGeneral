package com.npdev.generator.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI_AUTHORING_CONTRACT-2026-07-31.md Part 9, E2. Each RED case below is one of the contract's
 * own named failure modes (F1, F3, F6, F7, F8) plus the individual author obligations (A3, A4,
 * A5, A7, A9, A10) that produce them; each GREEN case is the same shape done correctly, proving
 * the gate does not cry wolf on a legitimate rename/removal/security change.
 */
class AuthoringDiffGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SHA_OK = "a".repeat(64);

    private static ModelAst model(String json) throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    private static JsonNode manifest(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    private static String baseManifest(String previousVersion, String submittedVersion, String extraFields) {
        return """
            {
              "recordKind": "npdev-authoring-submission.v1",
              "previousModelSha256": "%s",
              "previousModelVersion": "%s",
              "submittedModelVersion": "%s",
              "request": "test",
              "renames": [], "deliberateRemovals": [], "securityChanges": [],
              "couldNotExpress": [], "unchangedButSuspect": []
              %s
            }
            """.formatted(SHA_OK, previousVersion, submittedVersion, extraFields.isBlank() ? "" : "," + extraFields);
    }

    private static String conceptModel(String version, String conceptsJson) {
        return """
            { "dslVersion": "1.0.0", "namespace": "authoring.test", "version": "%s", "concepts": %s }
            """.formatted(version, conceptsJson);
    }

    /** A minimal, valid (non-empty per model.schema.json's concepts minItems:1) concept, used
     *  unchanged wherever a test only cares about version/sha/manifest-presence checks. */
    private static final String TRIVIAL_CONCEPT = """
        [ { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
        """;

    // --------------------------------------------------------------------------------------
    // F6 / A7 -- previousModelSha256 and version bump
    // --------------------------------------------------------------------------------------

    @Test
    void f6_mismatchedPreviousShaIsRefused() throws Exception {
        ModelAst previous = model(conceptModel("1.0", TRIVIAL_CONCEPT));
        ModelAst submitted = model(conceptModel("1.1", TRIVIAL_CONCEPT));
        JsonNode manifest = manifest(baseManifest("1.0", "1.1", ""));

        AuthoringDiffGate.GateResult result = AuthoringDiffGate.evaluate("b".repeat(64), previous, submitted, manifest);

        assertFalse(result.passed());
        assertTrue(hasCode(result, "AUTHORING_SHA_MISMATCH"), result.violations().toString());
    }

    @Test
    void a7_versionNotIncreasedIsRefused() throws Exception {
        ModelAst previous = model(conceptModel("1.0", TRIVIAL_CONCEPT));
        ModelAst submitted = model(conceptModel("1.0", TRIVIAL_CONCEPT));
        JsonNode manifest = manifest(baseManifest("1.0", "1.0", ""));

        AuthoringDiffGate.GateResult result = AuthoringDiffGate.evaluate(SHA_OK, previous, submitted, manifest);

        assertFalse(result.passed());
        assertTrue(hasCode(result, "AUTHORING_VERSION_NOT_INCREASED"), result.violations().toString());
    }

    @Test
    void versionIncreasePassesCleanlyOnAnUnchangedModel() throws Exception {
        ModelAst previous = model(conceptModel("1.0", TRIVIAL_CONCEPT));
        ModelAst submitted = model(conceptModel("1.1", TRIVIAL_CONCEPT));
        JsonNode manifest = manifest(baseManifest("1.0", "1.1", ""));

        AuthoringDiffGate.GateResult result = AuthoringDiffGate.evaluate(SHA_OK, previous, submitted, manifest);

        assertTrue(result.passed(), result.violations().toString());
    }

    @Test
    void noManifestIsRefused_C1() throws Exception {
        ModelAst previous = model(conceptModel("1.0", TRIVIAL_CONCEPT));
        ModelAst submitted = model(conceptModel("1.1", TRIVIAL_CONCEPT));

        AuthoringDiffGate.GateResult result = AuthoringDiffGate.evaluate(SHA_OK, previous, submitted, null);

        assertFalse(result.passed());
        assertTrue(hasCode(result, "AUTHORING_MANIFEST_MISSING"));
    }

    // --------------------------------------------------------------------------------------
    // F1 / A2 / C2 -- unaccounted removal
    // --------------------------------------------------------------------------------------

    private static final String ORDER_WITH_CLIENT_NAME = """
        [ { "name": "Order", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "clientName", "type": "string" } ] } ]
        """;

    @Test
    void f1_fieldSilentlyDroppedWithNoDeclarationIsRefused() throws Exception {
        ModelAst previous = model(conceptModel("1.0", ORDER_WITH_CLIENT_NAME));
        ModelAst submitted = model(conceptModel("1.1", """
            [ { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
            """));
        JsonNode manifest = manifest(baseManifest("1.0", "1.1", ""));

        AuthoringDiffGate.GateResult result = AuthoringDiffGate.evaluate(SHA_OK, previous, submitted, manifest);

        assertFalse(result.passed());
        assertTrue(hasCode(result, "AUTHORING_UNACCOUNTED_REMOVAL"), result.violations().toString());
    }

    @Test
    void aCleanRenameViaRenamedFromPassesWithNoManifestEntryNeeded() throws Exception {
        ModelAst previous = model(conceptModel("1.0", ORDER_WITH_CLIENT_NAME));
        ModelAst submitted = model(conceptModel("1.1", """
            [ { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "customerName", "type": "string", "renamedFrom": "clientName" } ] } ]
            """));
        JsonNode manifest = manifest(baseManifest("1.0", "1.1", ""));

        AuthoringDiffGate.GateResult result = AuthoringDiffGate.evaluate(SHA_OK, previous, submitted, manifest);

        assertTrue(result.passed(), result.violations().toString());
    }

    @Test
    void aDeclaredDeliberateRemovalPasses() throws Exception {
        ModelAst previous = model(conceptModel("1.0", ORDER_WITH_CLIENT_NAME));
        ModelAst submitted = model(conceptModel("1.1", """
            [ { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
            """));
        JsonNode manifest = manifest(baseManifest("1.0", "1.1", """
            "deliberateRemovals": [ { "kind": "field", "concept": "Order", "name": "clientName", "reason": "superseded" } ]
            """).replace("\"deliberateRemovals\": [],", ""));

        AuthoringDiffGate.GateResult result = AuthoringDiffGate.evaluate(SHA_OK, previous, submitted, manifest);

        assertTrue(result.passed(), result.violations().toString());
    }

    @Test
    void unaccountedConceptRemovalIsRefused() throws Exception {
        ModelAst previous = model(conceptModel("1.0", """
            [ { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
              { "name": "Gadget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
            """));
        ModelAst submitted = model(conceptModel("1.1", """
            [ { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
            """));
        JsonNode manifest = manifest(baseManifest("1.0", "1.1", ""));

        AuthoringDiffGate.GateResult result = AuthoringDiffGate.evaluate(SHA_OK, previous, submitted, manifest);

        assertFalse(result.passed());
        assertTrue(hasCode(result, "AUTHORING_UNACCOUNTED_REMOVAL"), result.violations().toString());
    }

    // --------------------------------------------------------------------------------------
    // A4 -- hallucinated rename
    // --------------------------------------------------------------------------------------

    @Test
    void a4_renamedFromNamingSomethingThatNeverExistedIsRefused() throws Exception {
        ModelAst previous = model(conceptModel("1.0", ORDER_WITH_CLIENT_NAME));
        ModelAst submitted = model(conceptModel("1.1", """
            [ { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "clientName", "type": "string" },
                  { "name": "customerName", "type": "string", "renamedFrom": "neverExisted" } ] } ]
            """));
        JsonNode manifest = manifest(baseManifest("1.0", "1.1", ""));

        AuthoringDiffGate.GateResult result = AuthoringDiffGate.evaluate(SHA_OK, previous, submitted, manifest);

        assertFalse(result.passed());
        assertTrue(hasCode(result, "AUTHORING_HALLUCINATED_RENAME"), result.violations().toString());
    }

    // --------------------------------------------------------------------------------------
    // F7 / A3 -- name reused for a different thing
    // --------------------------------------------------------------------------------------

    @Test
    void f7_deliberatelyRemovedNameStillPresentIsRefused() throws Exception {
        ModelAst previous = model(conceptModel("1.0", """
            [ { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "status", "type": "string" } ] } ]
            """));
        ModelAst submitted = model(conceptModel("1.1", """
            [ { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "status", "type": "reference", "reference": {"target": "Order"} } ] } ]
            """));
        JsonNode manifest = manifest(baseManifest("1.0", "1.1", """
            "deliberateRemovals": [ { "kind": "field", "concept": "Order", "name": "status", "reason": "retired the enum" } ]
            """).replace("\"deliberateRemovals\": [],", ""));

        AuthoringDiffGate.GateResult result = AuthoringDiffGate.evaluate(SHA_OK, previous, submitted, manifest);

        assertFalse(result.passed());
        assertTrue(hasCode(result, "AUTHORING_REUSED_REMOVED_NAME"), result.violations().toString());
    }

    // --------------------------------------------------------------------------------------
    // F8 / A5 -- rename + shape change in one step
    // --------------------------------------------------------------------------------------

    @Test
    void f8_renameAndTypeWideningInOneStepIsRefused() throws Exception {
        ModelAst previous = model(conceptModel("1.0", """
            [ { "name": "Widget", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "qty", "type": "int" } ] } ]
            """));
        ModelAst submitted = model(conceptModel("1.1", """
            [ { "name": "Widget", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "quantity", "type": "long", "renamedFrom": "qty" } ] } ]
            """));
        JsonNode manifest = manifest(baseManifest("1.0", "1.1", ""));

        AuthoringDiffGate.GateResult result = AuthoringDiffGate.evaluate(SHA_OK, previous, submitted, manifest);

        assertFalse(result.passed());
        assertTrue(hasCode(result, "AUTHORING_RENAME_WITH_SHAPE_CHANGE"), result.violations().toString());
    }

    @Test
    void aPureRenameWithNoShapeChangePasses() throws Exception {
        ModelAst previous = model(conceptModel("1.0", """
            [ { "name": "Widget", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "qty", "type": "int" } ] } ]
            """));
        ModelAst submitted = model(conceptModel("1.1", """
            [ { "name": "Widget", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "quantity", "type": "int", "renamedFrom": "qty" } ] } ]
            """));
        JsonNode manifest = manifest(baseManifest("1.0", "1.1", ""));

        AuthoringDiffGate.GateResult result = AuthoringDiffGate.evaluate(SHA_OK, previous, submitted, manifest);

        assertTrue(result.passed(), result.violations().toString());
    }

    // --------------------------------------------------------------------------------------
    // F3 / A9 / A10 / E6 -- undeclared security-relevant change
    // --------------------------------------------------------------------------------------

    private static final String INVOICE_WITH_ACCESS = """
        [ { "name": "Invoice",
            "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "ownerId", "type": "string" } ],
            "access": { "write": "$user.id == $row.ownerId" } } ]
        """;

    @Test
    void f3_undeclaredAccessWriteWideningIsRefused() throws Exception {
        ModelAst previous = model(conceptModel("1.0", INVOICE_WITH_ACCESS));
        ModelAst submitted = model(conceptModel("1.1", """
            [ { "name": "Invoice",
                "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true },
                            { "name": "ownerId", "type": "string" } ],
                "access": { "write": "$user.id == $row.ownerId || 'finance' in $user.roles" } } ]
            """));
        JsonNode manifest = manifest(baseManifest("1.0", "1.1", ""));

        AuthoringDiffGate.GateResult result = AuthoringDiffGate.evaluate(SHA_OK, previous, submitted, manifest);

        assertFalse(result.passed());
        assertTrue(hasCode(result, "AUTHORING_UNDECLARED_SECURITY_CHANGE"), result.violations().toString());
    }

    @Test
    void aDeclaredAccessWriteChangePasses() throws Exception {
        ModelAst previous = model(conceptModel("1.0", INVOICE_WITH_ACCESS));
        ModelAst submitted = model(conceptModel("1.1", """
            [ { "name": "Invoice",
                "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true },
                            { "name": "ownerId", "type": "string" } ],
                "access": { "write": "$user.id == $row.ownerId || 'finance' in $user.roles" } } ]
            """));
        JsonNode manifest = manifest(baseManifest("1.0", "1.1", """
            "securityChanges": [ { "kind": "access.write", "concept": "Invoice", "from": "old", "to": "new", "rationale": "finance reassignment" } ]
            """).replace("\"securityChanges\": [],", ""));

        AuthoringDiffGate.GateResult result = AuthoringDiffGate.evaluate(SHA_OK, previous, submitted, manifest);

        assertTrue(result.passed(), result.violations().toString());
    }

    @Test
    void undeclaredSensitiveFlagChangeIsRefused() throws Exception {
        ModelAst previous = model(conceptModel("1.0", """
            [ { "name": "Customer", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "ssn", "type": "string" } ] } ]
            """));
        ModelAst submitted = model(conceptModel("1.1", """
            [ { "name": "Customer", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "ssn", "type": "string", "sensitive": true } ] } ]
            """));
        JsonNode manifest = manifest(baseManifest("1.0", "1.1", ""));

        AuthoringDiffGate.GateResult result = AuthoringDiffGate.evaluate(SHA_OK, previous, submitted, manifest);

        assertFalse(result.passed());
        assertTrue(hasCode(result, "AUTHORING_UNDECLARED_SECURITY_CHANGE"), result.violations().toString());
    }

    @Test
    void undeclaredInvariantRemovalIsRefused() throws Exception {
        ModelAst previous = model(conceptModel("1.0", """
            [ { "name": "Order",
                "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true },
                            { "name": "total", "type": "int" } ],
                "invariants": [ { "type": "expression", "expression": "total >= 0" } ] } ]
            """));
        ModelAst submitted = model(conceptModel("1.1", """
            [ { "name": "Order",
                "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true },
                            { "name": "total", "type": "int" } ],
                "invariants": [] } ]
            """));
        JsonNode manifest = manifest(baseManifest("1.0", "1.1", ""));

        AuthoringDiffGate.GateResult result = AuthoringDiffGate.evaluate(SHA_OK, previous, submitted, manifest);

        assertFalse(result.passed());
        assertTrue(hasCode(result, "AUTHORING_UNDECLARED_SECURITY_CHANGE"), result.violations().toString());
    }

    // --------------------------------------------------------------------------------------
    // Additive-only submissions are always clean
    // --------------------------------------------------------------------------------------

    @Test
    void addingANewConceptAndFieldRequiresNoDeclarationAtAll() throws Exception {
        ModelAst previous = model(conceptModel("1.0", ORDER_WITH_CLIENT_NAME));
        ModelAst submitted = model(conceptModel("1.1", """
            [ { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "clientName", "type": "string" },
                  { "name": "notes", "type": "string" } ] },
              { "name": "Shipment", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
            """));
        JsonNode manifest = manifest(baseManifest("1.0", "1.1", ""));

        AuthoringDiffGate.GateResult result = AuthoringDiffGate.evaluate(SHA_OK, previous, submitted, manifest);

        assertTrue(result.passed(), result.violations().toString());
    }

    private static boolean hasCode(AuthoringDiffGate.GateResult result, String code) {
        Set<String> codes = result.violations().stream().map(AuthoringDiffGate.Violation::code).collect(Collectors.toSet());
        return codes.contains(code);
    }
}
