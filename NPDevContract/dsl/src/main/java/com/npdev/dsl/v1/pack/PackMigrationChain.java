package com.npdev.dsl.v1.pack;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * PK-4 Stage C: a pack's own accumulated {@code migrations} object, parsed into a list of hops.
 * Since there is no registry yet (PK-3/PK-5: local-files-only), a pack's single current
 * {@code pack.json} file is the only place its chain history can live -- {@code npdev pack publish}
 * appends one hop per version bump, never editing or removing an already-published one (see
 * {@code PackPublishGate}'s immutability check), so the raw JSON object accumulates the pack's whole
 * history over its lifetime.
 *
 * <p>Deliberately does NOT key hops by their start version internally -- a JSON object's keys are
 * unique, but two DIFFERENT keys ({@code "1.0.0 -> 1.4.0"} and {@code "1.0.0 -> 2.0.0"}) can both
 * start at the same version (a branching/malformed chain). Collapsing to a {@code Map<PackVersion,
 * HopEntry>} at parse time would silently keep only one and hide the branch; {@link
 * #hopsStartingAt(PackVersion)} preserves all of them so {@link PackMigrationComposer} can detect
 * and refuse a branch explicitly instead.
 */
public final class PackMigrationChain {

    private static final String HOP_SEPARATOR = " -> ";

    public record HopEntry(PackVersion from, PackVersion to, List<PackMigrationOp> ops) {
        public HopEntry {
            ops = List.copyOf(ops);
        }
    }

    private final List<HopEntry> hops;

    private PackMigrationChain(List<HopEntry> hops) {
        this.hops = List.copyOf(hops);
    }

    public static PackMigrationChain empty() {
        return new PackMigrationChain(List.of());
    }

    /**
     * Parses a pack's raw {@code migrations} node (may be {@code null}/missing -- treated as empty,
     * matching a pack that has never bumped past its first published version).
     */
    public static PackMigrationChain parse(JsonNode migrationsNode) {
        if (migrationsNode == null || migrationsNode.isMissingNode() || migrationsNode.isNull()) {
            return empty();
        }
        if (!migrationsNode.isObject()) {
            throw new IllegalArgumentException("migrations must be a JSON object, got: " + migrationsNode.getNodeType());
        }
        List<HopEntry> parsed = new ArrayList<>();
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = migrationsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            parsed.add(parseHop(entry.getKey(), entry.getValue()));
        }
        return new PackMigrationChain(parsed);
    }

    private static HopEntry parseHop(String key, JsonNode opsNode) {
        int separatorIndex = key.indexOf(HOP_SEPARATOR);
        if (separatorIndex < 0) {
            throw new IllegalArgumentException(
                    "migrations key '" + key + "' is not of the form '<oldVersion> -> <newVersion>'");
        }
        PackVersion from;
        PackVersion to;
        try {
            from = PackVersion.parse(key.substring(0, separatorIndex));
            to = PackVersion.parse(key.substring(separatorIndex + HOP_SEPARATOR.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("migrations key '" + key + "': " + e.getMessage(), e);
        }
        if (to.compareTo(from) <= 0) {
            throw new IllegalArgumentException(
                    "migrations key '" + key + "': target version must be strictly greater than the start version");
        }
        if (opsNode == null || !opsNode.isArray()) {
            throw new IllegalArgumentException("migrations['" + key + "'] must be an array");
        }
        List<PackMigrationOp> ops = new ArrayList<>();
        for (JsonNode opNode : opsNode) {
            ops.add(parseOp(key, opNode));
        }
        return new HopEntry(from, to, ops);
    }

    private static PackMigrationOp parseOp(String hopKey, JsonNode opNode) {
        if (opNode == null || !opNode.isObject()) {
            throw new IllegalArgumentException("migrations['" + hopKey + "']: every op must be a JSON object");
        }
        String op = textOrBlank(opNode, "op");
        return switch (op) {
            case "renameField" -> new PackMigrationOp.RenameField(
                    requiredText(hopKey, opNode, "concept"),
                    requiredText(hopKey, opNode, "from"),
                    requiredText(hopKey, opNode, "to"));
            case "renameConcept" -> new PackMigrationOp.RenameConcept(
                    requiredText(hopKey, opNode, "from"),
                    requiredText(hopKey, opNode, "to"));
            case "addField" -> new PackMigrationOp.AddField(
                    requiredText(hopKey, opNode, "concept"),
                    requiredText(hopKey, opNode, "field"));
            case "dropField" -> new PackMigrationOp.DropField(
                    requiredText(hopKey, opNode, "concept"),
                    requiredText(hopKey, opNode, "field"));
            default -> throw new IllegalArgumentException(
                    "migrations['" + hopKey + "']: unknown op '" + op
                            + "' (expected one of renameField, renameConcept, addField, dropField)");
        };
    }

    private static String requiredText(String hopKey, JsonNode opNode, String field) {
        String value = textOrBlank(opNode, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "migrations['" + hopKey + "']: op is missing required field '" + field + "'");
        }
        return value;
    }

    private static String textOrBlank(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    public List<HopEntry> hops() {
        return hops;
    }

    /** Every hop whose declared start version is exactly {@code version} -- more than one means a
     *  branching chain, which {@link PackMigrationComposer} refuses rather than picking arbitrarily. */
    public List<HopEntry> hopsStartingAt(PackVersion version) {
        List<HopEntry> matches = new ArrayList<>();
        for (HopEntry hop : hops) {
            if (hop.from().equals(version)) {
                matches.add(hop);
            }
        }
        return Collections.unmodifiableList(matches);
    }
}
