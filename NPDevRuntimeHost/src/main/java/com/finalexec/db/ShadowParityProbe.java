package com.finalexec.db;

import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffEngine;

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
            SchemaLifecycleExecutor.DestructiveRecreation liveResult) {
        try {
            if (preSnapshot == null || manifest == null || !manifest.physicalDatabase()) {
                return;
            }
            DesiredSchema desired = DesiredSchemaFactory.fromManifest(manifest);
            SchemaDiff diff = new SchemaDiffEngine().diff(desired, preSnapshot);
            Verdict shadow = shadowVerdict(diff);
            Verdict live = liveVerdict(liveResult);
            if (shadow != live) {
                System.out.println("SHADOW_DIVERGENCE: expected=" + shadow + " actual=" + live
                        + " items=" + diff.items().size() + " destructive=" + diff.destructiveItems().size());
            }
        } catch (Throwable ignored) {
            // The shadow must never change behavior — swallow absolutely everything.
        }
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
