package com.npdev.dsl.v1.pack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PACK-14: the reference classifier's three arms -- intra-pack rewrite, cross-pack dependency,
 * and unresolved refusal -- all exercised against in-memory concepts and a temp packs root.
 */
class PackExportReferenceClassifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode concept(String name, String fieldName, String referenceTarget) {
        ObjectNode concept = MAPPER.createObjectNode();
        concept.put("name", name);
        ObjectNode field = concept.putArray("fields").addObject();
        field.put("name", fieldName);
        field.put("reference", referenceTarget);
        return concept;
    }

    @Test
    void rewritesAQualifiedReferenceToAnotherExportedConceptToIntraPackForm(@TempDir Path tmp) {
        ObjectNode a = concept("A", "owner", "somepack::B");
        ObjectNode b = MAPPER.createObjectNode();
        b.put("name", "B");

        PackExportReferenceClassifier.Result result =
                PackExportReferenceClassifier.classify(List.of(a, b), Set.of("A", "B"), tmp);

        assertEquals("B", a.path("fields").get(0).path("reference").asText());
        assertTrue(result.unresolved().isEmpty());
        assertEquals(1, result.rewrites().size());
        assertEquals("A.owner.reference", result.rewrites().get(0).get("field"));
        assertEquals("somepack::B", result.rewrites().get(0).get("from"));
        assertEquals("B", result.rewrites().get(0).get("to"));
    }

    @Test
    void leavesAQualifiedCrossPackReferenceAndRecordsTheDependency(@TempDir Path tmp) throws Exception {
        Path identityPack = tmp.resolve("identity");
        Files.createDirectories(identityPack);
        Files.writeString(identityPack.resolve("pack.json"),
                "{\"pack\":\"identity\",\"version\":\"1.2.3\"}");

        ObjectNode a = concept("A", "owner", "identity::User");
        PackExportReferenceClassifier.Result result =
                PackExportReferenceClassifier.classify(List.of(a), Set.of("A"), tmp);

        assertEquals("identity::User", a.path("fields").get(0).path("reference").asText());
        assertTrue(result.unresolved().isEmpty());
        assertEquals("^1.2", result.crossPackVersions().get("identity"));
    }

    @Test
    void flagsAnUnresolvedBareReferenceRatherThanDroppingIt(@TempDir Path tmp) {
        ObjectNode a = concept("A", "owner", "Missing");
        PackExportReferenceClassifier.Result result =
                PackExportReferenceClassifier.classify(List.of(a), Set.of("A"), tmp);

        assertEquals("Missing", a.path("fields").get(0).path("reference").asText());
        assertFalse(result.unresolved().isEmpty());
        assertEquals("A.owner.reference", result.unresolved().get(0).get("field"));
        assertEquals("Missing", result.unresolved().get(0).get("target"));
    }
}
