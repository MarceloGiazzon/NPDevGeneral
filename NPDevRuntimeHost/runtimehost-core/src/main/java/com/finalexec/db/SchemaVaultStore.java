package com.finalexec.db;

import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.DesiredTable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * S17a (NPDEV_MEGA_ROADMAP.md): the Artifact Vault's read surface over the existing per-fingerprint
 * schema snapshots ({@code SchemaSnapshotStore}'s {@code npdev_schema_snapshot} table).
 *
 * <p>The WRITE side already exists -- {@link SchemaLifecycleExecutor#afterMigrate} stores one full
 * desired-schema snapshot per fingerprint the database has actually reached. What S17a adds is the
 * restore path: list the vaulted fingerprints, and derive a restore plan between the LIVE fingerprint
 * and any vaulted one. This class ONLY reads; "restore" is deliberately a plan a human reviews and
 * an operator executes through the SAME migration acknowledgment machinery every other destructive
 * step already uses -- the vault never writes directly.
 */
public final class SchemaVaultStore {

    private SchemaVaultStore() {
    }

    /** One vaulted snapshot's metadata -- fingerprint, when it was recorded, how many tables it
     *  describes. Never the snapshot body itself (the UI lists what COULD be restored, it does not
     *  pull megabytes of desired-schema JSON). */
    public record VaultEntry(String fingerprint, long recordedAtUtc, int tableCount) {
    }

    /** Every fingerprint this database has actually reached, newest first. */
    public static List<VaultEntry> list(DataSource dataSource) {
        List<VaultEntry> entries = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT fingerprint, snapshot_json, recorded_at_utc FROM npdev_schema_snapshot "
                             + "ORDER BY recorded_at_utc DESC")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String fingerprint = resultSet.getString(1);
                    String json = resultSet.getString(2);
                    int tableCount = 0;
                    try {
                        tableCount = SchemaSnapshotStore.fromJson(json).tables().size();
                    } catch (Exception ignored) {
                        // a corrupt snapshot must not hide every other entry -- count 0 and move on
                    }
                    entries.add(new VaultEntry(fingerprint, resultSet.getLong(3), tableCount));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return entries;
    }

    /**
     * Derives a restore plan from the LIVE fingerprint to a vaulted target: which tables exist in
     * the target but not live ("must be recreated"), which exist live but not in the target ("would
     * be dropped -- DESTRUCTIVE"), and which are common. The plan is descriptive, not executable: it
     * names the shape of the difference so an operator can route the actual rebuild through the
     * schema-lifecycle machinery (or decline).
     *
     * <p>The live fingerprint is read from the app's schema-lifecycle manifest. When no manifest is
     * in scope (no app running -- like a unit test), the plan degrades honestly: it reports what the
     * target contains with no destructive claim it cannot see.
     */
    public static java.util.Map<String, Object> restorePlan(DataSource dataSource, String targetFingerprint) {
        return restorePlan(dataSource, currentFingerprint(), targetFingerprint);
    }

    /**
     * The comparison half, with the live fingerprint supplied explicitly so a caller (or a test)
     * does not depend on the filesystem manifest. Everything below this method is pure set
     * arithmetic over the two snapshots.
     */
    public static java.util.Map<String, Object> restorePlan(DataSource dataSource, String currentFingerprint, String targetFingerprint) {
        java.util.Map<String, Object> plan = new java.util.LinkedHashMap<>();
        plan.put("currentFingerprint", currentFingerprint);
        plan.put("targetFingerprint", targetFingerprint);

        DesiredSchema target = SchemaSnapshotStore.readSnapshot(dataSource, targetFingerprint).orElse(null);
        if (target == null) {
            plan.put("found", false);
            plan.put("reason", "no snapshot recorded for target fingerprint " + targetFingerprint);
            return plan;
        }
        plan.put("found", true);

        VaultEntry targetMeta = list(dataSource).stream()
                .filter(e -> e.fingerprint().equals(targetFingerprint))
                .findFirst().orElse(null);
        plan.put("targetRecordedAtUtc", targetMeta == null ? null : targetMeta.recordedAtUtc());

        DesiredSchema current = (currentFingerprint == null)
                ? null : SchemaSnapshotStore.readSnapshot(dataSource, currentFingerprint).orElse(null);

        Set<String> targetTables = tableNames(target);
        Set<String> currentTables = current == null ? Set.of() : tableNames(current);

        Set<String> onlyInTarget = new LinkedHashSet<>(targetTables);
        onlyInTarget.removeAll(currentTables);
        Set<String> onlyInCurrent = new LinkedHashSet<>(currentTables);
        onlyInCurrent.removeAll(targetTables);
        Set<String> common = new LinkedHashSet<>(targetTables);
        common.retainAll(currentTables);

        plan.put("targetTableCount", targetTables.size());
        plan.put("tablesOnlyInTarget", new ArrayList<>(onlyInTarget));
        plan.put("tablesOnlyInCurrent", new ArrayList<>(onlyInCurrent));
        plan.put("commonTables", new ArrayList<>(common));
        // Dropping tables that exist live but not in the target is the destructive half -- the
        // visual-weight rule S16 applies to migration plans applies here too. With no live
        // fingerprint this is deliberately false: the plan must never claim a destructive drop it
        // cannot see.
        plan.put("destructive", !onlyInCurrent.isEmpty());
        return plan;
    }

    private static String currentFingerprint() {
        try {
            SchemaLifecycleExecutor.SchemaManifest manifest = SchemaLifecycleExecutor.loadManifest();
            return manifest == null ? null : manifest.schemaFingerprint();
        } catch (Exception exception) {
            return null;
        }
    }

    private static Set<String> tableNames(DesiredSchema schema) {
        Set<String> names = new LinkedHashSet<>();
        for (DesiredTable table : schema.tables().values()) {
            if (table.name() != null && !table.name().isBlank()) {
                names.add(table.name());
            }
        }
        return names;
    }
}