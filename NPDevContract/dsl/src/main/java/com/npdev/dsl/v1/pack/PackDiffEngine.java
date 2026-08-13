package com.npdev.dsl.v1.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * PK-4 Stage A: a pure function that diffs two {@code pack.json} documents (see
 * {@code NPDevContract/schemas/pack.schema.json}) and classifies every difference it finds into
 * {@link PackChangeClassification#ADDITIVE}, {@link PackChangeClassification#BREAKING}, or
 * {@link PackChangeClassification#PATCH}.
 *
 * <p><b>Pure and self-contained.</b> This class touches no database, no filesystem, and no other
 * pack -- it reads only the two {@link JsonNode} trees handed to it. The CLI entry point
 * ({@code com.npdev.dsl.v1.cli.PackDiffMain}) is what reads files; everything below this line is a
 * plain in-memory tree comparison, which is what makes {@code PackPublishGate} (Stage B) and its
 * tests able to call this synchronously with hand-built fixtures.
 *
 * <p><b>What is compared.</b> {@code $schema}, {@code dslVersion}, {@code version} and {@code
 * migrations} are excluded from the diff itself -- {@code version} is the very thing Stage B
 * compares the diff's verdict against, and {@code migrations} is a field THIS engine's own caller
 * (the publish gate) writes into the new document, so diffing it would be circular. Every other
 * top-level key is compared generically: known scalar/object metadata keys ({@code description},
 * {@code category}, {@code author}, {@code metadata}, {@code forkedFrom}) are PATCH-only; {@code
 * pack} and {@code namespace} identity keys are BREAKING if changed (a rename of the pack itself,
 * or a change to its generated Java package); every other key -- {@code concepts}, {@code panels},
 * {@code queries}, {@code procedures}, and so on, including any FUTURE top-level array the schema
 * grows -- is treated as a named collection and diffed by identity (see {@link
 * #diffCollection(String, String, String, JsonNode, JsonNode, List)}).
 *
 * <p><b>The three buckets, concretely:</b>
 * <ul>
 *   <li>ADDITIVE: a wholly new concept/panel/query/procedure/etc. (present in the new pack, absent
 *       from the old one), a newly-added object key (e.g. a new nullable field on an existing
 *       concept), a field's {@code required} flipped from true to false (a constraint loosened), or
 *       a field's/index's {@code unique} flipped from true to false.</li>
 *   <li>BREAKING: anything removed (a concept/panel/query/procedure/field/key present in the old
 *       pack and absent from the new one), a field's {@code type} changed at all (this engine does
 *       not attempt to distinguish a safe widening from an unsafe narrowing -- see the note on
 *       {@link #diffKeyedValue}), a field's {@code required} flipped from false to true, a field's/
 *       index's {@code unique} flipped from false to true, and -- per PK-4's explicit rule -- any
 *       rename. There is no rename DETECTION here at all: a rename is simply what a remove (of the
 *       old name) plus an add (of the new name) looks like to this engine, which classifies the
 *       removal BREAKING and the addition ADDITIVE as two independent findings. The pack-level
 *       aggregate is the worst of the two, i.e. BREAKING, which is exactly PK-4's rule ("every
 *       rename must be classified BREAKING unconditionally, since there's no real migration-chain
 *       mechanism yet") -- achieved by NOT building a rename-guessing mechanism, not by building
 *       one that always answers BREAKING.</li>
 *   <li>PATCH: {@code description} added/changed/removed anywhere, or any of the pack-level
 *       metadata keys above.</li>
 * </ul>
 */
public final class PackDiffEngine {

    private PackDiffEngine() {
    }

    /** Top-level keys this engine never diffs (see the class doc for why each is excluded). */
    private static final Set<String> IGNORED_TOP_LEVEL_KEYS =
            Set.of("$schema", "dslVersion", "version", "migrations");

    /** Top-level scalar/object metadata keys: any change here is PATCH-only. */
    private static final Set<String> PATCH_ONLY_TOP_LEVEL_KEYS =
            Set.of("description", "category", "author", "metadata", "forkedFrom");

    /** Top-level identity keys: any change here is BREAKING (renames the pack, or its codegen package). */
    private static final Set<String> BREAKING_TOP_LEVEL_SCALAR_KEYS =
            Set.of("pack", "namespace");

    /** Any key at any nesting level named this is metadata-only, never structural. */
    private static final String DESCRIPTION_KEY = "description";

    /** Candidate identity keys tried, in order, to key-match items within an array section. */
    private static final List<String> IDENTITY_KEY_CANDIDATES = List.of("name", "key", "id");

    public static PackDiffResult diff(JsonNode oldPack, JsonNode newPack) {
        if (oldPack == null || !oldPack.isObject()) {
            throw new IllegalArgumentException("oldPack must be a JSON object");
        }
        if (newPack == null || !newPack.isObject()) {
            throw new IllegalArgumentException("newPack must be a JSON object");
        }

        List<PackDiffFinding> findings = new ArrayList<>();
        for (String key : unionOfFieldNames(oldPack, newPack)) {
            if (IGNORED_TOP_LEVEL_KEYS.contains(key)) {
                continue;
            }
            JsonNode oldVal = oldPack.path(key);
            JsonNode newVal = newPack.path(key);
            if (jsonEquals(oldVal, newVal)) {
                continue;
            }

            if (PATCH_ONLY_TOP_LEVEL_KEYS.contains(key)) {
                findings.add(new PackDiffFinding("pack", "pack." + key, PackChangeClassification.PATCH,
                        "pack." + key + " changed (metadata-only)"));
            } else if (BREAKING_TOP_LEVEL_SCALAR_KEYS.contains(key)) {
                findings.add(new PackDiffFinding("pack", "pack." + key, PackChangeClassification.BREAKING,
                        "pack." + key + " changed from " + display(oldVal) + " to " + display(newVal)));
            } else if (newVal.isArray() || oldVal.isArray()) {
                diffCollection(key, key, key, oldVal, newVal, findings);
            } else if (newVal.isObject() || oldVal.isObject()) {
                diffObject(key, key, asObjectOrEmpty(oldVal), asObjectOrEmpty(newVal), findings);
            } else {
                // An unrecognized top-level scalar key changed -- no rule proves this is safe, so the
                // conservative default (matching every other "we can't tell" branch in this engine)
                // is BREAKING rather than silently letting it through as additive.
                findings.add(new PackDiffFinding("pack", "pack." + key, PackChangeClassification.BREAKING,
                        "pack." + key + " changed from " + display(oldVal) + " to " + display(newVal)));
            }
        }
        return new PackDiffResult(findings);
    }

    /**
     * Diffs one array-valued section (top-level, e.g. {@code concepts}, or nested, e.g. a concept's
     * own {@code fields}/{@code indexes}) by matching items on an identity key.
     *
     * <p>Every item in both arrays is tried against {@link #IDENTITY_KEY_CANDIDATES} in order; the
     * first candidate present (as a non-blank string) on ALL items of BOTH arrays wins for the whole
     * section. If no candidate key works for every item (e.g. the array holds plain strings, as
     * {@code imports[]} does, or heterogeneous objects with no shared identity key), this falls back
     * to {@link #diffOpaqueEntries} -- an order-insensitive set diff over the entries' own JSON text,
     * which still separates "something was added" from "something was removed" without needing a
     * name to hang the finding on.
     *
     * @param section the TOP-LEVEL section this collection lives under (e.g. {@code "concepts"}),
     *                unchanged across recursion -- propagated onto every {@link PackDiffFinding} so
     *                findings about a nested {@code fields[]} array still group under {@code
     *                "concepts"}, matching {@link PackDiffFinding#section()}'s own contract.
     * @param labelKey the LOCAL array key this call is diffing right now (e.g. {@code "fields"} when
     *                 called for a concept's own fields array, vs. {@code "concepts"} at the top
     *                 level) -- used only to pick the right singular noun ("field" vs. "concept") for
     *                 human-readable messages, via {@link #singularOf(String)}.
     */
    private static void diffCollection(
            String section, String labelKey, String path, JsonNode oldArr, JsonNode newArr, List<PackDiffFinding> out) {
        List<JsonNode> oldItems = elementsOf(oldArr);
        List<JsonNode> newItems = elementsOf(newArr);

        String identityKey = resolveSharedIdentityKey(oldItems, newItems);
        if (identityKey == null) {
            diffOpaqueEntries(section, labelKey, path, oldItems, newItems, out);
            return;
        }

        Map<String, JsonNode> oldByIdentity = indexByIdentity(oldItems, identityKey);
        Map<String, JsonNode> newByIdentity = indexByIdentity(newItems, identityKey);
        String singular = singularOf(labelKey);

        for (String identity : newByIdentity.keySet()) {
            if (!oldByIdentity.containsKey(identity)) {
                out.add(new PackDiffFinding(section, path + "." + identity, PackChangeClassification.ADDITIVE,
                        "new " + singular + " '" + identity + "'"));
            }
        }
        for (String identity : oldByIdentity.keySet()) {
            if (!newByIdentity.containsKey(identity)) {
                out.add(new PackDiffFinding(section, path + "." + identity, PackChangeClassification.BREAKING,
                        singular + " '" + identity + "' removed"));
            }
        }
        for (Map.Entry<String, JsonNode> entry : oldByIdentity.entrySet()) {
            JsonNode newItem = newByIdentity.get(entry.getKey());
            if (newItem == null) {
                continue; // already reported as removed above
            }
            JsonNode oldItem = entry.getValue();
            if (!jsonEquals(oldItem, newItem)) {
                diffObject(section, path + "." + entry.getKey(), asObjectOrEmpty(oldItem), asObjectOrEmpty(newItem), out);
            }
        }
    }

    /**
     * Order-insensitive fallback for an array with no usable identity key (plain-string arrays like
     * {@code imports[]}, or object arrays with no shared {@code name}/{@code key}/{@code id}).
     * Compares entries by their exact canonical JSON text: entries only in the new array are
     * ADDITIVE, entries only in the old array are BREAKING, and a pure reordering (same entries,
     * different order) produces no finding at all -- it is invisible to any consumer.
     */
    private static void diffOpaqueEntries(
            String section, String labelKey, String path, List<JsonNode> oldItems, List<JsonNode> newItems,
            List<PackDiffFinding> out) {
        Set<String> oldTexts = new LinkedHashSet<>();
        for (JsonNode item : oldItems) {
            oldTexts.add(item.toString());
        }
        Set<String> newTexts = new LinkedHashSet<>();
        for (JsonNode item : newItems) {
            newTexts.add(item.toString());
        }
        Set<String> added = new TreeSet<>(newTexts);
        added.removeAll(oldTexts);
        Set<String> removed = new TreeSet<>(oldTexts);
        removed.removeAll(newTexts);

        if (!added.isEmpty()) {
            out.add(new PackDiffFinding(section, path, PackChangeClassification.ADDITIVE,
                    labelKey + " gained " + added.size() + " entr" + (added.size() == 1 ? "y" : "ies")
                            + " with no identity key to name individually"));
        }
        if (!removed.isEmpty()) {
            out.add(new PackDiffFinding(section, path, PackChangeClassification.BREAKING,
                    labelKey + " lost " + removed.size() + " entr" + (removed.size() == 1 ? "y" : "ies")
                            + " with no identity key to name individually"));
        }
    }

    /**
     * Generic key-by-key diff of one object (a single concept, panel, field, nested {@code
     * reference}/{@code access} block, etc.). Three keys carry dedicated semantics ({@link
     * #DESCRIPTION_KEY}, {@code type}, {@code required}, {@code unique} -- see {@link
     * #diffKeyedValue}); every other key falls through to structural add/remove/recurse.
     */
    private static void diffObject(String section, String path, ObjectNode oldObj, ObjectNode newObj, List<PackDiffFinding> out) {
        for (String key : unionOfFieldNames(oldObj, newObj)) {
            JsonNode oldVal = oldObj.path(key);
            JsonNode newVal = newObj.path(key);
            String childPath = path + "." + key;
            diffKeyedValue(section, childPath, key, oldVal, newVal, out);
        }
    }

    private static void diffKeyedValue(
            String section, String path, String key, JsonNode oldVal, JsonNode newVal, List<PackDiffFinding> out) {
        boolean oldAbsent = isAbsent(oldVal);
        boolean newAbsent = isAbsent(newVal);
        if (oldAbsent && newAbsent) {
            return;
        }
        if (!oldAbsent && !newAbsent && jsonEquals(oldVal, newVal)) {
            return;
        }

        if (DESCRIPTION_KEY.equals(key)) {
            out.add(new PackDiffFinding(section, path, PackChangeClassification.PATCH, path + " description changed"));
            return;
        }

        // Field type: this engine deliberately does not attempt to distinguish a safe widening
        // (e.g. int -> long) from an unsafe narrowing (e.g. string -> int) -- that classification
        // needs real knowledge of the storage layer's own widening rules (see
        // com.npdev.dsl.v1.schemaevolution.TypeChangeMatrix, which exists for exactly that purpose
        // one layer down, at the DB-column level) and PK-4 Stage A is explicitly scoped to a pure,
        // no-database comparison. ANY type change is therefore classified BREAKING, the conservative
        // default that can never misclassify an actually-unsafe retype as safe.
        if ("type".equals(key)) {
            if (oldAbsent) {
                out.add(new PackDiffFinding(section, path, PackChangeClassification.ADDITIVE, path + " gained a type"));
            } else if (newAbsent) {
                out.add(new PackDiffFinding(section, path, PackChangeClassification.BREAKING, path + " lost its type"));
            } else {
                out.add(new PackDiffFinding(section, path, PackChangeClassification.BREAKING,
                        path + " type changed from " + display(oldVal) + " to " + display(newVal)));
            }
            return;
        }

        // required/unique are boolean CONSTRAINTS: tightening (false/absent -> true) can reject data
        // an existing consumer used to be able to write, so it is BREAKING; loosening (true -> false)
        // can only make a previously-rejected write succeed, so it is ADDITIVE. Only handled when
        // both sides parse as plain booleans -- `required` also has a conditional-list array form
        // (model.schema.json field.required oneOf) this engine does not attempt to interpret, so a
        // change involving that shape falls through to the generic structural rule below instead.
        if (("required".equals(key) || "unique".equals(key))) {
            Boolean oldBool = effectiveBoolean(oldVal);
            Boolean newBool = effectiveBoolean(newVal);
            if (oldBool != null && newBool != null) {
                if (!oldBool && newBool) {
                    out.add(new PackDiffFinding(section, path, PackChangeClassification.BREAKING,
                            path + " " + key + " changed from false to true (constraint tightened)"));
                } else if (oldBool && !newBool) {
                    out.add(new PackDiffFinding(section, path, PackChangeClassification.ADDITIVE,
                            path + " " + key + " changed from true to false (constraint loosened)"));
                }
                return;
            }
            // fall through: at least one side is not a plain boolean
        }

        if (oldAbsent) {
            out.add(new PackDiffFinding(section, path, PackChangeClassification.ADDITIVE, path + " gained " + key));
            return;
        }
        if (newAbsent) {
            out.add(new PackDiffFinding(section, path, PackChangeClassification.BREAKING, path + " lost " + key));
            return;
        }
        if (oldVal.isObject() && newVal.isObject()) {
            diffObject(section, path, (ObjectNode) oldVal, (ObjectNode) newVal, out);
        } else if (oldVal.isArray() && newVal.isArray()) {
            diffCollection(section, key, path, oldVal, newVal, out);
        } else {
            out.add(new PackDiffFinding(section, path, PackChangeClassification.BREAKING,
                    path + " changed from " + display(oldVal) + " to " + display(newVal)));
        }
    }

    // ---- small structural helpers -------------------------------------------------------------

    private static Set<String> unionOfFieldNames(JsonNode a, JsonNode b) {
        Set<String> keys = new LinkedHashSet<>();
        a.fieldNames().forEachRemaining(keys::add);
        b.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static List<JsonNode> elementsOf(JsonNode arrayNode) {
        List<JsonNode> items = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            arrayNode.elements().forEachRemaining(items::add);
        }
        return items;
    }

    /**
     * Finds the single identity key (tried in {@link #IDENTITY_KEY_CANDIDATES} order) that is
     * present as a non-blank string on every item of both lists, or {@code null} if no candidate
     * works for all of them (including the trivial case where any item is not an object at all).
     */
    private static String resolveSharedIdentityKey(List<JsonNode> oldItems, List<JsonNode> newItems) {
        for (String candidate : IDENTITY_KEY_CANDIDATES) {
            if (allHaveIdentity(oldItems, candidate) && allHaveIdentity(newItems, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean allHaveIdentity(List<JsonNode> items, String key) {
        for (JsonNode item : items) {
            if (!item.isObject()) {
                return false;
            }
            JsonNode value = item.path(key);
            if (!value.isTextual() || value.asText().isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, JsonNode> indexByIdentity(List<JsonNode> items, String identityKey) {
        Map<String, JsonNode> byIdentity = new LinkedHashMap<>();
        for (JsonNode item : items) {
            byIdentity.put(item.path(identityKey).asText(), item);
        }
        return byIdentity;
    }

    private static ObjectNode asObjectOrEmpty(JsonNode node) {
        if (node != null && node.isObject()) {
            return (ObjectNode) node;
        }
        return JsonNodeFactory.instance.objectNode();
    }

    private static boolean isAbsent(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    /** {@code null} unless the node is exactly a JSON boolean (an absent/null node reads as false). */
    private static Boolean effectiveBoolean(JsonNode node) {
        if (isAbsent(node)) {
            return Boolean.FALSE;
        }
        return node.isBoolean() ? node.asBoolean() : null;
    }

    private static boolean jsonEquals(JsonNode a, JsonNode b) {
        boolean aAbsent = isAbsent(a);
        boolean bAbsent = isAbsent(b);
        if (aAbsent || bAbsent) {
            return aAbsent && bAbsent;
        }
        return a.equals(b);
    }

    private static String display(JsonNode node) {
        if (isAbsent(node)) {
            return "(absent)";
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static String singularOf(String section) {
        return switch (section) {
            case "concepts" -> "concept";
            case "domainTypes" -> "domain type";
            case "capabilities" -> "capability";
            case "customCapabilities" -> "custom capability";
            case "bindings" -> "binding";
            case "events" -> "event";
            case "flows" -> "flow";
            case "orchestrationRules" -> "orchestration rule";
            case "orchestrations" -> "orchestration";
            case "queries" -> "query";
            case "ruleProfiles" -> "rule profile";
            case "procedures" -> "procedure";
            case "conversions" -> "conversion";
            case "panels" -> "panel";
            case "roles" -> "role";
            case "propertyScopes" -> "property scope";
            case "properties" -> "property";
            case "fields" -> "field";
            case "indexes" -> "index";
            case "fragments" -> "fragment";
            case "imports" -> "import";
            default -> section; // future/unknown section name -- still a usable message
        };
    }
}
