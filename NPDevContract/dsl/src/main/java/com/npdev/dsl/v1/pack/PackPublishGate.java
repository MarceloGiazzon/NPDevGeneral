package com.npdev.dsl.v1.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PK-4 Stage B: the publish gate. Wraps {@link PackDiffEngine} with the version-bump-size rule --
 * refuses a publish whose {@code version} bump is smaller than what the diff actually requires.
 *
 * <p><b>The rule, precisely</b> (this is the real design decision PK-4 asks to be stated clearly):
 * <ul>
 *   <li>A BREAKING change requires AT LEAST a major bump. Nothing smaller is acceptable, because a
 *       consumer pinned to {@code ^oldMajor} would silently pull in a break.</li>
 *   <li>An ADDITIVE change requires AT LEAST a minor bump (a major bump also satisfies it -- semver's
 *       usual "a bigger bump always covers a smaller requirement" rule). A patch-only bump is not
 *       enough, because a consumer pinned to {@code ~oldMajor.oldMinor} would silently miss the new
 *       capability while still being told nothing changed structurally.</li>
 *   <li>A PATCH-only change requires AT LEAST a patch bump (minor/major also satisfy it -- they are
 *       just not REQUIRED). "No bump at all" is refused too, even though nothing structural changed:
 *       the file's content differs, and publishing identical content under an identical version
 *       number is either a no-op that should not be attempted, or a sign the version field itself
 *       was forgotten -- either way, silently accepting it would mean a version number stops meaning
 *       "this exact content", which is the property every consumer's lockfile depends on.</li>
 *   <li>No diff at all (the two documents are identical outside {@code version}/{@code $schema}/
 *       {@code dslVersion}/{@code migrations}) requires no bump -- {@code PackVersionBump.NONE} is a
 *       satisfied requirement, so re-publishing byte-identical content with the version left alone
 *       is allowed (a genuine no-op, distinct from the PATCH case above where the CONTENT differs).</li>
 * </ul>
 */
public final class PackPublishGate {

    private PackPublishGate() {
    }

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:[-+].*)?$");

    public record Decision(
            boolean allowed,
            PackVersionBump requiredBump,
            PackVersionBump actualBump,
            Optional<PackChangeClassification> overallClassification,
            List<PackDiffFinding> findings,
            String message
    ) {
        /**
         * True for every {@link #allowed} decision except a BREAKING one (a major-bump publish of a
         * breaking change is allowed, but Stage C -- populating what actually needs to happen to
         * replay the break -- does not exist yet, so this engine must not pretend an empty chain
         * entry means "nothing to do" for that case). See {@code PackPublishGate}'s class doc and
         * PK-4's own scope note: "Do NOT attempt to design or populate the array's contents for
         * breaking changes -- that's explicitly out of scope."
         */
        public boolean shouldWriteEmptyMigrationEntry() {
            return allowed && overallClassification.map(c -> c != PackChangeClassification.BREAKING).orElse(true);
        }
    }

    public static Decision evaluate(JsonNode oldPack, JsonNode newPack) {
        PackDiffResult diffResult = PackDiffEngine.diff(oldPack, newPack);

        String oldVersion = textOrEmpty(oldPack, "version");
        String newVersion = textOrEmpty(newPack, "version");

        String chainViolation = checkChainImmutability(oldPack, newPack);
        if (chainViolation == null) {
            chainViolation = checkChainDiffConsistency(newPack, oldVersion, newVersion, diffResult);
        }
        if (chainViolation != null) {
            return new Decision(false, requiredBump(diffResult), PackVersionBump.NONE,
                    diffResult.worstClassification(), diffResult.findings(), chainViolation);
        }

        int[] oldParts = parseVersion(oldVersion, "old pack's version");
        int[] newParts = parseVersion(newVersion, "new pack's version");

        PackVersionBump required = requiredBump(diffResult);
        int cmp = compareParts(newParts, oldParts);
        boolean downgrade = cmp < 0;
        PackVersionBump actual = downgrade ? PackVersionBump.NONE : actualBump(oldParts, newParts);

        boolean allowed = !downgrade && actual.ordinal() >= required.ordinal();
        String message = buildMessage(allowed, downgrade, required, actual, oldVersion, newVersion, oldParts, diffResult);
        return new Decision(allowed, required, actual, diffResult.worstClassification(), diffResult.findings(), message);
    }

    /**
     * PK-4 Stage C "Breaks": "a published version's chain must be immutable -- pin it by digest, and
     * refuse a publish that alters a released version's migrations." Compares every hop key present
     * in the OLD pack's {@code migrations} against the new pack: missing entirely, or present with
     * different content, both refuse. A hop the old pack never had is unaffected -- that is exactly
     * what THIS publish is allowed to add.
     */
    private static String checkChainImmutability(JsonNode oldPack, JsonNode newPack) {
        JsonNode oldMigrations = oldPack.get("migrations");
        if (oldMigrations == null || !oldMigrations.isObject()) {
            return null;
        }
        JsonNode newMigrations = newPack.get("migrations");
        Iterator<String> keys = oldMigrations.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            JsonNode oldHop = oldMigrations.get(key);
            JsonNode newHop = newMigrations == null ? null : newMigrations.get(key);
            if (newHop == null) {
                return "Refusing publish: migrations['" + key + "'] was already published and is missing from "
                        + "this publish -- a released version's migration chain must never be removed.";
            }
            if (!newHop.equals(oldHop)) {
                return "Refusing publish: migrations['" + key + "'] was already published and is immutable, but "
                        + "this publish changes its content -- a released version's migration chain must never be "
                        + "altered after the fact.";
            }
        }
        return null;
    }

    /**
     * Cross-checks THIS publish's own new hop ({@code migrations["<oldVersion> -> <newVersion>"]},
     * if present and non-empty) against what {@link PackDiffEngine} actually observed between the
     * two documents -- the cheapest point to catch a wrong chain entry, before any consumer ever
     * replays it. Only checks that every declared op corresponds to a real finding (a fabricated or
     * mistyped op is refused); does not attempt the reverse (flagging an undeclared rename-shaped
     * diff), which would require re-implementing {@code ImpactReportText}'s own same-table/same-type
     * heuristic and risks diverging from it -- left for a future card if this cheaper half proves
     * insufficient in practice.
     */
    private static String checkChainDiffConsistency(
            JsonNode newPack, String oldVersion, String newVersion, PackDiffResult diffResult) {
        JsonNode migrations = newPack.get("migrations");
        if (migrations == null || !migrations.isObject()) {
            return null;
        }
        String hopKey = oldVersion + " -> " + newVersion;
        JsonNode hopNode = migrations.get(hopKey);
        if (hopNode == null || !hopNode.isArray() || hopNode.isEmpty()) {
            return null;
        }

        PackMigrationChain chain;
        try {
            ObjectNode wrapper = JsonNodeFactory.instance.objectNode();
            wrapper.set(hopKey, hopNode);
            chain = PackMigrationChain.parse(wrapper);
        } catch (IllegalArgumentException malformed) {
            return "Refusing publish: migrations['" + hopKey + "'] is malformed: " + malformed.getMessage();
        }

        Set<String> findingKeys = new HashSet<>();
        for (PackDiffFinding finding : diffResult.findings()) {
            findingKeys.add(finding.classification() + ":" + finding.path());
        }

        for (PackMigrationOp op : chain.hops().get(0).ops()) {
            String missing = missingDiffEvidenceFor(op, findingKeys);
            if (missing != null) {
                return "Refusing publish: migrations['" + hopKey + "'] declares an op with no matching change "
                        + "in the actual diff (expected to find: " + missing + ") -- the chain entry does not "
                        + "describe what this publish actually changed.";
            }
        }
        return null;
    }

    /** Null if {@code op} has real, matching diff findings; otherwise a description of the missing one.
     *  Field-level paths carry a {@code .fields.} segment -- {@code PackDiffEngine} reaches a
     *  concept's fields via a nested {@code diffCollection} over the concept's own {@code fields}
     *  key, so a field-level finding's path is {@code concepts.<Concept>.fields.<field>}, not
     *  {@code concepts.<Concept>.<field>}. */
    private static String missingDiffEvidenceFor(PackMigrationOp op, Set<String> findingKeys) {
        if (op instanceof PackMigrationOp.RenameField renameField) {
            String removedPath = "concepts." + renameField.concept() + ".fields." + renameField.from();
            String addedPath = "concepts." + renameField.concept() + ".fields." + renameField.to();
            if (!findingKeys.contains("BREAKING:" + removedPath)) {
                return "field '" + renameField.from() + "' removed on " + renameField.concept();
            }
            if (!findingKeys.contains("ADDITIVE:" + addedPath)) {
                return "new field '" + renameField.to() + "' on " + renameField.concept();
            }
            return null;
        }
        if (op instanceof PackMigrationOp.RenameConcept renameConcept) {
            String removedPath = "concepts." + renameConcept.from();
            String addedPath = "concepts." + renameConcept.to();
            if (!findingKeys.contains("BREAKING:" + removedPath)) {
                return "concept '" + renameConcept.from() + "' removed";
            }
            if (!findingKeys.contains("ADDITIVE:" + addedPath)) {
                return "new concept '" + renameConcept.to() + "'";
            }
            return null;
        }
        if (op instanceof PackMigrationOp.AddField addField) {
            String addedPath = "concepts." + addField.concept() + ".fields." + addField.field();
            return findingKeys.contains("ADDITIVE:" + addedPath)
                    ? null
                    : "new field '" + addField.field() + "' on " + addField.concept();
        }
        PackMigrationOp.DropField dropField = (PackMigrationOp.DropField) op;
        String removedPath = "concepts." + dropField.concept() + ".fields." + dropField.field();
        return findingKeys.contains("BREAKING:" + removedPath)
                ? null
                : "field '" + dropField.field() + "' removed on " + dropField.concept();
    }

    static PackVersionBump requiredBump(PackDiffResult diffResult) {
        return diffResult.worstClassification()
                .map(PackVersionBump::requiredFor)
                .orElse(PackVersionBump.NONE);
    }

    /**
     * PK-4 Stage B's "empty chain entry" auto-generation: for a non-BREAKING publish, returns a deep
     * copy of {@code newPack} with an empty array registered under {@code migrations} at key
     * {@code "<oldVersion> -> <newVersion>"} -- {@code {"migrations": {"1.0.0 -> 1.1.0": []}}}. A
     * LATER, different card (Stage C) defines what goes inside a non-empty array; this only proves
     * the schema field exists and the chain link is present for every non-breaking transition, ready
     * for that future work to populate. Never call this for a BREAKING decision -- guard with
     * {@link Decision#shouldWriteEmptyMigrationEntry()} first.
     */
    public static ObjectNode withEmptyMigrationChainEntry(JsonNode newPack, String oldVersion, String newVersion) {
        if (newPack == null || !newPack.isObject()) {
            throw new IllegalArgumentException("newPack must be a JSON object");
        }
        ObjectNode copy = ((ObjectNode) newPack).deepCopy();
        JsonNode existingMigrations = copy.get("migrations");
        ObjectNode migrations = (existingMigrations != null && existingMigrations.isObject())
                ? (ObjectNode) existingMigrations
                : copy.putObject("migrations");
        String key = oldVersion + " -> " + newVersion;
        if (!migrations.has(key)) {
            migrations.putArray(key);
        }
        return copy;
    }

    private static String buildMessage(
            boolean allowed,
            boolean downgrade,
            PackVersionBump required,
            PackVersionBump actual,
            String oldVersion,
            String newVersion,
            int[] oldParts,
            PackDiffResult diffResult
    ) {
        if (downgrade) {
            return "Refusing publish: new version (" + newVersion + ") is lower than old version (" + oldVersion
                    + ") -- a pack version must never decrease.";
        }
        if (allowed && diffResult.isEmpty()) {
            return "Publish allowed: " + oldVersion + " -> " + newVersion + " (" + actual
                    + " bump). No differences found between the two pack documents.";
        }
        String verdict = diffResult.worstClassification().map(Enum::toString).orElse("no changes");
        if (allowed) {
            return "Publish allowed: " + oldVersion + " -> " + newVersion + " (" + actual + " bump) is sufficient for "
                    + "the " + verdict + " change(s) found (required: at least a " + required + " bump).";
        }
        StringBuilder message = new StringBuilder();
        message.append("Refusing publish: ").append(oldVersion).append(" -> ").append(newVersion)
                .append(" is only a ").append(actual).append(" bump, but the diff engine found ")
                .append(verdict).append(" change(s), which require at least a ").append(required).append(" bump.");
        if (!diffResult.findings().isEmpty()) {
            message.append(" Changes:");
            for (PackDiffFinding finding : diffResult.findings()) {
                message.append("\n  - [").append(finding.classification()).append("] ").append(finding.message());
            }
        }
        message.append("\nRequired: bump at least the ").append(bumpComponentName(required))
                .append(" version component, e.g. ").append(suggestedVersion(oldParts, required)).append(".");
        return message.toString();
    }

    private static String bumpComponentName(PackVersionBump bump) {
        return switch (bump) {
            case MAJOR -> "major";
            case MINOR -> "minor";
            case PATCH, NONE -> "patch";
        };
    }

    private static String suggestedVersion(int[] oldParts, PackVersionBump bump) {
        return switch (bump) {
            case MAJOR -> (oldParts[0] + 1) + ".0.0";
            case MINOR -> oldParts[0] + "." + (oldParts[1] + 1) + ".0";
            case PATCH, NONE -> oldParts[0] + "." + oldParts[1] + "." + (oldParts[2] + 1);
        };
    }

    private static PackVersionBump actualBump(int[] oldParts, int[] newParts) {
        if (newParts[0] != oldParts[0]) {
            return PackVersionBump.MAJOR;
        }
        if (newParts[1] != oldParts[1]) {
            return PackVersionBump.MINOR;
        }
        if (newParts[2] != oldParts[2]) {
            return PackVersionBump.PATCH;
        }
        return PackVersionBump.NONE;
    }

    private static int compareParts(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(a[i], b[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static int[] parseVersion(String version, String label) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException(label + " is missing or blank -- cannot compare version bumps");
        }
        Matcher matcher = VERSION_PATTERN.matcher(version.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(label + " ('" + version
                    + "') is not a recognizable major[.minor[.patch]] version");
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
        int patch = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;
        return new int[]{major, minor, patch};
    }

    private static String textOrEmpty(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }
}
