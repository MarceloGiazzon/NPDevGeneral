package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.dsl.v1.validation.ModelSchemaValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PK-3: a pack may itself declare {@code packs[]} (transitive dependencies). Proves the diamond
 * shape the card's own proof section names: {@code app -> crm -> user}, {@code app -> billing ->
 * user} resolves to one merged {@code user} pack, and cross-pack references written using a
 * dependency's real pack id ({@code user::Something}) resolve correctly with zero rewriting in
 * this common, no-alias-collision case (see PackDependencyGraphWalker's own qualifier-assignment
 * doc comment for why).
 */
class PackTransitiveDependencyResolutionTest {

    @TempDir
    Path temp;

    @Test
    void diamondDependencyMergesOneUserPackAndCrossPackReferencesResolve() throws Exception {
        write("packs/user/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "user",
                  "version": "2.5.0",
                  "concepts": [
                    { "name": "Account", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "email", "type": "string", "required": true }
                    ] }
                  ]
                }
                """);
        write("packs/crm/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "crm",
                  "version": "1.0.0",
                  "packs": [ { "pack": "user", "version": "^2.0" } ],
                  "concepts": [
                    { "name": "Lead", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "ownerAccount", "type": "reference", "ref": "user::Account" }
                    ] }
                  ]
                }
                """);
        write("packs/billing/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "billing",
                  "version": "1.0.0",
                  "packs": [ { "pack": "user", "version": "^2.0" } ],
                  "concepts": [
                    { "name": "Invoice", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "billedAccount", "type": "reference", "ref": "user::Account" }
                    ] }
                  ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "diamond.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [
                    { "$ref": "packs/crm/pack.json" },
                    { "$ref": "packs/billing/pack.json" }
                  ]
                }
                """);

        ResolvedModelSource source = new ModelSourceResolver().resolve(model);
        JsonNode concepts = source.resolvedRoot().get("concepts");

        boolean sawUserAccount = false;
        int userAccountCount = 0;
        for (JsonNode concept : concepts) {
            String name = concept.get("name").asText();
            if ("user::Account".equals(name)) {
                sawUserAccount = true;
                userAccountCount++;
            }
        }
        assertTrue(sawUserAccount, "user::Account must be merged exactly once from the diamond");
        assertEquals(1, userAccountCount, "user pack must be merged exactly once, not once per path that reaches it");

        // Both dependents' hardcoded cross-pack references (written using user's real packId,
        // since neither crm nor billing's author can know an importer's future alias) must resolve
        // to the SAME qualifier user's own concepts were merged under -- zero rewriting needed in
        // this common (no direct-alias-collision) case.
        for (JsonNode concept : concepts) {
            String name = concept.get("name").asText();
            if ("crm::Lead".equals(name) || "billing::Invoice".equals(name)) {
                JsonNode fields = concept.get("fields");
                boolean foundRef = false;
                for (JsonNode field : fields) {
                    if (field.has("ref")) {
                        assertEquals("user::Account", field.get("ref").asText(),
                                name + "'s cross-pack reference must resolve to user::Account unchanged");
                        foundRef = true;
                    }
                }
                assertTrue(foundRef, name + " must have its cross-pack reference field");
            }
        }
    }

    @Test
    void crossMajorConstraintsOnTheSamePackRefuseNamingBothPaths() throws Exception {
        write("packs/user/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "user",
                  "version": "2.5.0",
                  "concepts": [
                    { "name": "Account", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] }
                  ]
                }
                """);
        write("packs/crm/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "crm",
                  "version": "1.0.0",
                  "packs": [ { "pack": "user", "version": "^2.0" } ],
                  "concepts": [
                    { "name": "Lead", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] }
                  ]
                }
                """);
        write("packs/billing/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "billing",
                  "version": "1.0.0",
                  "packs": [ { "pack": "user", "version": "^3.0" } ],
                  "concepts": [
                    { "name": "Invoice", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] }
                  ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "diamond.conflict.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [
                    { "$ref": "packs/crm/pack.json" },
                    { "$ref": "packs/billing/pack.json" }
                  ]
                }
                """);

        IOException thrown = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        String message = thrown.getMessage();
        assertTrue(message.contains("crm"), "must name crm, got: " + message);
        assertTrue(message.contains("billing"), "must name billing, got: " + message);
        assertTrue(message.contains("app -> crm"), "must name crm's path, got: " + message);
        assertTrue(message.contains("app -> billing"), "must name billing's path, got: " + message);
    }

    @Test
    void packDependencyCycleFailsPrintingThePath() throws Exception {
        write("packs/a/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "a",
                  "version": "1.0.0",
                  "packs": [ { "pack": "b", "version": "^1.0" } ]
                }
                """);
        write("packs/b/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "b",
                  "version": "1.0.0",
                  "packs": [ { "pack": "a", "version": "^1.0" } ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "cycle.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "$ref": "packs/a/pack.json" } ]
                }
                """);

        IOException thrown = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        // The DFS can legitimately report the cycle starting from either "a" or "b" depending on
        // discovery order (both "a -> b -> a" and "b -> a -> b" name the same real cycle) -- assert
        // the shape, not one specific rotation.
        String message = thrown.getMessage();
        assertTrue(message.contains("Pack dependency cycle detected"), "must name a pack cycle, got: " + message);
        assertTrue(message.contains("a -> b -> a") || message.contains("b -> a -> b"),
                "cycle message must name the actual path (either rotation), got: " + message);
    }

    @Test
    void depthCapRefusesNamingTheLimit() throws Exception {
        // 9 packs chained a0 -> a1 -> ... -> a8, exceeding MAX_PACK_DEPTH (8).
        for (int i = 0; i < 9; i++) {
            String selfId = "a" + i;
            String depsBlock = i < 8
                    ? "\"packs\": [ { \"pack\": \"a" + (i + 1) + "\", \"version\": \"^1.0\" } ],"
                    : "";
            write("packs/" + selfId + "/pack.json", """
                    {
                      "dslVersion": "1.0.0",
                      "pack": "%s",
                      "version": "1.0.0",
                      %s
                      "concepts": [ { "name": "C", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ] } ]
                    }
                    """.formatted(selfId, depsBlock));
        }
        Path model = write("model.json", """
                {
                  "namespace": "depth.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "$ref": "packs/a0/pack.json" } ]
                }
                """);

        IOException thrown = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(thrown.getMessage().contains("maximum depth"),
                "must refuse naming the depth cap, got: " + thrown.getMessage());
    }

    @Test
    void packWithNoTransitiveDependenciesResolvesExactlyAsBeforePk3() throws Exception {
        // No-op regression proof: a pack that declares no packs[] at all (every existing pack in
        // this repo today) must resolve byte-identically to pre-PK-3 behavior.
        write("packs/simple/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "simple",
                  "version": "1.0.0",
                  "concepts": [
                    { "name": "Widget", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] }
                  ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "noop.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "$ref": "packs/simple/pack.json" } ]
                }
                """);

        ResolvedModelSource source = new ModelSourceResolver().resolve(model);
        JsonNode concepts = source.resolvedRoot().get("concepts");
        assertEquals(1, concepts.size());
        assertEquals("simple::Widget", concepts.get(0).get("name").asText());
    }

    private Path write(String relative, String content) throws Exception {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
