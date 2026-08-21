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
 * non-pack concepts and zero unbound capabilities." A <em>composed</em> sealed pack (one that lists
 * its own {@code packs[]}) is sealed when every dependency in its transitive closure is itself
 * sealed, the closure's resolution adds every transitively-bundled concept to the reference universe
 * (so a reference to a dependency's concept is not "outbound"), and nothing else leaks out.
 *
 * <p><b>Deliberately narrower than "from PK-1's provenance"</b> -- the BT-2 card assumed {@code
 * origin}/provenance metadata (packId/version/digest recorded on every compiled member) was already
 * threaded through the four-place canonical chain when it was written. It is not: that is PK-1 steps
 * 5-8, tracked separately as {@code ledger/items/PACK-2.yml}, which stays OPEN. Rather than block on
 * that, this analyzer computes sealedness directly from the pack's OWN {@code pack.json} content --
 * before any app ever composes it -- which is both simpler and more correct for this purpose.
 *
 * <p><b>What is checked, precisely:</b>
 * <ol>
 *   <li>Transitive dependencies: a pack's own {@code packs[]} must all be sealed. That is only
 *       verifiable when the caller supplies a {@link PackDependencyResolver} that can turn each
 *       {@code packs[]} entry into its {@code pack.json}; {@link #analyze(JsonNode)} (no resolver)
 *       refuses any {@code packs[]} pack outright -- the historical leaf-pack-only behaviour, kept
 *       for backward compatibility -- while {@link #analyze(JsonNode, PackDependencyResolver)}
 *       recursively seals every transitive dependency and requires ALL of them to be sealed.</li>
 *   <li>{@code requires.capabilities[]} must be empty or absent -- the "zero unbound capabilities"
 *       half. A sealed jar is built with no app in the loop, so a required capability can never be
 *       bound at pack-build time.</li>
 *   <li>Every concept's every {@code reference}-typed field, plus the query/panel/flow concept
 *       references, must target a concept in the pack's own <em>closure</em>: its {@code concepts[]}
 *       plus the concepts bundled by its transitively-sealed dependencies. Any reference whose bare
 *       name is not in that closure is an outbound reference to a non-pack concept. The field-target
 *       spelling is resolved via {@link #referenceTarget(JsonNode)} (all THREE spellings
 *       {@code JsonModelParser} accepts).</li>
 * </ol>
 *
 * <p><b>Not checked (documented limitation, not silently ignored):</b> a concept declared via a
 * {@code localModelRef} ({@code {"$ref": "..."}}) fragment rather than inline is treated as
 * UNVERIFIABLE and fails sealedness with a named violation, because resolving it would add a
 * filesystem/model-root dependency this pure function deliberately avoids. Borrowing the same
 * reasoning, a {@code packs[]} pack is only verifiable when the caller supplies a
 * {@link PackDependencyResolver}; without one it refuses.
 */
public final class PackSealednessAnalyzer {

    private PackSealednessAnalyzer() {
    }

    /**
     * Turns a pack's {@code packs[]} dependency entry into that dependency's raw {@code pack.json},
     * so the analyzer can recursively verify the whole transitive closure is sealed.
     */
    @FunctionalInterface
    public interface PackDependencyResolver {
        JsonNode resolve(String packRef);
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

    /** Internal recursion result: sealedness + violations, plus the closure's available concept set
     *  so a parent composed pack can treat a bundled dependency's concepts as non-outbound. */
    private record Internal(boolean sealed, List<String> violations, Set<String> closureConcepts) {
        Internal {
            violations = List.copyOf(violations);
        }
    }

    /** Backward-compatible leaf-pack entry point: {@code packs[]} packs are refused (no resolver). */
    public static SealednessResult analyze(JsonNode packJson) {
        Internal internal = analyzeInternal(packJson, null, new LinkedHashSet<>());
        return new SealednessResult(internal.sealed(), internal.violations());
    }

    /** Full entry point: a {@code packs[]} pack is sealed iff every transitive dependency resolves
     *  and is itself sealed (recursively), with no outbound references or unbound capabilities. */
    public static SealednessResult analyze(JsonNode packJson, PackDependencyResolver resolver) {
        Internal internal = analyzeInternal(packJson, resolver, new LinkedHashSet<>());
        return new SealednessResult(internal.sealed(), internal.violations());
    }

    private static Internal analyzeInternal(JsonNode packJson, PackDependencyResolver resolver, Set<String> visited) {
        if (packJson == null || !packJson.isObject()) {
            return new Internal(false, List.of("pack document is missing or not a JSON object"), Set.of());
        }
        List<String> violations = new ArrayList<>();
        Set<String> closureConcepts = new LinkedHashSet<>();

        JsonNode packsNode = packJson.get("packs");
        if (packsNode != null && packsNode.isArray() && !packsNode.isEmpty()) {
            if (resolver == null) {
                violations.add("pack declares " + packsNode.size() + " of its own transitive dependencies (packs[]) -- "
                        + "composed sealed packs require a PackDependencyResolver so the whole closure can be "
                        + "verified sealed; none was supplied, so sealing is refused");
            } else {
                for (JsonNode dependency : packsNode) {
                    String ref = packRef(dependency);
                    if (ref == null) {
                        violations.add("a packs[] dependency entry names no pack/from/$ref, so it cannot be resolved "
                                + "for sealedness verification");
                        continue;
                    }
                    if (!visited.add(ref)) {
                        continue; // already analyzed -- cycle guard
                    }
                    JsonNode dependencyJson;
                    try {
                        dependencyJson = resolver.resolve(ref);
                    } catch (RuntimeException e) {
                        violations.add("transitive dependency '" + ref + "' could not be resolved: " + e.getMessage());
                        continue;
                    }
                    if (dependencyJson == null) {
                        violations.add("transitive dependency '" + ref + "' resolved to nothing (null) -- its sealedness "
                                + "cannot be verified");
                        continue;
                    }
                    Internal dependencyInternal = analyzeInternal(dependencyJson, resolver, visited);
                    closureConcepts.addAll(dependencyInternal.closureConcepts());
                    if (!dependencyInternal.sealed()) {
                        violations.add("transitive dependency '" + ref + "' is not sealed: " + dependencyInternal.violations());
                    }
                }
            }
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

        // The referencing universe: this pack's own concepts plus everything its (sealed or not)
        // dependencies bundle, so a reference to a bundled dependency concept is not outbound.
        Set<String> referenceUniverse = new LinkedHashSet<>(closureConcepts);
        referenceUniverse.addAll(conceptNames);

        Set<String> reportedOutbound = new LinkedHashSet<>();

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
                String rawTarget = referenceTarget(field);
                if (rawTarget == null) {
                    continue;
                }
                String bareTarget = bareName(rawTarget);
                if (!referenceUniverse.contains(bareTarget)) {
                    String fieldName = field.has("name") ? field.get("name").asText("?") : "?";
                    reportedOutbound.add(rawTarget);
                    violations.add("concept '" + conceptName + "' field '" + fieldName
                            + "' references '" + rawTarget + "', which is not declared in this pack's "
                            + "referencing universe (own concepts[] or a transitively-sealed dependency) "
                            + "-- an outbound reference to a non-pack concept, so this pack cannot be sealed");
                }
            }
        }

        checkNonFieldConceptReferences(packJson, referenceUniverse, reportedOutbound, violations);

        return new Internal(violations.isEmpty(), violations, referenceUniverse);
    }

    /** The {@code packs[]} entry's resolvable reference string: {@code pack}, else {@code from},
     *  else {@code $ref} -- mirroring the field spellings ModelSourceResolver accepts. */
    private static String packRef(JsonNode dependency) {
        if (dependency == null || !dependency.isObject()) {
            return null;
        }
        String pack = textOrNull(dependency.get("pack"));
        if (pack != null) {
            return pack;
        }
        String from = textOrNull(dependency.get("from"));
        if (from != null) {
            return from;
        }
        return textOrNull(dependency.get("$ref"));
    }

    private static void checkNonFieldConceptReferences(
            JsonNode packJson,
            Set<String> referenceUniverse,
            Set<String> reportedOutbound,
            List<String> violations
    ) {
        List<String> refs = new ArrayList<>();
        collectConceptRefs(refs, packJson.get("queries"), "queries");
        collectConceptRefs(refs, packJson.get("panels"), "panels");
        JsonNode flows = packJson.get("flows");
        if (flows != null && flows.isArray()) {
            for (int f = 0; f < flows.size(); f++) {
                JsonNode flow = flows.get(f);
                collectConceptRefs(refs, flow, "flows[" + f + "]");
                JsonNode steps = flow == null ? null : flow.get("steps");
                if (steps != null && steps.isArray()) {
                    for (int s = 0; s < steps.size(); s++) {
                        collectConceptRefs(refs, steps.get(s), "flows[" + f + "].steps[" + s + "]");
                    }
                }
            }
        }
        for (String entry : refs) {
            String ref = entry.substring(entry.indexOf('=') + 1);
            String bare = bareName(ref);
            if (!referenceUniverse.contains(bare) && !reportedOutbound.contains(ref)) {
                reportedOutbound.add(ref);
                violations.add(entry.replaceFirst("=", " refers to '") + "', which is not declared in this "
                        + "pack's referencing universe (own concepts[] or a transitively-sealed dependency) "
                        + "-- an outbound reference to a non-pack concept, so this pack cannot be sealed");
            }
        }
    }

    /** Collects concept-reference strings from a subtree under the clearly-concept-bearing keys:
     *  {@code dataSource.concept}, {@code concept}, and flow-step {@code scope} (only when it names a
     *  local-looking concept, i.e. not a {@code $}-prefixed runtime/variable reference). Records as
     *  {@code <context>=<ref>}; recurses into arrays and nested {@code dataSource} objects. */
    private static void collectConceptRefs(List<String> into, JsonNode node, String context) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectConceptRefs(into, node.get(i), context + "[" + i + "]");
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode dataSource = node.get("dataSource");
        if (dataSource != null && dataSource.isObject()) {
            String concept = textOrNull(dataSource.get("concept"));
            if (concept != null) {
                into.add(context + ".dataSource.concept=" + concept);
            }
            collectConceptRefs(into, dataSource, context + ".dataSource");
        }
        String concept = textOrNull(node.get("concept"));
        if (concept != null) {
            into.add(context + ".concept=" + concept);
        }
        String scope = textOrNull(node.get("scope"));
        if (scope != null && looksLikeConceptName(scope)) {
            into.add(context + ".scope=" + scope);
        }
    }

    /** A {@code scope} value that names a concept (a bare identifier), as opposed to a runtime
     *  {@code $}-prefixed variable, a dotted path, or a literal. */
    private static boolean looksLikeConceptName(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.indexOf('$') >= 0 || value.indexOf('.') >= 0 || value.indexOf(' ') >= 0) {
            return false;
        }
        return value.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    /**
     * Resolves a {@code reference}-typed field's target the SAME way {@code JsonModelParser}
     * (lines ~239-249) actually does at real parse time -- three legitimate, independently-authored
     * spellings, in the SAME priority order: (a) the bare-string shorthand {@code "ref": "Concept"},
     * checked first; (b) the object form {@code "reference": {"target": "Concept", ...}}; (c) the
     * bare-string form {@code "reference": "Concept"}. {@code ModelSourceResolver
     * .namespacePackFieldRefs} independently confirms all three are real, separately-rewritten
     * spellings during actual pack composition -- an analyzer that only recognizes (b) would
     * wrongly certify a pack using (a) or (c) for an outbound reference as sealed. Returns
     * {@code null} (not blank) when the field declares no resolvable target at all.
     */
    private static String referenceTarget(JsonNode field) {
        String bareRef = textOrNull(field.get("ref"));
        if (bareRef != null) {
            return bareRef;
        }
        JsonNode referenceNode = field.get("reference");
        if (referenceNode != null && referenceNode.isObject()) {
            String target = textOrNull(referenceNode.get("target"));
            if (target != null) {
                return target;
            }
        }
        return textOrNull(referenceNode);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String text = node.asText();
        return text.isBlank() ? null : text;
    }

    private static String bareName(String qualifiedOrBare) {
        int split = qualifiedOrBare.indexOf("::");
        return split < 0 ? qualifiedOrBare : qualifiedOrBare.substring(split + 2);
    }
}