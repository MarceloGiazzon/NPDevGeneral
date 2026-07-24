package com.finalexec.db;

import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffEngine;
import com.finalexec.db.schemastate.SafetyClass;

import javax.sql.DataSource;

/**
 * Read-only shadow parity probe (schema-engine rebuild, Phase 3 — "the crux"). Runs the new
 * desired-vs-current {@link SchemaDiffEngine} ALONGSIDE the live {@code SchemaLifecycleExecutor} and
 * reports where the shadow's verdict diverges from what the live engine actually did. The live engine
 * is the authority until Phase 4 — every divergence is a bug in the SHADOW, to be fixed here without
 * ever changing live behavior.
 *
 * <p><b>It must be impossible for this probe to change behavior.</b> {@link #snapshot} and
 * {@link #compareAndLog} each wrap everything in a catch-all and swallow; the live path calls them but
 * never depends on their result. In production this is log-only (a single {@code SHADOW_DIVERGENCE}
 * line). Phase 3.2 adds a test-only hard assertion behind {@link #ASSERT_PROPERTY}.
 */
public final class ShadowParityProbe {

    /** Test-only: when {@code true}, a divergence throws instead of logging (wired in Phase 3.2). */
    public static final String ASSERT_PROPERTY = "npdev.schema.shadow.assert";

    /** Diagnostic (Phase 3.3): when set to a path, each divergence is appended there for analysis. */
    public static final String LOG_FILE_PROPERTY = "npdev.schema.shadow.logFile";

    private ShadowParityProbe() {
    }

    /** The coarse outcome category both sides are reduced to for comparison. */
    enum Verdict {
        NO_CHANGE,
        SAFE,
        DESTRUCTIVE
    }

    /**
     * Read the live schema BEFORE the engine acts (call at the top of {@code beforeMigrate}). Never
     * throws — returns {@code null} if the read fails, and {@link #compareAndLog} then skips.
     */
    public static CurrentSchema snapshot(DataSource dataSource) {
        try {
            return new CurrentSchemaReader().read(dataSource);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Compare the shadow's verdict (from the pre-action snapshot + the manifest) against what the live
     * engine did ({@code liveResult}; {@code null} means the engine refused the boot). Log-only, never
     * throws.
     */
    public static void compareAndLog(CurrentSchema preSnapshot,
            SchemaLifecycleExecutor.SchemaManifest manifest,
            SchemaLifecycleExecutor.DestructiveRecreation liveResult,
            boolean fingerprintChanged) {
        String divergenceLine = null;
        try {
            if (preSnapshot == null || manifest == null || !manifest.physicalDatabase()) {
                return;
            }
            // Scope (owner decision, Phase 3): the shadow validates the live engine's SCHEMA-DRIVEN
            // reconciliation choices, NOT its refusal/override policy. Skip every case where the live
            // outcome is driven by state a pure schema-diff cannot (and should not) model — each of
            // these was confirmed against a real matrix scenario:
            //  (a) no fingerprint change            -> the engine no-ops regardless of live shape;
            //  (b) a refusal (liveResult == null)    -> npdev_schema_history (REG-8 rollback), an ack
            //                                           token, a missing required bond, crash-recovery;
            //  (c) blanket destructive posture       -> whole-schema recreate policy, not item-wise;
            //  (d) a whole-schema recreate performed -> UNKNOWN/ownership/token-driven, not pure schema.
            if (!fingerprintChanged || liveResult == null || manifest.destructiveAllowed() || liveResult.performed()) {
                return;
            }
            DesiredSchema desired = DesiredSchemaFactory.fromManifest(manifest);
            SchemaDiff diff = new SchemaDiffEngine().diff(desired, scopeToOwnedBusinessTables(preSnapshot, manifest));
            Verdict shadow = shadowVerdict(diff);
            Verdict live = liveVerdict(liveResult);
            //  (e) live no-op despite a fingerprint change -> a MANUALLY_MARKED_DONE fast-forward (no
            //      passes run), an override the shadow has no signal for;
            //  (f) the shadow sees no business-column change at all -> any live SAFE action was on a
            //      dimension the shadow does not model yet (unique/FK/index -- P0.2 asymmetry), or an
            //      internal table;
            //  (g) the shadow's only destructive signal is a table drop -> table-drop is ownership-gated
            //      (LNCH-1-B7: an orphan NPDev cannot prove it created is LEFT ALONE), not pure schema.
            if (live == Verdict.NO_CHANGE || diff.isEmpty() || onlyTableDrops(diff)) {
                return;
            }
            if (shadow != live) {
                divergenceLine = "SHADOW_DIVERGENCE: shadow=" + shadow + " live=" + live
                        + " items=" + diff.items().size() + " destructive=" + diff.destructiveItems().size()
                        + " tables=" + preSnapshot.tables().keySet() + " sample=" + sample(diff);
                System.out.println(divergenceLine);
                appendToLogFile(divergenceLine);
            }
        } catch (Throwable ignored) {
            // The shadow must never change behavior — swallow absolutely everything from the computation.
            return;
        }
        // Test-only hard assertion (P3.2), thrown OUTSIDE the swallowing try so it propagates. Never
        // reached on a refusal (skipped above), so it cannot mask a live refusal exception.
        if (divergenceLine != null && Boolean.getBoolean(ASSERT_PROPERTY)) {
            throw new AssertionError("Shadow parity divergence (npdev.schema.shadow.assert on): " + divergenceLine);
        }
    }

    /** Classification-level self-check (Phase 4.1): compares {@link ClassificationReducer}'s reduction of
     *  the live schema diff against what the live {@code classify} returned. Gated on the
     *  {@code npdev.schema.classify.check} property so it costs nothing in normal runs. Log-only unless
     *  {@code npdev.schema.classify.assert} is set. Never changes behavior (fully swallowed). */
    public static void compareClassification(javax.sql.DataSource dataSource,
            SchemaLifecycleExecutor.SchemaManifest manifest,
            SchemaLifecycleExecutor.SchemaChangeClassification direct) {
        if (System.getProperty("npdev.schema.classify.check") == null) {
            return;
        }
        boolean diverged = false;
        String line = null;
        try {
            CurrentSchema current = new CurrentSchemaReader().read(dataSource);
            DesiredSchema desired = DesiredSchemaFactory.fromManifest(manifest);
            SchemaDiff diff = new SchemaDiffEngine().diff(desired, scopeToOwnedBusinessTables(current, manifest));
            SchemaLifecycleExecutor.SchemaChangeClassification reduced = ClassificationReducer.reduce(diff, desired);
            if (reduced != direct) {
                diverged = true;
                line = "CLASSIFY_DIVERGENCE: reducer=" + reduced + " direct=" + direct
                        + " items=" + diff.items().size() + " sample=" + sample(diff);
                System.out.println(line);
                appendToLogFile(line);
            }
        } catch (Throwable ignored) {
            return;
        }
        if (diverged && Boolean.getBoolean("npdev.schema.classify.assert")) {
            throw new AssertionError("Classify reduction divergence: " + line);
        }
    }

    private static void appendToLogFile(String line) {
        String logFile = System.getProperty(LOG_FILE_PROPERTY);
        if (logFile == null || logFile.isBlank()) {
            return;
        }
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(logFile), line + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            // best-effort diagnostic only
        }
    }

    /**
     * The live executor only ever touches the business tables the model owns — never Flyway's own
     * bookkeeping, and internal/platform tables self-heal through a separate path. The shadow must
     * mirror that scope, or every {@code npdev_*}/{@code flyway_schema_history} table reads as an
     * unexplained DROP. Excludes {@code manifest.internalTables()} + anything with the {@code npdev_}
     * platform prefix + Flyway's history table; everything else (business tables, current or dropped)
     * stays in scope.
     */
    static CurrentSchema scopeToOwnedBusinessTables(CurrentSchema current,
            SchemaLifecycleExecutor.SchemaManifest manifest) {
        java.util.Set<String> internal = new java.util.HashSet<>();
        for (String t : manifest.internalTables()) {
            internal.add(t.toLowerCase(java.util.Locale.ROOT));
        }
        java.util.Map<String, com.finalexec.db.schemastate.CurrentTable> kept = new java.util.LinkedHashMap<>();
        current.tables().forEach((name, table) -> {
            if (internal.contains(name) || name.startsWith("npdev_") || name.equals("flyway_schema_history")) {
                return;
            }
            kept.put(name, table);
        });
        return new CurrentSchema(kept);
    }

    private static String sample(SchemaDiff diff) {
        return diff.items().stream().limit(4)
                .map(i -> i.safetyClass() + "(" + i.itemKey() + ")")
                .toList().toString();
    }

    /** The shadow's only destructive signal is one or more table drops — an ownership-gated decision
     *  (B7), not a pure-schema one, so it is out of the shadow's parity scope. */
    private static boolean onlyTableDrops(SchemaDiff diff) {
        return !diff.destructiveItems().isEmpty()
                && diff.destructiveItems().stream()
                        .allMatch(i -> i.safetyClass() == SafetyClass.DESTRUCTIVE_DROP_TABLE);
    }

    static Verdict shadowVerdict(SchemaDiff diff) {
        if (diff.isEmpty()) {
            return Verdict.NO_CHANGE;
        }
        return diff.destructiveItems().isEmpty() ? Verdict.SAFE : Verdict.DESTRUCTIVE;
    }

    static Verdict liveVerdict(SchemaLifecycleExecutor.DestructiveRecreation liveResult) {
        if (liveResult == null) {
            return Verdict.DESTRUCTIVE; // the engine threw / refused
        }
        if (liveResult.performed()) {
            return Verdict.DESTRUCTIVE;
        }
        return liveResult.safeAdditive() ? Verdict.SAFE : Verdict.NO_CHANGE;
    }
}
