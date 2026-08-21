package com.npdev.dsl.v1.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

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

    /** Positive-case sibling to the two false-negative regressions below: the bare-string spellings
     *  must not become spuriously OVER-strict either -- a self-contained reference using {@code "ref"}
     *  or bare-string {@code "reference"} must still analyze sealed, same as the object form already
     *  proven above. */
    @Test
    void packWithSelfContainedReferences_usingBareRefAndBareStringReferenceSpellings_isSealed() {
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
                  { "name": "widgetId", "type": "reference", "required": true, "ref": "Widget" }
                ]},
                { "name": "WidgetTag", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "widgetId", "type": "reference", "required": true, "reference": "Widget" }
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

    /** Regression (adversarial review before merge, PR #82): {@code "ref": "Concept"} is the
     *  bare-string shorthand {@code JsonModelParser} checks FIRST (readText(f, "ref")) -- an earlier
     *  version of the analyzer only recognized the object form ({@code "reference": {"target": ...}})
     *  and reported this shape's outbound reference as sealed=true, wrongly certifying a pack with a
     *  real dependency on a non-pack concept. */
    @Test
    void outboundReferenceViaBareRefShorthand_isRefused_namingTheConceptAndTarget() {
        JsonNode unsealedPack = pack("""
            {
              "dslVersion": "1.0.0",
              "pack": "orders",
              "version": "1.0.0",
              "concepts": [
                { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "placedByUserId", "type": "reference", "required": true,
                    "ref": "identity::User" }
                ]}
              ]
            }
            """);

        PackSealednessAnalyzer.SealednessResult result = PackSealednessAnalyzer.analyze(unsealedPack);

        assertFalse(result.sealed(), "bare 'ref' shorthand must be recognized, not silently skipped");
        assertTrue(result.violations().stream().anyMatch(v ->
                        v.contains("Order") && v.contains("placedByUserId") && v.contains("identity::User")),
                "expected a violation naming Order/placedByUserId/identity::User, got: " + result.violations());
    }

    /** Regression (adversarial review before merge, PR #82): {@code "reference": "Concept"} (bare
     *  string, distinct from the object form {@code "reference": {"target": ...}}) is
     *  {@code JsonModelParser}'s third accepted spelling (readText(f, "reference")). Same false-
     *  negative risk as the {@code ref}-shorthand case above -- covered separately since the two
     *  spellings are read by different code paths in {@link PackSealednessAnalyzer#referenceTarget}. */
    @Test
    void outboundReferenceViaBareStringReferenceField_isRefused_namingTheConceptAndTarget() {
        JsonNode unsealedPack = pack("""
            {
              "dslVersion": "1.0.0",
              "pack": "orders",
              "version": "1.0.0",
              "concepts": [
                { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "placedByUserId", "type": "reference", "required": true,
                    "reference": "identity::User" }
                ]}
              ]
            }
            """);

        PackSealednessAnalyzer.SealednessResult result = PackSealednessAnalyzer.analyze(unsealedPack);

        assertFalse(result.sealed(), "bare-string 'reference' must be recognized, not silently skipped");
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

    // ---- BUILD-2 composed sealed packs (Part A): a packs[] pack is sealed iff the whole ---- //
    // ---- transitive closure resolves and is itself sealed (resolver overload only).       ---- //

    private static PackSealednessAnalyzer.PackDependencyResolver resolver(Map<String, JsonNode> byRef) {
        return byRef::get;
    }

    @Test
    void composedPackWithAllSealedDependencies_isSealed() {
        JsonNode base = pack("""
            {
              "dslVersion": "1.0.0", "pack": "identity", "version": "1.0.0",
              "concepts": [
                { "name": "User", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """);
        JsonNode hospital = pack("""
            {
              "dslVersion": "1.0.0", "pack": "hospital", "version": "1.0.0",
              "packs": [ { "pack": "identity", "version": "1.0.0" } ],
              "concepts": [
                { "name": "Patient", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "userId", "type": "reference", "required": false,
                    "reference": { "target": "User", "onDelete": "restrict" } } ] }
              ]
            }
            """);
        Map<String, JsonNode> byRef = new HashMap<>();
        byRef.put("identity", base);

        PackSealednessAnalyzer.SealednessResult result =
                PackSealednessAnalyzer.analyze(hospital, resolver(byRef));

        assertTrue(result.sealed(), "a packs[] pack whose only dependency is sealed should seal, "
                + "violations: " + result.violations());
    }

    @Test
    void composedPackWithUnsealedDependency_isRefused_namingTheDependencyAndItsViolation() {
        JsonNode identity = pack("""
            {
              "dslVersion": "1.0.0", "pack": "identity", "version": "1.0.0",
              "requires": { "capabilities": ["emailSender"] },
              "concepts": [
                { "name": "User", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """);
        JsonNode hospital = pack("""
            {
              "dslVersion": "1.0.0", "pack": "hospital", "version": "1.0.0",
              "packs": [ { "pack": "identity", "version": "1.0.0" } ],
              "concepts": [
                { "name": "Patient", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """);
        Map<String, JsonNode> byRef = new HashMap<>();
        byRef.put("identity", identity);

        PackSealednessAnalyzer.SealednessResult result =
                PackSealednessAnalyzer.analyze(hospital, resolver(byRef));

        assertFalse(result.sealed());
        assertTrue(result.violations().stream().anyMatch(v ->
                        v.contains("identity") && v.contains("not sealed") && v.contains("emailSender")),
                "expected a violation naming the unsealed dependency identity (emailSender), got: "
                        + result.violations());
    }

    @Test
    void composedPackTransitiveUnsealedDependency_isRefused() {
        JsonNode identity = pack("""
            {
              "dslVersion": "1.0.0", "pack": "identity", "version": "1.0.0",
              "concepts": [
                { "name": "User", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """);
        // billing is sealed on its own BUT depends transitively on unsealed "audit".
        JsonNode billing = pack("""
            {
              "dslVersion": "1.0.0", "pack": "billing", "version": "1.0.0",
              "packs": [ { "pack": "audit", "version": "1.0.0" } ],
              "concepts": [
                { "name": "Account", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """);
        JsonNode audit = pack("""
            {
              "dslVersion": "1.0.0", "pack": "audit", "version": "1.0.0",
              "requires": { "capabilities": ["tracer"] },
              "concepts": [ ]
            }
            """);
        JsonNode hospital = pack("""
            {
              "dslVersion": "1.0.0", "pack": "hospital", "version": "1.0.0",
              "packs": [
                { "pack": "identity", "version": "1.0.0" },
                { "pack": "billing", "version": "1.0.0" }
              ],
              "concepts": [
                { "name": "Patient", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """);
        Map<String, JsonNode> byRef = new HashMap<>();
        byRef.put("identity", identity);
        byRef.put("billing", billing);
        byRef.put("audit", audit);

        PackSealednessAnalyzer.SealednessResult result =
                PackSealednessAnalyzer.analyze(hospital, resolver(byRef));

        assertFalse(result.sealed());
        assertTrue(result.violations().stream().anyMatch(v ->
                        v.contains("audit") && v.contains("not sealed") && v.contains("tracer")),
                "expected a violation naming the TRANSITIVE unsealed dependency audit (tracer), got: "
                        + result.violations());
    }

    @Test
    void composedPackWithUnresolvableDependency_isRefused() {
        JsonNode hospital = pack("""
            {
              "dslVersion": "1.0.0", "pack": "hospital", "version": "1.0.0",
              "packs": [ { "from": "git+https://example.invalid/packs/identity.git@v1" } ],
              "concepts": [
                { "name": "Patient", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """);

        PackSealednessAnalyzer.SealednessResult result =
                PackSealednessAnalyzer.analyze(hospital, ref -> null);

        assertFalse(result.sealed());
        assertTrue(result.violations().stream().anyMatch(v -> v.contains("resolved to nothing")),
                "expected a violation naming the unresolvable dependency, got: " + result.violations());
    }

    @Test
    void leafPackWithoutPacks_analyzedWithResolver_stillSeals() {
        JsonNode base = pack("""
            {
              "dslVersion": "1.0.0", "pack": "identity", "version": "1.0.0",
              "concepts": [
                { "name": "User", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """);

        PackSealednessAnalyzer.SealednessResult result =
                PackSealednessAnalyzer.analyze(base, ref -> null);

        assertTrue(result.sealed(), "a leaf pack analyzed with a resolver must still seal, violations: "
                + result.violations());
    }

    // ---- BUILD-2 Part C: the extended outbound-reference walk beyond reference fields ---- //

    @Test
    void queryDataSourceOutboundConceptReference_isRefused() {
        JsonNode packJson = pack("""
            {
              "dslVersion": "1.0.0", "pack": "crm", "version": "1.0.0",
              "concepts": [
                { "name": "Contact", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ],
              "queries": [
                { "name": "RichContacts", "dataSource": { "concept": "identity::User" }, "where": "true" }
              ]
            }
            """);

        PackSealednessAnalyzer.SealednessResult result = PackSealednessAnalyzer.analyze(packJson);

        assertFalse(result.sealed(), "a query dataSource.concept naming an out-of-pack concept must refuse "
                + "sealing, got: " + result.violations());
        assertTrue(result.violations().stream().anyMatch(v ->
                        v.contains("dataSource.concept") && v.contains("identity::User")),
                "expected a violation naming the query's outbound dataSource.concept, got: "
                        + result.violations());
    }

    @Test
    void panelDataSourceOutboundConceptReference_isRefused() {
        JsonNode packJson = pack("""
            {
              "dslVersion": "1.0.0", "pack": "crm", "version": "1.0.0",
              "concepts": [
                { "name": "Contact", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ],
              "panels": [
                { "name": "AuditFeed", "dataSource": { "concept": "external::AuditEvent" }, "layout": { "type": "list" } }
              ]
            }
            """);

        PackSealednessAnalyzer.SealednessResult result = PackSealednessAnalyzer.analyze(packJson);

        assertFalse(result.sealed());
        assertTrue(result.violations().stream().anyMatch(v ->
                        v.contains("dataSource.concept") && v.contains("external::AuditEvent")),
                "expected a violation naming the panel's outbound dataSource.concept, got: "
                        + result.violations());
    }

    @Test
    void queryDataSourceSelfContainedConceptReference_staysSealed() {
        JsonNode packJson = pack("""
            {
              "dslVersion": "1.0.0", "pack": "crm", "version": "1.0.0",
              "concepts": [
                { "name": "Contact", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ],
              "queries": [
                { "name": "AllContacts", "dataSource": { "concept": "Contact" }, "where": "true" }
              ]
            }
            """);

        PackSealednessAnalyzer.SealednessResult result = PackSealednessAnalyzer.analyze(packJson);

        assertTrue(result.sealed(), "a query dataSource.concept naming a LOCAL concept must stay sealed, "
                + "violations: " + result.violations());
    }
}
