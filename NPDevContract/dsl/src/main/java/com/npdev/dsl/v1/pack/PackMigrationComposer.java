package com.npdev.dsl.v1.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PK-4 Stage C: composes a pack's accumulated {@link PackMigrationChain} across however many hops
 * separate {@code from} from {@code to} -- the mechanism behind "identity@1.0 straight to @3.0,
 * skipping @2.0, still replays @2.0's rename". Pure, no filesystem, no database.
 *
 * <p><b>Why collapsing to a single (oldest-in-range -> final) pair per field/concept is required,
 * not a simplification</b>: {@code DesiredColumn}/{@code DesiredTable} in the RuntimeHost schema
 * engine only ever carry one nullable {@code renamedFrom}/{@code renamedFromColumn} string -- the
 * "marker lifecycle" contract ({@code docs/SCHEMA_EVOLUTION.md}) already requires a single hop's
 * marker to name the immediately-previous name, never the original, on a hand-authored chain. A
 * composed multi-hop marker must therefore name whichever name the field actually has on a live
 * database that was never regenerated past {@code from} -- which, for a straight skip, is exactly
 * the OLDEST name in the traversed range. This is why {@code addField}/{@code dropField} ops are
 * never consulted here (see {@link PackMigrationOp}'s own class doc) and why a field renamed then
 * later dropped within the same traversed range drops out of the result entirely: the schema
 * engine's ordinary destructive-drop path already handles that case correctly with no marker at all.
 */
public final class PackMigrationComposer {

    /** DoS guard mirroring {@code PackDependencyGraphWalker.MAX_RESOLVED_PACKS} -- a real pack's
     *  history is a straight line by construction of {@code PackPublishGate}, so a legitimate chain
     *  never comes close to this. */
    static final int MAX_HOPS = 200;

    private PackMigrationComposer() {
    }

    public record ComposedRenames(
            Map<String, String> conceptRenames,
            Map<String, Map<String, String>> fieldRenamesByConcept
    ) {
        public ComposedRenames {
            conceptRenames = Map.copyOf(conceptRenames);
            Map<String, Map<String, String>> copy = new LinkedHashMap<>();
            fieldRenamesByConcept.forEach((concept, fields) -> copy.put(concept, Map.copyOf(fields)));
            fieldRenamesByConcept = Map.copyOf(copy);
        }

        public static ComposedRenames empty() {
            return new ComposedRenames(Map.of(), Map.of());
        }

        public boolean isEmpty() {
            if (!conceptRenames.isEmpty()) {
                return false;
            }
            return fieldRenamesByConcept.values().stream().allMatch(Map::isEmpty);
        }
    }

    public sealed interface Result permits Composed, Refused {
    }

    public record Composed(ComposedRenames renames) implements Result {
    }

    public record Refused(String message) implements Result {
    }

    public static Result compose(String packId, PackMigrationChain chain, PackVersion from, PackVersion to) {
        if (from.equals(to)) {
            return new Composed(ComposedRenames.empty());
        }

        List<PackMigrationOp> concatenatedOps = new ArrayList<>();
        PackVersion current = from;
        List<String> traversedHops = new ArrayList<>();
        for (int step = 0; step < MAX_HOPS; step++) {
            List<PackMigrationChain.HopEntry> candidates = chain.hopsStartingAt(current);
            if (candidates.isEmpty()) {
                return new Refused("Pack '" + packId + "': no migration chain entry starts at version " + current
                        + " (need to reach " + to + " from " + from + "; traversed so far: "
                        + (traversedHops.isEmpty() ? "none" : String.join(", ", traversedHops)) + ")");
            }
            if (candidates.size() > 1) {
                return new Refused("Pack '" + packId + "': more than one migration chain hop starts at version "
                        + current + " (a pack's version history must be a straight line) -- found "
                        + candidates.size() + " candidates targeting "
                        + candidates.stream().map(h -> h.to().toString()).reduce((a, b) -> a + ", " + b).orElse(""));
            }
            PackMigrationChain.HopEntry hop = candidates.get(0);
            if (hop.to().compareTo(to) > 0) {
                return new Refused("Pack '" + packId + "': migration chain hop " + hop.from() + " -> " + hop.to()
                        + " overshoots the requested target " + to + " -- the declared chain does not cleanly bridge "
                        + from + " to " + to);
            }
            concatenatedOps.addAll(hop.ops());
            traversedHops.add(hop.from() + " -> " + hop.to());
            current = hop.to();
            if (current.equals(to)) {
                return new Composed(collapse(concatenatedOps));
            }
        }
        return new Refused("Pack '" + packId + "': migration chain composition from " + from + " to " + to
                + " did not terminate within " + MAX_HOPS + " hops -- possible malformed chain");
    }

    private static ComposedRenames collapse(List<PackMigrationOp> ops) {
        Map<String, String> conceptOriginalByCurrent = new LinkedHashMap<>();
        Map<String, Map<String, String>> fieldOriginalByConceptThenCurrent = new LinkedHashMap<>();

        for (PackMigrationOp op : ops) {
            if (op instanceof PackMigrationOp.RenameConcept renameConcept) {
                String from = renameConcept.from();
                String to = renameConcept.to();
                String original = conceptOriginalByCurrent.containsKey(from)
                        ? conceptOriginalByCurrent.remove(from)
                        : from;
                conceptOriginalByCurrent.put(to, original);
                Map<String, String> fieldMap = fieldOriginalByConceptThenCurrent.remove(from);
                if (fieldMap != null) {
                    fieldOriginalByConceptThenCurrent.put(to, fieldMap);
                }
            } else if (op instanceof PackMigrationOp.RenameField renameField) {
                Map<String, String> fieldMap = fieldOriginalByConceptThenCurrent
                        .computeIfAbsent(renameField.concept(), key -> new LinkedHashMap<>());
                String from = renameField.from();
                String to = renameField.to();
                String original = fieldMap.containsKey(from) ? fieldMap.remove(from) : from;
                fieldMap.put(to, original);
            } else if (op instanceof PackMigrationOp.DropField dropField) {
                Map<String, String> fieldMap = fieldOriginalByConceptThenCurrent.get(dropField.concept());
                if (fieldMap != null) {
                    fieldMap.remove(dropField.field());
                }
            }
            // AddField: deliberately not consulted -- see PackMigrationOp's class doc.
        }

        return new ComposedRenames(conceptOriginalByCurrent, fieldOriginalByConceptThenCurrent);
    }
}
