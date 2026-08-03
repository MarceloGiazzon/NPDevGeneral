package com.npdev.generator.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.dsl.v1.parser.ModelSourceResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S5 (element-granularity authoring merge, {@code __OutsideRepo\s5\S5_SPEC.md} I1): computes, for
 * two raw {@code model.json} documents, the set of top-level array elements one document touched
 * relative to the other, addressed as {@code (arrayKey, name)} -- the same identity scheme
 * {@link com.npdev.dsl.v1.parser.ModelSourceResolver}'s pack/context composition already relies on.
 *
 * <p><b>H1 -- conservative by construction.</b> Any change this differ cannot confidently attribute
 * to one named element -- a change to a top-level key outside {@code MODEL_ARRAY_KEYS} (including
 * {@code version} itself: this differ has no opinion that version is special, see
 * {@code AuthoringMergeGate#withoutVersion} for why the merge gate strips it before calling here), a
 * reordering of an order-significant array, or an array element with no usable {@code name} (e.g.
 * {@code bindings[]}, identified by {@code capability}+{@code adapter} instead) -- collapses the
 * WHOLE document pair to a single {@code wholeDocument} conflict rather than guessing. This class
 * never decides whether a merge is safe; it only reports what changed. {@link AuthoringMergeGate}
 * is the one that acts on the result.
 */
public final class ElementDiffer {

    private ElementDiffer() {
    }

    public enum ChangeKind { ADDED, REMOVED, MODIFIED }

    public record ElementKey(String arrayKey, String name) {
        @Override
        public String toString() {
            return arrayKey + "[" + name + "]";
        }
    }

    public record ElementChange(ElementKey key, ChangeKind kind) {
    }

    public record DiffResult(List<ElementChange> changes, boolean wholeDocument, List<String> wholeDocumentReasons) {
        public DiffResult {
            changes = List.copyOf(changes);
            wholeDocumentReasons = List.copyOf(wholeDocumentReasons);
        }

        /** The (arrayKey, name) identities this document pair touched. Meaningless (and MUST NOT be
         *  used for a disjointness decision) when {@link #wholeDocument()} is true -- callers check
         *  that flag first. */
        public Set<ElementKey> touchedKeys() {
            Set<ElementKey> keys = new LinkedHashSet<>();
            for (ElementChange change : changes) {
                keys.add(change.key());
            }
            return keys;
        }
    }

    public static DiffResult diff(JsonNode base, JsonNode other) {
        List<ElementChange> changes = new ArrayList<>();
        List<String> wholeDocumentReasons = new ArrayList<>();

        Set<String> allTopLevelKeys = new LinkedHashSet<>();
        if (base != null && base.isObject()) {
            base.fieldNames().forEachRemaining(allTopLevelKeys::add);
        }
        if (other != null && other.isObject()) {
            other.fieldNames().forEachRemaining(allTopLevelKeys::add);
        }

        Set<String> arrayKeys = ModelSourceResolver.modelArrayKeys();
        for (String key : allTopLevelKeys) {
            JsonNode baseValue = base == null ? null : base.get(key);
            JsonNode otherValue = other == null ? null : other.get(key);
            if (arrayKeys.contains(key)) {
                diffArray(key, baseValue, otherValue, changes, wholeDocumentReasons);
            } else if (!jsonEquals(baseValue, otherValue)) {
                wholeDocumentReasons.add("root field '" + key + "' changed -- not an element-addressable "
                        + "array (H1: a top-level scalar collapses the whole document, never a guess)");
            }
        }

        return new DiffResult(changes, !wholeDocumentReasons.isEmpty(), wholeDocumentReasons);
    }

    private static void diffArray(
            String arrayKey, JsonNode baseArray, JsonNode otherArray,
            List<ElementChange> changes, List<String> wholeDocumentReasons
    ) {
        Map<String, JsonNode> baseByName = new LinkedHashMap<>();
        Map<String, JsonNode> otherByName = new LinkedHashMap<>();
        if (!indexByName(baseArray, baseByName) || !indexByName(otherArray, otherByName)) {
            if (jsonEquals(baseArray, otherArray)) {
                return; // unnamed elements (e.g. bindings[]), but this side never touched them
            }
            wholeDocumentReasons.add("array '" + arrayKey + "' has an element with no usable 'name' "
                    + "(or a duplicate name) -- cannot attribute its changes to a specific element "
                    + "(H1: never guess)");
            return;
        }
        for (Map.Entry<String, JsonNode> entry : otherByName.entrySet()) {
            String name = entry.getKey();
            JsonNode baseElement = baseByName.get(name);
            if (baseElement == null) {
                changes.add(new ElementChange(new ElementKey(arrayKey, name), ChangeKind.ADDED));
            } else if (!jsonEquals(baseElement, entry.getValue())) {
                changes.add(new ElementChange(new ElementKey(arrayKey, name), ChangeKind.MODIFIED));
            }
        }
        for (String name : baseByName.keySet()) {
            if (!otherByName.containsKey(name)) {
                changes.add(new ElementChange(new ElementKey(arrayKey, name), ChangeKind.REMOVED));
            }
        }
    }

    /** @return false if the array (when present) contains any element the differ cannot address by
     *  name -- a missing/blank/non-string {@code name}, a non-object element, or a name duplicated
     *  within the SAME document -- in which case the caller treats the whole array as unattributable. */
    private static boolean indexByName(JsonNode array, Map<String, JsonNode> out) {
        if (array == null || array.isNull() || array.isMissingNode()) {
            return true; // absent == empty; nothing to name, fully attributable
        }
        if (!array.isArray()) {
            return false;
        }
        for (JsonNode element : array) {
            if (element == null || !element.isObject()) {
                return false;
            }
            JsonNode nameNode = element.get("name");
            if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isBlank()) {
                return false;
            }
            String name = nameNode.asText();
            if (out.containsKey(name)) {
                return false;
            }
            out.put(name, element);
        }
        return true;
    }

    private static boolean jsonEquals(JsonNode a, JsonNode b) {
        boolean aAbsent = a == null || a.isNull() || a.isMissingNode();
        boolean bAbsent = b == null || b.isNull() || b.isMissingNode();
        if (aAbsent || bAbsent) {
            return aAbsent == bAbsent;
        }
        return a.equals(b);
    }
}
