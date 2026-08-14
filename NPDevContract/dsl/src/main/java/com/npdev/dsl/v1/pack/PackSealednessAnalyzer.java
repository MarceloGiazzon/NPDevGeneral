package com.npdev.dsl.v1.pack;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * BT-2 (PACK-ROADMAP.md, Track B): computes whether a pack is <b>sealed</b> -- eligible to be
 * precompiled into a jar that a consuming app links instead of generating and compiling the pack's
 * own concepts.
 *
 * <p><b>Definition (the card's own words):</b> "A pack is sealed iff zero outbound references to
 * non-pack concepts and zero unbound capabilities." This class is the mechanical implementation.
 *
 * <p><b>Deliberately narrower than "from PK-1's provenance"</b> -- the BT-2 card assumed {@code
 * origin}/provenance metadata (packId/version/digest recorded on every compiled member) was already
 * threaded through the four-place canonical chain when it was written. It is not: that is PK-1 steps
 * 5-8, tracked separately as {@code ledger/items/PACK-2.yml}, which stays OPEN. Rather than block on
 * that (a HIGH-severity, separately-scoped gap), this analyzer computes sealedness directly from the
 * pack's OWN {@code pack.json} content -- before any app ever composes it -- which is both simpler
 * and more correct for this purpose: sealedness is a property of the pack in isolation, not of any
 * particular app's composed model, so it should never have needed compiled-model provenance at all.
 *
 * <p><b>What is checked, precisely:</b>
 * <ol>
 *   <li>The pack must declare no transitive dependencies of its own ({@code packs[]} empty or
 *       absent). A pack that depends on other packs could in principle be sealed too (bundling its
 *       whole transitive closure into one jar, or requiring its dependencies to themselves be sealed
 *       jars already on the classpath) -- but that composition question is genuinely new design work
 *       this slice does not attempt. Restricting sealing to leaf packs (no {@code packs[]}) keeps the
 *       mechanism correct for the packs it does handle rather than accepting a half-considered answer
 *       for the harder case. See BT-2's ledger item for this explicit deferral.</li>
 *   <li>{@code requires.capabilities[]} must be empty or absent. A pack that requires a capability
 *       from the composing app cannot be compiled once and shared -- the capability binding only
 *       exists once a real app supplies it (see {@code ModelSourceResolver.checkPackRequirements}),
 *       and a sealed jar is built with no app in the loop at all. This is the "zero unbound
 *       capabilities" half of the card's definition.</li>
 *   <li>Every concept's every {@code reference}-typed field must target another concept declared in
 *       this SAME pack's own {@code concepts[]} (by bare, unqualified name -- pack.json concepts are
 *       always authored with bare names; a {@code ::}-qualified target, possible only via this pack's
 *       own {@code packs[]}, is itself already excluded by rule 1 above). This is the "zero outbound
 *       references to non-pack concepts" half.</li>
 * </ol>
 *
 * <p><b>Not checked (documented limitation, not silently ignored):</b> a concept declared via a
 * {@code localModelRef} ({@code {"$ref": "..."}}) fragment rather than inline cannot be inspected by
 * this analyzer without resolving the fragment first ({@code ModelSourceResolver} handles that
 * resolution during real composition, but this analyzer deliberately runs on the pack's raw,
 * unresolved JSON so it has no filesystem/model-root dependency and stays a pure function like its
 * {@link PackDiffEngine}/{@link PackPublishGate} siblings). A fragment-based concept is therefore
 * treated as UNVERIFIABLE and fails sealedness with a named violation, rather than being silently
 * assumed safe -- the identity/workspace built-in packs this slice targets declare every concept
 * inline, so this limitation does not block them.
 *
 * <p>References other than a concept field's {@code reference.target} (a query join, a panel data
 * source, a flow step referencing a concept by name, ...) are not walked by this first slice --
 * the FK/reference-field case is both the dominant shape in every existing pack and the one that
 * actually determines whether the entity/repository layer this card precompiles is self-contained.
 * Extending the walk to query/flow/panel concept references is mechanical (same pattern, same
 * pack-local-name-set check) and left as follow-up, not attempted here to keep this slice's own
 * claim precise.
 */
public final class PackSealednessAnalyzer {

    private PackSealednessAnalyzer() {
    }

    public record SealednessResult(boolean sealed, List<String> violations) {
        public SealednessResult {
            violations = List.copyOf(violations);
        }

        public static SealednessResult allSealed() {
            return new SealednessResult(true, List.of());
        }

        public static SealednessResult unsealed(List<String> violations) {
            return new SealednessResult(false, violations);
        }
    }

    public static SealednessResult analyze(JsonNode packJson) {
        if (packJson == null || !packJson.isObject()) {
            return SealednessResult.unsealed(List.of("pack document is missing or not a JSON object"));
        }
        List<String> violations = new ArrayList<>();

        JsonNode packsNode = packJson.get("packs");
        if (packsNode != null && packsNode.isArray() && !packsNode.isEmpty()) {
            violations.add("pack declares " + packsNode.size() + " of its own transitive dependencies "
                    + "(packs[]) -- composed sealed packs (a sealed pack depending on another pack) are "
                    + "not yet supported; only a leaf pack (no packs[] of its own) can be sealed today");
        }

        JsonNode requiresNode = packJson.get("requires");
        if (requiresNode != null && requiresNode.isObject()) {
            JsonNode capabilitiesNode = requiresNode.get("capabilities");
            if (capabilitiesNode != null && capabilitiesNode.isArray() && !capabilitiesNode.isEmpty()) {
                List<String> unbound = new ArrayList<>();
                for (JsonNode capability : capabilitiesNode) {
                    if (capability.isTextual()) {
                        unbound.add(capability.asText());
                    }
                }
                violations.add("pack requires " + unbound.size() + " capabilit"
                        + (unbound.size() == 1 ? "y" : "ies") + " from the composing app ("
                        + String.join(", ", unbound) + ") -- a sealed jar is built with no app in the "
                        + "loop, so a required capability can never be bound at pack-build time");
            }
        }

        Set<String> conceptNames = new LinkedHashSet<>();
        JsonNode conceptsNode = packJson.get("concepts");
        List<JsonNode> inlineConcepts = new ArrayList<>();
        if (conceptsNode != null && conceptsNode.isArray()) {
            for (JsonNode concept : conceptsNode) {
                if (concept != null && concept.isObject() && concept.has("name") && concept.get("name").isTextual()) {
                    conceptNames.add(concept.get("name").asText());
                    inlineConcepts.add(concept);
                } else if (concept != null && concept.isObject() && concept.has("$ref")) {
                    violations.add("concept declared via a fragment ($ref: " + concept.get("$ref").asText("?")
                            + ") -- this analyzer only verifies inline concepts; a fragment-declared "
                            + "concept's own outbound references cannot be checked without resolving it "
                            + "first, so sealing is refused rather than silently assumed safe");
                }
            }
        }

        for (JsonNode concept : inlineConcepts) {
            String conceptName = concept.get("name").asText();
            JsonNode fieldsNode = concept.get("fields");
            if (fieldsNode == null || !fieldsNode.isArray()) {
                continue;
            }
            for (JsonNode field : fieldsNode) {
                if (field == null || !field.isObject()) {
                    continue;
                }
                JsonNode typeNode = field.get("type");
                if (typeNode == null || !"reference".equals(typeNode.asText(""))) {
                    continue;
                }
                JsonNode referenceNode = field.get("reference");
                if (referenceNode == null || !referenceNode.isObject()) {
                    continue;
                }
                JsonNode targetNode = referenceNode.get("target");
                if (targetNode == null || !targetNode.isTextual()) {
                    continue;
                }
                String rawTarget = targetNode.asText();
                String bareTarget = bareName(rawTarget);
                if (!conceptNames.contains(bareTarget)) {
                    String fieldName = field.has("name") ? field.get("name").asText("?") : "?";
                    violations.add("concept '" + conceptName + "' field '" + fieldName
                            + "' references '" + rawTarget + "', which is not declared in this pack's own "
                            + "concepts[] -- an outbound reference to a non-pack concept, so this pack "
                            + "cannot be sealed");
                }
            }
        }

        return violations.isEmpty() ? SealednessResult.allSealed() : SealednessResult.unsealed(violations);
    }

    private static String bareName(String qualifiedOrBare) {
        int split = qualifiedOrBare.indexOf("::");
        return split < 0 ? qualifiedOrBare : qualifiedOrBare.substring(split + 2);
    }
}
