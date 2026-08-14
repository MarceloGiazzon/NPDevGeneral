package com.npdev.dsl.v1.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BT-2 (PACK-ROADMAP.md): the RED-then-GREEN proof for {@link PackSealednessAnalyzer} the card's own
 * "Proof" section asks for -- a real in-repo pack (identity) must analyze sealed, and a synthetic
 * pack with each named violation (outbound reference, unbound capability, transitive dependency,
 * fragment-declared concept) must be refused, naming the reason.
 */
class PackSealednessAnalyzerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode pack(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** The real built-in identity pack (NPDevContract/packs/identity/pack.json) -- every reference
     *  field targets another concept declared in the same file, no packs[], no requires. This is the
     *  pack BT-2's own slice targets as its first real sealed pack. */
    @Test
    void realIdentityPack_isSealed() throws IOException {
        Path packFile = Path.of("..", "packs", "identity", "pack.json").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(packFile), "expected " + packFile + " to exist");
        JsonNode identityPack = MAPPER.readTree(packFile.toFile());

        PackSealednessAnalyzer.SealednessResult result = PackSealednessAnalyzer.analyze(identityPack);

        assertTrue(result.sealed(), "identity pack should be sealed, violations: " + result.violations());
        assertTrue(result.violations().isEmpty());
    }

    @Test
    void packWithOnlySelfContainedReferences_isSealed() {
        JsonNode sealedPack = pack("""
            {
              "dslVersion": "1.0.0",
              "pack": "widgetcatalog",
              "version": "1.0.0",
              "concepts": [
                { "name": "Widget", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "sku", "type": "string", "required": true }
                ]},
                { "name": "WidgetPrice", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "widgetId", "type": "reference", "required": true,
                    "reference": { "target": "Widget", "onDelete": "cascade" } }
                ]}
              ]
            }
            """);

        PackSealednessAnalyzer.SealednessResult result = PackSealednessAnalyzer.analyze(sealedPack);

        assertTrue(result.sealed(), "violations: " + result.violations());
    }

    @Test
    void outboundReferenceToNonPackConcept_isRefused_namingTheConceptAndTarget() {
        JsonNode unsealedPack = pack("""
            {
              "dslVersion": "1.0.0",
              "pack": "orders",
              "version": "1.0.0",
              "concepts": [
                { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "placedByUserId", "type": "reference", "required": true,
                    "reference": { "target": "identity::User", "onDelete": "restrict" } }
                ]}
              ]
            }
            """);

        PackSealednessAnalyzer.SealednessResult result = PackSealednessAnalyzer.analyze(unsealedPack);

        assertFalse(result.sealed());
        assertTrue(result.violations().stream().anyMatch(v ->
                        v.contains("Order") && v.contains("placedByUserId") && v.contains("identity::User")),
                "expected a violation naming Order/placedByUserId/identity::User, got: " + result.violations());
    }

    @Test
    void unboundRequiredCapability_isRefused_namingIt() {
        JsonNode unsealedPack = pack("""
            {
              "dslVersion": "1.0.0",
              "pack": "notifier",
              "version": "1.0.0",
              "requires": { "capabilities": ["emailSender"] },
              "concepts": [
                { "name": "Notification", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true }
                ]}
              ]
            }
            """);

        PackSealednessAnalyzer.SealednessResult result = PackSealednessAnalyzer.analyze(unsealedPack);

        assertFalse(result.sealed());
        assertTrue(result.violations().stream().anyMatch(v -> v.contains("emailSender")),
                "expected a violation naming emailSender, got: " + result.violations());
    }

    @Test
    void ownTransitiveDependency_isRefused_composedSealingNotYetSupported() {
        JsonNode unsealedPack = pack("""
            {
              "dslVersion": "1.0.0",
              "pack": "hospital",
              "version": "1.0.0",
              "packs": [ { "pack": "identity", "version": "^1.0" } ],
              "concepts": [
                { "name": "Patient", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true }
                ]}
              ]
            }
            """);

        PackSealednessAnalyzer.SealednessResult result = PackSealednessAnalyzer.analyze(unsealedPack);

        assertFalse(result.sealed());
        assertTrue(result.violations().stream().anyMatch(v -> v.contains("packs[]")),
                "expected a violation naming packs[], got: " + result.violations());
    }

    @Test
    void fragmentDeclaredConcept_isRefused_asUnverifiable() {
        JsonNode unsealedPack = pack("""
            {
              "dslVersion": "1.0.0",
              "pack": "fragmented",
              "version": "1.0.0",
              "concepts": [
                { "$ref": "concepts/widget.json" }
              ]
            }
            """);

        PackSealednessAnalyzer.SealednessResult result = PackSealednessAnalyzer.analyze(unsealedPack);

        assertFalse(result.sealed());
        assertTrue(result.violations().stream().anyMatch(v -> v.contains("fragment") || v.contains("$ref")),
                "expected a violation naming the fragment, got: " + result.violations());
    }

    @Test
    void emptyRequiresObjectWithNoCapabilities_staysSealed() {
        JsonNode sealedPack = pack("""
            {
              "dslVersion": "1.0.0",
              "pack": "trivial",
              "version": "1.0.0",
              "requires": { "roles": ["admin"] },
              "concepts": [
                { "name": "Thing", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true }
                ]}
              ]
            }
            """);

        PackSealednessAnalyzer.SealednessResult result = PackSealednessAnalyzer.analyze(sealedPack);

        assertTrue(result.sealed(), "a requires.roles-only pack should still be sealed (only "
                + "capabilities gate sealedness), violations: " + result.violations());
    }
}
