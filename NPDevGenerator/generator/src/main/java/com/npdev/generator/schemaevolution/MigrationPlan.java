package com.npdev.generator.schemaevolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * LNCH-1 Phase 6 (task 6.1). The generator's preview of what a future app boot's schema-lifecycle
 * executor would do to an already-deployed app's database, computed by {@link MigrationPlanEmitter}.
 * Pure data -- no I/O in the record itself; {@link #toJson()}/{@link #write(Path, MigrationPlan)}
 * serialize per {@code NPDevContract/schemas/migration-plan.schema.json}, following
 * {@code com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson}'s toJson()/write() pattern.
 *
 * <p><b>freshInstall vs no-change, the distinction a consumer must not confuse (per the plan's own
 * explicit instruction):</b> {@link #freshInstall()} is true only when NO previous compiled model
 * was supplied at all (first-ever generation) -- {@link #fromFingerprint()} is {@code null} and
 * {@link #items()} is empty. A SEPARATE, also-valid state is "no changes since the last build":
 * {@code freshInstall == false}, {@link #fromFingerprint()} equals {@link #toFingerprint()}, and
 * {@link #items()} is (also) empty. Both states produce an empty item list and no ack token, but
 * only the first means "there was no previous model to diff against."
 */
public record MigrationPlan(
        boolean freshInstall,
        String fromFingerprint,
        String toFingerprint,
        List<PlanItem> items,
        String destructiveAckToken,
        List<String> warnings
) {

    private static final String MIGRATION_PLAN_VERSION = "1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public MigrationPlan {
        items = items == null ? List.of() : List.copyOf(items);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** Back-compat 5-arg constructor (no warnings) for the many existing call sites/tests that
     * predate LNCH-1 remediation R6's stale-marker warnings. */
    public MigrationPlan(boolean freshInstall, String fromFingerprint, String toFingerprint,
            List<PlanItem> items, String destructiveAckToken) {
        this(freshInstall, fromFingerprint, toFingerprint, items, destructiveAckToken, List.of());
    }

    /** Every destructive item's stable string, in list order -- the exact input
     * {@code DestructiveAckToken#compute} was given to produce {@link #destructiveAckToken()}. */
    public List<String> destructiveItemStableStrings() {
        List<String> out = new ArrayList<>();
        for (PlanItem item : items) {
            if (item.destructive() && item.stableString() != null) {
                out.add(item.stableString());
            }
        }
        return out;
    }

    public String toJson() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("migrationPlanVersion", MIGRATION_PLAN_VERSION);
        root.put("freshInstall", freshInstall);
        root.put("fromFingerprint", fromFingerprint);
        root.put("toFingerprint", toFingerprint);
        root.put("destructiveAckToken", destructiveAckToken);

        ArrayNode warningsNode = root.putArray("warnings");
        for (String warning : warnings) {
            warningsNode.add(warning);
        }

        ArrayNode itemsNode = root.putArray("items");
        for (PlanItem item : items) {
            ObjectNode itemNode = itemsNode.addObject();
            itemNode.put("kind", item.kind().name());
            itemNode.put("table", item.table());
            itemNode.put("column", item.column());
            itemNode.put("fromType", item.fromType());
            itemNode.put("toType", item.toType());
            itemNode.put("renamedFrom", item.renamedFrom());
            if (item.constraintColumns() == null) {
                itemNode.putNull("constraintColumns");
            } else {
                ArrayNode columns = itemNode.putArray("constraintColumns");
                for (String column : item.constraintColumns()) {
                    columns.add(column);
                }
            }
            itemNode.put("destructive", item.destructive());
            itemNode.put("sqlPreview", item.sqlPreview());
            itemNode.put("description", item.description());
            itemNode.put("stableString", item.stableString());
        }

        try {
            return OBJECT_MAPPER.writeValueAsString(root) + "\n";
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to serialize migration plan as JSON", exception);
        }
    }

    public static void write(Path outFile, MigrationPlan plan) throws IOException {
        Objects.requireNonNull(plan, "plan");
        Path parent = outFile.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(outFile, plan.toJson(), StandardCharsets.UTF_8);
    }
}
