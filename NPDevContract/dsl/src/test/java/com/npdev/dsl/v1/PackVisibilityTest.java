package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4.1 (NPDEV_PATH_A_REALIGNMENT_PLAN.md): "a pack declares its public surface ... and its
 * extension points; referencing a private member refuses composition naming the pack and the
 * member." Real end-to-end proof through {@link ModelSourceResolver#resolve}, mirroring {@code
 * SpecializationParentVersionDriftTest}'s own template (no mocking of the merge pipeline).
 *
 * <p>Scope: covers every reference field {@code ModelSourceResolver.rewriteKnownMemberReferenceFields}
 * already knows about (domainType, conceptRef, specializes/extends, and the per-kind
 * query/flow/procedure/panel/... fields) -- the SAME choke point D3's context-import gate uses, and
 * where every such reference (bare-resolved or authored-qualified) ends up fully qualified exactly
 * once, after every pack is merged. A plain concept-to-concept FK field ({@code field.ref}/{@code
 * field.reference.target}) is NOT covered -- that target is rewritten by a separate, earlier,
 * pack-LOCAL-only mechanism ({@code namespacePackFieldRefs}) that predates this check and (like D3's
 * own context gate) has never participated in the qualified-reference-validator plumbing.
 */
class PackVisibilityTest {

    @TempDir
    Path temp;

    private static final String PACK_WITH_PRIVATE_DOMAIN_TYPE = """
            {
              "dslVersion": "1.0.0",
              "pack": "identity",
              "version": "1.0.0",
              "domainTypes": [
                { "name": "InternalCode", "baseType": "string" },
                { "name": "DisplayName", "baseType": "string" }
              ],
              "private": ["InternalCode"]
            }
            """;

    @Test
    void bareReferenceToPrivateMemberRefusesNamingPackAndMember() throws Exception {
        write("packs/identity/pack.json", PACK_WITH_PRIVATE_DOMAIN_TYPE);
        Path model = write("model.json", """
                {
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "$ref": "packs/identity/pack.json" } ],
                  "concepts": [
                    { "name": "Order", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "code", "type": "string", "domainType": "InternalCode" }
                    ] }
                  ]
                }
                """);

        IOException e = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(e.getMessage().contains("PACK_VISIBILITY"), e.getMessage());
        assertTrue(e.getMessage().contains("identity"), e.getMessage());
        assertTrue(e.getMessage().contains("InternalCode"), e.getMessage());
    }

    @Test
    void explicitlyQualifiedReferenceToAPrivateMemberAlsoRefuses() throws Exception {
        write("packs/identity/pack.json", PACK_WITH_PRIVATE_DOMAIN_TYPE);
        Path model = write("model.json", """
                {
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "$ref": "packs/identity/pack.json" } ],
                  "concepts": [
                    { "name": "Order", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "code", "type": "string", "domainType": "identity::InternalCode" }
                    ] }
                  ]
                }
                """);

        IOException e = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(e.getMessage().contains("PACK_VISIBILITY"), e.getMessage());
        assertTrue(e.getMessage().contains("InternalCode"), e.getMessage());
    }

    @Test
    void referenceToAPublicMemberOfTheSamePackStillSucceeds() throws Exception {
        write("packs/identity/pack.json", PACK_WITH_PRIVATE_DOMAIN_TYPE);
        Path model = write("model.json", """
                {
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "$ref": "packs/identity/pack.json" } ],
                  "concepts": [
                    { "name": "Order", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "label", "type": "string", "domainType": "DisplayName" }
                    ] }
                  ]
                }
                """);

        ResolvedModelSource source = new ModelSourceResolver().resolve(model);

        JsonNode field = conceptNamed(source, "Order").get("fields").get(1);
        assertEquals("identity::DisplayName", field.get("domainType").asText());
    }

    @Test
    void packsOwnMembersMayReferenceItsPrivateMembersFreely() throws Exception {
        write("packs/identity/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "identity",
                  "version": "1.0.0",
                  "domainTypes": [
                    { "name": "InternalCode", "baseType": "string" }
                  ],
                  "concepts": [
                    { "name": "User", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "code", "type": "string", "domainType": "InternalCode" }
                    ] }
                  ],
                  "private": ["InternalCode"]
                }
                """);
        Path model = write("model.json", """
                {
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "$ref": "packs/identity/pack.json" } ]
                }
                """);

        ResolvedModelSource source = new ModelSourceResolver().resolve(model);

        JsonNode field = conceptNamed(source, "identity::User").get("fields").get(1);
        assertEquals("identity::InternalCode", field.get("domainType").asText());
    }

    @Test
    void crossPackReferenceToAnotherPacksPrivateMemberRefuses() throws Exception {
        write("packs/identity/pack.json", PACK_WITH_PRIVATE_DOMAIN_TYPE);
        write("packs/billing/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "billing",
                  "version": "1.0.0",
                  "concepts": [
                    { "name": "Invoice", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "code", "type": "string", "domainType": "identity::InternalCode" }
                    ] }
                  ]
                }
                """);
        Path model = write("model.json", """
                {
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [
                    { "$ref": "packs/identity/pack.json" },
                    { "$ref": "packs/billing/pack.json" }
                  ]
                }
                """);

        IOException e = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(e.getMessage().contains("PACK_VISIBILITY"), e.getMessage());
        assertTrue(e.getMessage().contains("identity"), e.getMessage());
        assertTrue(e.getMessage().contains("InternalCode"), e.getMessage());
    }

    @Test
    void specializingAPrivateConceptRefusesUnlessDeclaredAsAnExtensionPoint() throws Exception {
        write("packs/identity/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "identity",
                  "version": "1.0.0",
                  "concepts": [
                    { "name": "InternalBase", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] }
                  ],
                  "private": ["InternalBase"]
                }
                """);
        Path model = write("model.json", """
                {
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "$ref": "packs/identity/pack.json" } ],
                  "concepts": [
                    { "name": "Derived", "specializes": "identity::InternalBase", "fields": [
                      { "name": "extra", "type": "string" }
                    ] }
                  ]
                }
                """);

        IOException e = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(e.getMessage().contains("PACK_VISIBILITY"), e.getMessage());
        assertTrue(e.getMessage().contains("InternalBase"), e.getMessage());
    }

    @Test
    void specializingADeclaredExtensionPointSucceedsDespiteBeingPrivate() throws Exception {
        write("packs/identity/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "identity",
                  "version": "1.0.0",
                  "concepts": [
                    { "name": "InternalBase", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] }
                  ],
                  "private": ["InternalBase"],
                  "extensionPoints": ["InternalBase"]
                }
                """);
        Path model = write("model.json", """
                {
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "$ref": "packs/identity/pack.json" } ],
                  "concepts": [
                    { "name": "Derived", "specializes": "identity::InternalBase", "fields": [
                      { "name": "extra", "type": "string" }
                    ] }
                  ]
                }
                """);

        ResolvedModelSource source = new ModelSourceResolver().resolve(model);

        assertEquals("identity::InternalBase", conceptNamed(source, "Derived").get("specializes").asText());
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private JsonNode conceptNamed(ResolvedModelSource source, String name) {
        for (JsonNode concept : source.resolvedRoot().get("concepts")) {
            if (name.equals(concept.get("name").asText())) {
                return concept;
            }
        }
        throw new AssertionError("concept '" + name + "' not found in resolved model: " + source.resolvedRoot());
    }

    private Path write(String relative, String content) throws Exception {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
