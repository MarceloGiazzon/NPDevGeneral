package com.npdev.generator.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S5 ({@code __OutsideRepo\s5\S5_SPEC.md} I2-I4). Each case is one of the spec's own Session DoD
 * bullets: two disjoint submissions both land; two overlapping submissions refuse naming the
 * colliding elements; the merged model round-trips canonically; element-disjoint but semantically
 * invalid is rejected naming the dangling reference (H2); a security delta on either side requires
 * acknowledgement (H3); and it all works on a real, non-context corpus model.
 */
class AuthoringMergeGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EMPTY_MANIFEST = """
        { "securityChanges": [] }
        """;

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    private static ModelAst model(JsonNode json) throws Exception {
        return new JsonModelParser().parse(json);
    }

    private static final String BASE = """
        { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.0",
          "concepts": [
            { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
          ] }
        """;

    // --------------------------------------------------------------------------------------
    // I2 -- disjoint merges land, overlapping merges refuse naming the collision
    // --------------------------------------------------------------------------------------

    @Test
    void twoDisjointSubmissionsBothLand() throws Exception {
        JsonNode base = json(BASE);
        JsonNode ours = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.1",
              "concepts": [
                { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "Shipment", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ] }
            """);
        JsonNode theirs = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.1",
              "concepts": [
                { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "Gadget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ] }
            """);
        JsonNode manifest = json(EMPTY_MANIFEST);

        AuthoringMergeGate.MergeResult result = AuthoringMergeGate.merge(
                base, model(base), ours, model(ours), theirs, model(theirs), manifest, manifest);

        assertTrue(result.merged(), result.violations().toString());
        Set<String> conceptNames = names(result.mergedModel().get("concepts"));
        assertEquals(Set.of("Order", "Shipment", "Gadget"), conceptNames);
        assertEquals(Set.of(new ElementDiffer.ElementKey("concepts", "Shipment")), result.elementsFromOurs());
        assertEquals(Set.of(new ElementDiffer.ElementKey("concepts", "Gadget")), result.elementsFromTheirs());
    }

    @Test
    void overlappingSubmissionsAreRefusedNamingTheCollidingElement() throws Exception {
        JsonNode base = json(BASE);
        JsonNode ours = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.1",
              "concepts": [
                { "name": "Order", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "notes", "type": "string" } ] } ] }
            """);
        JsonNode theirs = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.1",
              "concepts": [
                { "name": "Order", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "total", "type": "int" } ] } ] }
            """);
        JsonNode manifest = json(EMPTY_MANIFEST);

        AuthoringMergeGate.MergeResult result = AuthoringMergeGate.merge(
                base, model(base), ours, model(ours), theirs, model(theirs), manifest, manifest);

        assertFalse(result.merged());
        assertTrue(hasCode(result, "AUTHORING_MERGE_ELEMENT_COLLISION"), result.violations().toString());
        assertTrue(result.violations().get(0).message().contains("concepts[Order]"),
                "expected the colliding element named: " + result.violations());
    }

    @Test
    void mergedModelRoundTripsCanonically() throws Exception {
        JsonNode base = json(BASE);
        JsonNode ours = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.1",
              "concepts": [
                { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "Shipment", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ] }
            """);
        JsonNode theirs = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.1",
              "concepts": [
                { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "Gadget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ] }
            """);
        JsonNode manifest = json(EMPTY_MANIFEST);

        AuthoringMergeGate.MergeResult result = AuthoringMergeGate.merge(
                base, model(base), ours, model(ours), theirs, model(theirs), manifest, manifest);
        assertTrue(result.merged(), result.violations().toString());

        String write1 = AuthoringMergeGate.toJson(result.mergedModel());
        ObjectNode reparsed = AuthoringMergeGate.fromJson(write1);
        String write2 = AuthoringMergeGate.toJson(reparsed);

        assertEquals(write1, write2, "toJson(fromJson(toJson(m))) must equal toJson(m)");
    }

    // --------------------------------------------------------------------------------------
    // I3 / H2 -- element-disjoint but semantically invalid is rejected, naming the dangling ref
    // --------------------------------------------------------------------------------------

    @Test
    void elementDisjointButSemanticallyInvalidIsRejectedNamingTheDanglingReference() throws Exception {
        JsonNode base = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.0",
              "concepts": [
                { "name": "Anchor", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ] }
            """);
        // OURS: adds a flow referencing Widget (Widget itself untouched by OURS).
        JsonNode ours = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.1",
              "concepts": [
                { "name": "Anchor", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ],
              "flows": [
                { "name": "UseWidget", "input": { "concept": "Widget", "mode": "update" },
                  "steps": [ { "name": "s1", "type": "return", "value": "$input" } ] }
              ] }
            """);
        // THEIRS: removes Widget entirely (unaware OURS' flow will come to depend on it).
        JsonNode theirs = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.1",
              "concepts": [
                { "name": "Anchor", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ] }
            """);
        JsonNode manifest = json(EMPTY_MANIFEST);

        AuthoringMergeGate.MergeResult result = AuthoringMergeGate.merge(
                base, model(base), ours, model(ours), theirs, model(theirs), manifest, manifest);

        assertFalse(result.merged());
        assertTrue(hasCode(result, "AUTHORING_MERGE_INVALID_RESULT"), result.violations().toString());
        boolean namesTheDanglingReference = result.violations().stream()
                .anyMatch(v -> v.message().contains("Widget"));
        assertTrue(namesTheDanglingReference,
                "expected the error to name the dangling reference to 'Widget', not just 'merge conflict': "
                        + result.violations());
    }

    // --------------------------------------------------------------------------------------
    // I4 / H3 -- a security delta on either side requires acknowledgement
    // --------------------------------------------------------------------------------------

    private static final String INVOICE_WITH_ACCESS = """
        { "name": "Invoice",
          "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "ownerId", "type": "string" } ],
          "access": { "write": "$user.id == $row.ownerId" } }
        """;

    @Test
    void aMergeThatWidensAccessCannotProceedUnattended() throws Exception {
        JsonNode base = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.0",
              "concepts": [ %s ] }
            """.formatted(INVOICE_WITH_ACCESS));
        // OURS: adds an unrelated concept, Invoice itself untouched.
        JsonNode ours = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.1",
              "concepts": [ %s,
                { "name": "Shipment", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ] }
            """.formatted(INVOICE_WITH_ACCESS));
        // THEIRS: widens Invoice's write access, undeclared.
        JsonNode theirs = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.1",
              "concepts": [
                { "name": "Invoice",
                  "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true },
                              { "name": "ownerId", "type": "string" } ],
                  "access": { "write": "$user.id == $row.ownerId || $user.role == 'finance'" } } ] }
            """);
        JsonNode manifest = json(EMPTY_MANIFEST);

        AuthoringMergeGate.MergeResult result = AuthoringMergeGate.merge(
                base, model(base), ours, model(ours), theirs, model(theirs), manifest, manifest);

        assertFalse(result.merged());
        assertTrue(hasCode(result, "AUTHORING_UNDECLARED_SECURITY_CHANGE"), result.violations().toString());
    }

    @Test
    void aMergeThatWidensAccessPassesWhenDeclaredByEitherSide() throws Exception {
        JsonNode base = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.0",
              "concepts": [ %s ] }
            """.formatted(INVOICE_WITH_ACCESS));
        JsonNode ours = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.1",
              "concepts": [ %s,
                { "name": "Shipment", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ] }
            """.formatted(INVOICE_WITH_ACCESS));
        JsonNode theirs = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.1",
              "concepts": [
                { "name": "Invoice",
                  "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true },
                              { "name": "ownerId", "type": "string" } ],
                  "access": { "write": "$user.id == $row.ownerId || $user.role == 'finance'" } } ] }
            """);
        JsonNode oursManifest = json(EMPTY_MANIFEST);
        JsonNode theirsManifest = json("""
            { "securityChanges": [ { "kind": "access.write", "concept": "Invoice", "from": "old", "to": "new",
                                      "rationale": "finance reassignment" } ] }
            """);

        AuthoringMergeGate.MergeResult result = AuthoringMergeGate.merge(
                base, model(base), ours, model(ours), theirs, model(theirs), oursManifest, theirsManifest);

        assertTrue(result.merged(), result.violations().toString());
    }

    // --------------------------------------------------------------------------------------
    // Works on a real, non-context corpus model (30 of 32 have no contexts -- this is the
    // common case, not the optimization-only path)
    // --------------------------------------------------------------------------------------

    @Test
    void worksOnARealNonContextCorpusModel() throws Exception {
        Path modelPath = resolvePath(List.of(
                Path.of("..", "NPDevSamples", "simple-user-registry", "Input", "model.json"),
                Path.of("..", "..", "NPDevSamples", "simple-user-registry", "Input", "model.json"),
                Path.of("..", "..", "..", "NPDevSamples", "simple-user-registry", "Input", "model.json")
        ));
        JsonNode base = MAPPER.readTree(Files.readString(modelPath));
        assertFalse(base.has("contexts"), "this fixture is only meaningful if the corpus model has no contexts");

        ObjectNode ours = base.deepCopy();
        ours.put("version", bumpedVersion(base));
        ((com.fasterxml.jackson.databind.node.ArrayNode) ours.get("concepts")).add(
                json("""
                    { "name": "AuditNote", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                    """));

        ObjectNode theirs = base.deepCopy();
        theirs.put("version", bumpedVersion(base));
        ((com.fasterxml.jackson.databind.node.ArrayNode) theirs.get("concepts")).add(
                json("""
                    { "name": "LoginAttempt", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                    """));

        JsonNode manifest = json(EMPTY_MANIFEST);
        AuthoringMergeGate.MergeResult result = AuthoringMergeGate.merge(
                base, model(base), ours, model(ours), theirs, model(theirs), manifest, manifest);

        assertTrue(result.merged(), result.violations().toString());
        Set<String> conceptNames = names(result.mergedModel().get("concepts"));
        assertTrue(conceptNames.containsAll(Set.of("AuditNote", "LoginAttempt")), conceptNames.toString());
    }

    private static String bumpedVersion(JsonNode base) {
        String version = base.get("version").asText();
        return version + ".1";
    }

    private static Set<String> names(JsonNode array) {
        Set<String> out = new java.util.LinkedHashSet<>();
        if (array != null && array.isArray()) {
            for (JsonNode element : array) {
                out.add(element.get("name").asText());
            }
        }
        return out;
    }

    private static boolean hasCode(AuthoringMergeGate.MergeResult result, String code) {
        return result.violations().stream().anyMatch(v -> v.code().equals(code));
    }

    private static Path resolvePath(List<Path> candidates) {
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not resolve any candidate path: " + candidates);
    }
}
