package com.finalexec.db;

import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.SafetyClass;
import com.finalexec.db.schemastate.SchemaAheadResolution;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffEngine;
import com.finalexec.db.schemastate.SchemaDiffItem;
import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * B5-B (boundary-lift 2026-09-02, package 4.1): turns 2.3's advisory schema-ahead diagnosis
 * ({@link SchemaAheadAnalysis}) into an action for the subset that can be reversed safely and
 * deterministically -- a live schema that is a pure superset of this (older) build's desired schema,
 * with nothing this build wants actually missing.
 *
 * <p><b>Never driven by the stored snapshot.</b> {@link SchemaAheadAnalysis} reads a snapshot recorded
 * at the newer build's own boot time -- good enough for advisory diagnosis (its own javadoc says
 * "Purely advisory: never runs DDL"), but a snapshot can go stale. Real DROP/ALTER DDL here is always
 * driven by a FRESH live diff, using the exact plumbing {@link SchemaImpactFacade} already uses for the
 * Impact Report: {@link CurrentSchemaReader} + {@link ShadowParityProbe#scopeToOwnedBusinessTables} +
 * {@link DesiredSchemaFactory} + {@link SchemaDiffEngine} -- then the SAME
 * {@link SchemaAheadAnalysis#resolutionFor(SchemaDiffItem)} bucketing 2.3 already established, applied
 * to that fresh result. No parallel classification ladder, no new diff engine.
 *
 * <p><b>Classification -&gt; action.</b> Any item bucketed {@code NEEDS_NEWER_BUILD} (this build wants
 * something the live schema does not have) blocks the whole plan outright -- e.g. a rename can look
 * like an unrelated drop-plus-add from this angle, and reversing it as a plain drop would silently
 * discard data a rename would have preserved. A {@code DESTRUCTIVE_NARROW_TYPE} item ALSO blocks the
 * whole plan -- {@code BOUNDARY_LIFT_PLAN_2026-09-02.md} package 4.1's own done-when is explicit that
 * type narrowing "stay[s] out of scope and stay[s] refused" (it can truncate data on the way down,
 * unlike a straight drop, which only ever loses exactly the column/table named). Zero remaining items
 * means nothing needs reversing. Otherwise every remaining item (always {@code DESTRUCTIVE_DROP_COLUMN}
 * / {@code DESTRUCTIVE_DROP_TABLE}) is the reverse plan.
 *
 * <p><b>The acknowledgment token is the exact same function the forward path uses.</b>
 * {@code SchemaDiffItem.itemKey()} for a destructive item is already
 * {@code SchemaDeltaItem.stableString()} verbatim (see {@link SchemaDiffEngine}'s own construction of
 * these items) -- so the token is {@code DestructiveAckToken.compute(manifest.schemaFingerprint(),
 * <destructive items' itemKey()s>)}, no new stable-string machinery needed.
 *
 * <p><b>Execution reuses the forward destructive path's DDL primitives and safety net directly</b> --
 * {@link SchemaDropSnapshotWriter#snapshotBeforeDrop}, the write-before-execute
 * {@link SchemaHistoryStore} row (PARTIAL-CRASH -&gt; APPLIED), and {@link DestructiveRecreationPass}'s
 * {@code executeDropColumn}/{@code executeDropTableCascade} (widened from {@code private} to
 * package-private for this reuse -- see that class's own comment on each).
 *
 * <p><b>Why nothing here writes {@code npdev_schema_metadata} directly.</b>
 * {@link SchemaHistoryStore#databaseMigratedPastThisBuild} (the actual B5-ahead gate) reads only
 * {@code npdev_schema_history}'s most-recent {@code APPLIED}/{@code MANUALLY_MARKED_DONE} row -- never
 * the live schema and never the stored-fingerprint column. So {@link #execute} only has to insert one
 * more history row ({@code from = <ahead fingerprint>, to = manifest.schemaFingerprint(), outcome =
 * APPLIED}); the very next normal boot's own {@code beforeMigrate}/{@code afterMigrate} -- completely
 * untouched by this class -- naturally sees "not ahead," re-diffs the now-actually-reverted live schema,
 * finds it already matches, and converges the stored fingerprint/snapshot through the SAME
 * single-writer path 2.3 already uses.
 */
final class ReverseMigrationPlanner {

    private ReverseMigrationPlanner() {
    }

    /** What {@link #plan} found. Exactly one of {@link NotAhead}, {@link Blocked}, {@link NothingToDo},
     *  {@link Ready}. */
    interface Plan {
    }

    /** The live database is not ahead of this build at all -- there is nothing to reverse. */
    record NotAhead() implements Plan {
    }

    /** Either this build's desired schema wants something the live database does not have (reversing
     *  would be ambiguous), or the diff includes a type narrowing (out of scope, always refused) -- the
     *  whole plan refuses rather than guessing at or silently dropping part of it. */
    record Blocked(String reason) implements Plan {
    }

    /** The live database is ahead, but every difference already resolves {@code PROCEED_IGNORING} --
     *  nothing needs dropping or narrowing to reconcile. */
    record NothingToDo() implements Plan {
    }

    /** A pure-superset diff, ready to execute behind {@code ackToken} (see class javadoc for how it is
     *  computed). {@code aheadFingerprint} is the live database's own current fingerprint -- the
     *  history row's {@code from_fingerprint} once executed. */
    record Ready(List<SchemaDiffItem> items, String ackToken, String aheadFingerprint) implements Plan {
    }

    /** Outcome of {@link #execute}. {@code NOT_AHEAD}/{@code BLOCKED}/{@code NOTHING_TO_DO}/
     *  {@code TOKEN_MISMATCH} never touch the database; only {@code APPLIED} does. */
    enum Outcome {
        NOT_AHEAD, BLOCKED, NOTHING_TO_DO, TOKEN_MISMATCH, APPLIED
    }

    record ExecutionResult(Outcome outcome, String message, List<String> appliedItems) {
        static ExecutionResult of(Outcome outcome, String message) {
            return new ExecutionResult(outcome, message, List.of());
        }
    }

    /** Computes the fresh, live-diff-driven plan. Never runs DDL. */
    static Plan plan(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        Optional<SchemaHistoryStore.HistoryPoint> aheadOfBuild =
                SchemaHistoryStore.databaseMigratedPastThisBuild(dataSource, manifest);
        if (aheadOfBuild.isEmpty()) {
            return new NotAhead();
        }

        CurrentSchema current = new CurrentSchemaReader().read(dataSource);
        CurrentSchema scopedCurrent = ShadowParityProbe.scopeToOwnedBusinessTables(current, manifest);
        DesiredSchema desired = DesiredSchemaFactory.fromManifest(manifest);
        SchemaDiff diff = new SchemaDiffEngine().diff(desired, scopedCurrent);

        return classify(diff.items(), manifest.schemaFingerprint(), aheadOfBuild.get().toFingerprint());
    }

    /**
     * The pure classification step, deliberately separated from {@link #plan} (which does the DB work
     * above it) so it is directly unit-testable against hand-built {@link SchemaDiffItem} lists --
     * mirroring how {@link SchemaAheadAnalysis#resolutionFor} is tested independently of that class's
     * own DB-touching {@code classify}/{@code render}.
     */
    static Plan classify(List<SchemaDiffItem> diffItems, String schemaFingerprint, String aheadFingerprint) {
        List<String> needsNewerBuild = new ArrayList<>();
        // BOUNDARY_LIFT_PLAN_2026-09-02.md package 4.1's own done-when is explicit: "type narrowing
        // stay[s] out of scope and stay[s] refused" -- a narrowed type can truncate data on the way
        // down (unlike a straight drop, which only ever loses exactly the column/table named), so it
        // blocks the whole plan the same way an ambiguous NEEDS_NEWER_BUILD item does, rather than
        // silently executing a partial reversal.
        List<String> outOfScopeNarrowing = new ArrayList<>();
        List<SchemaDiffItem> destructive = new ArrayList<>();
        for (SchemaDiffItem item : diffItems) {
            SchemaAheadResolution resolution = SchemaAheadAnalysis.resolutionFor(item);
            if (resolution == SchemaAheadResolution.NEEDS_DESTRUCTIVE_DOWNGRADE) {
                if (item.safetyClass() == SafetyClass.DESTRUCTIVE_NARROW_TYPE) {
                    outOfScopeNarrowing.add(describe(item));
                } else {
                    destructive.add(item);
                }
            } else if (resolution == SchemaAheadResolution.NEEDS_NEWER_BUILD) {
                needsNewerBuild.add(describe(item));
            }
        }
        if (!needsNewerBuild.isEmpty() || !outOfScopeNarrowing.isEmpty()) {
            List<String> reasons = new ArrayList<>();
            if (!needsNewerBuild.isEmpty()) {
                reasons.add("this build's desired schema wants " + needsNewerBuild.size() + " thing(s) the "
                        + "live database does not have -- reversing would be ambiguous (e.g. a rename can "
                        + "look like an unrelated drop-plus-add from this angle): " + needsNewerBuild);
            }
            if (!outOfScopeNarrowing.isEmpty()) {
                reasons.add("type narrowing is out of scope for reverse migration and always refuses (it "
                        + "can truncate data, unlike a straight drop): " + outOfScopeNarrowing);
            }
            return new Blocked(String.join(" ", reasons));
        }
        if (destructive.isEmpty()) {
            return new NothingToDo();
        }
        List<String> stableStrings = destructive.stream().map(SchemaDiffItem::itemKey).toList();
        String ackToken = DestructiveAckToken.compute(schemaFingerprint, stableStrings);
        return new Ready(List.copyOf(destructive), ackToken, aheadFingerprint);
    }

    /**
     * Recomputes {@link #plan} fresh (never trusts a caller-supplied plan -- the live database may have
     * drifted since it was last previewed) and, only when {@code suppliedAckToken} matches the freshly
     * computed one exactly, executes it: snapshot the affected tables, write-before-execute a history
     * row, run each item's DDL via {@link DestructiveRecreationPass}'s primitives, mark the row APPLIED.
     */
    static ExecutionResult execute(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest,
            String suppliedAckToken) {
        Plan plan = plan(dataSource, manifest);
        if (plan instanceof NotAhead) {
            return ExecutionResult.of(Outcome.NOT_AHEAD,
                    "this database is not ahead of this build -- there is nothing to reverse.");
        }
        if (plan instanceof Blocked blocked) {
            return ExecutionResult.of(Outcome.BLOCKED, blocked.reason());
        }
        if (plan instanceof NothingToDo) {
            return ExecutionResult.of(Outcome.NOTHING_TO_DO,
                    "the live database is ahead, but every difference is already safe to proceed past -- "
                    + "nothing needs dropping or narrowing.");
        }
        Ready ready = (Ready) plan;
        String supplied = suppliedAckToken == null ? "" : suppliedAckToken.trim();
        if (!supplied.equals(ready.ackToken())) {
            return ExecutionResult.of(Outcome.TOKEN_MISMATCH,
                    "supplied ack token does not match the one freshly computed from the CURRENT live "
                    + "diff (" + ready.ackToken() + "). The live database may have drifted since you last "
                    + "previewed -- re-run --preview and try again with the token it prints.");
        }

        List<String> affectedTables = ready.items().stream()
                .map(SchemaDiffItem::table)
                .distinct()
                .sorted()
                .toList();
        SchemaDropSnapshotWriter.snapshotBeforeDrop(dataSource, affectedTables);

        List<String> displayStrings = ready.items().stream().map(ReverseMigrationPlanner::describe).toList();
        String historyId = SchemaHistoryStore.insertPendingHistoryRowWithItems(dataSource,
                ready.aheadFingerprint(), manifest.schemaFingerprint(),
                SchemaLifecycleExecutor.SchemaChangeClassification.DESTRUCTIVE, displayStrings, supplied);

        List<String> applied = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (SchemaDiffItem item : ready.items()) {
                // classify() never puts a DESTRUCTIVE_NARROW_TYPE item in Ready -- it blocks the whole
                // plan instead (out of scope per the boundary-lift plan's own done-when). Only the two
                // deterministic kinds reach here.
                switch (item.safetyClass()) {
                    case DESTRUCTIVE_DROP_COLUMN -> {
                        DestructiveRecreationPass.executeDropColumn(connection, item.table(), item.column());
                        applied.add("DROP_COLUMN " + item.table() + "." + item.column());
                    }
                    case DESTRUCTIVE_DROP_TABLE -> {
                        DestructiveRecreationPass.executeDropTableCascade(connection, item.table());
                        applied.add("DROP_TABLE " + item.table());
                    }
                    default -> throw new IllegalStateException(
                            "ReverseMigrationPlanner.plan() only ever returns DROP_COLUMN/DROP_TABLE items "
                                    + "in Ready -- unreachable: " + item.safetyClass());
                }
            }
        } catch (SQLException exception) {
            // Deliberately NOT marking the history row APPLIED here -- it stays PARTIAL-CRASH, the
            // correct, honest record of a half-applied reverse pass (same discipline as
            // executeSurgicalDestruction).
            throw new IllegalStateException("Failed applying reverse migration DDL (" + applied.size() + "/"
                    + ready.items().size() + " item(s) applied before failure: " + applied + ")", exception);
        }
        SchemaHistoryStore.markHistoryRowApplied(dataSource, historyId);
        System.out.println("NPDev schema lifecycle: reverse migration applied: " + applied);
        return new ExecutionResult(Outcome.APPLIED, "reverse migration applied: " + applied, List.copyOf(applied));
    }

    private static String describe(SchemaDiffItem item) {
        String location = item.column() != null ? item.table() + "." + item.column() : item.table();
        if (item.before() != null && item.after() != null) {
            return location + " (" + item.before() + " -> " + item.after() + ")";
        }
        if (item.before() != null) {
            return location + " (" + item.before() + ")";
        }
        return location;
    }
}
