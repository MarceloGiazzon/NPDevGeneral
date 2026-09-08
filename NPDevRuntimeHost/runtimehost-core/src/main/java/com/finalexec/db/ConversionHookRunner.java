package com.finalexec.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.SafetyClass;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffEngine;
import com.finalexec.db.schemastate.SchemaDiffItem;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import com.npdev.kernel.storage.sql.PartialApplicationTruth;
import com.npdev.kernel.storage.sql.SqlDialects;
import com.npdev.kernel.storage.sql.StorageCapability;
import com.finalexec.npdev.service.PluginExecutionPolicyEvaluator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.finalexec.npdev.service.pluginipc.PluginIpcChildProcessPool;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Schema-engine rebuild, Phase 7 (SER-P7.3): the "freedom pillar" -- runs operator-authored SQL
 * conversion hooks against a residual schema diff, invoked at ONE fixed point in {@code
 * SchemaLifecycleExecutor#beforeMigrateDecision}: after the safe convergent passes (renames/relax/
 * tighten) and BEFORE the destructive decision ({@code refuseIfRequiredBondColumnMissing} /
 * {@code SchemaDeltaReport.generate}). v1 is SQL-only; a Java {@code DataMigrationHook} interface is
 * explicitly deferred (ADR-0003 code-bearing-objects track).
 *
 * <p><b>Package placement note:</b> the plan sketch puts this in {@code com.finalexec.db.schemastate},
 * but that sub-package gets NO package access to {@link ShadowParityProbe#scopeToOwnedBusinessTables}
 * (package-private in {@code com.finalexec.db}) -- the exact scoping every other diff consumer
 * ({@link SchemaImpactFacade}, {@link SchemaDeltaReport}, {@link ImpactReportWriter}) uses. Living in
 * {@code com.finalexec.db} instead (same reasoning as {@link SchemaImpactFacade}, SER-P6.0) lets this
 * class compute the IDENTICAL scoped diff those surfaces do, so a hook's {@code claims} match against
 * the same item keys the Impact Report shows -- rather than inventing a second, narrower diff view.
 *
 * <h2>Rule 6 (sanctioned destruction) needs no special-case code here</h2>
 * A hook's {@code convert.sql} performs the ACTUAL data conversion/destruction itself (e.g. it drops
 * the column it claims, having already migrated the data it cared about). By the time this method
 * returns and the existing destructive-decision code re-computes {@code SchemaDeltaReport} fresh
 * against the (now hook-modified) live database, a fully-resolved item has simply vanished from the
 * residual diff -- so {@code DestructiveAckToken} is computed over a smaller residual set and no token
 * is required for what the hook already resolved. "Authoring the hook IS the acknowledgment" falls out
 * of the existing token-over-residual-diff design; nothing downstream needed to change. An unclaimed
 * destructive item is untouched by any of this and remains exactly as token-gated as before.
 *
 * <h2>Verify runs INSIDE the hook transaction (finding #1), and the H2 DDL caveat</h2>
 * A hook's {@code convert.sql} AND its {@code verifySql} run in ONE transaction on ONE connection
 * ({@link #executeAndVerify}); the transaction commits ONLY when there is no verifySql or it matched
 * {@code verifyExpect}. A verify mismatch (or a verifySql that errors) rolls the WHOLE hook back, so
 * nothing persists and the boot refuses cleanly. <b>Engine caveat:</b> PostgreSQL has transactional DDL,
 * so a rolled-back hook fully undoes both its data (DML) and schema (DDL) changes. <b>H2 has no
 * transactional DDL</b> — an {@code ALTER TABLE}/{@code DROP} auto-commits, so on H2 a verify failure
 * rolls back the hook's DML but any DDL it already executed persists (and, worse, an H2 DDL statement
 * implicitly commits everything before it in the same batch). Practical guidance: keep destructive DDL
 * and data movement in SEPARATE hooks/boots, or run conversions on Postgres, if you need a verify
 * failure to leave the schema untouched. This is an H2 engine limitation, not a hook-runner bug.
 */
public final class ConversionHookRunner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** SER closure-plan G6: detects a hook's convert SQL mixing DDL ({@code ALTER}/{@code DROP}/
     *  {@code CREATE TABLE}) with a {@code verifySql} on H2, where a verify failure would not roll back
     *  the already-executed DDL (H2 has no transactional DDL). */
    private static final java.util.regex.Pattern MIXES_DDL_PATTERN =
            java.util.regex.Pattern.compile("(?is).*\\b(ALTER|DROP|CREATE)\\s+TABLE\\b.*");

    /**
     * BOUNDARY_LIFT_PLAN_2026-09-02 package 3.4 (B11), default flipped by STOR-35 (POSTURAL_LIFT_PLAN
     * 2026-09-07.md package P7): whether the {@link #MIXES_DDL_PATTERN} shape on a non-{@link
     * StorageCapability#DDL_IN_TRANSACTION} engine only warns, refuses the boot outright BEFORE
     * running the hook (so the mixed state can never be authored into existence rather than only be
     * warned about after the fact), or is decomposed into guarded, journaled, resumable phases.
     * Default is now {@code split} (the safe mode is the one you get by not thinking about it); a
     * mode that was not chosen explicitly falls back to warn when the splitter cannot render a
     * statement idempotent, so no app that boots today stops booting. Overridable with
     * {@code -Dnpdev.schema.conversionHooks.mixedDdlVerify=warn|refuse|split};
     * {@code =warn} restores the previous behaviour byte-for-byte.
     */
    private static final String MIXED_DDL_VERIFY_PROPERTY = "npdev.schema.conversionHooks.mixedDdlVerify";

    /** STOR-22: the two item-key prefixes a hook's target column can appear under across a crash --
     *  see {@link #tightenedColumnStillClaimed}. */
    private static final String ADD_REQUIRED_COLUMN_PREFIX = "ADD_REQUIRED_COLUMN:";
    private static final String TIGHTEN_NOT_NULL_PREFIX = "TIGHTEN_NOT_NULL:";

    private enum MixedDdlVerifyMode {
        WARN, REFUSE, SPLIT
    }

    /** STOR-35 (boundary B11, package P7): the resolved mode plus whether the operator chose it
     *  explicitly -- tracked as a REAL field (never by re-reading the system property at the decision
     *  point), because the split-blocked fallback depends on it: an EXPLICIT split refuses when the
     *  splitter blocks; the DEFAULT split falls back to warn with a named log line. */
    record ResolvedMixedDdlVerify(MixedDdlVerifyMode mode, boolean explicit) {
    }

    private static ResolvedMixedDdlVerify resolveMixedDdlVerifyMode() {
        String configured = System.getProperty(MIXED_DDL_VERIFY_PROPERTY);
        if (configured == null || configured.isBlank()) {
            // STOR-35 (P7): the default flipped from warn to split -- see the property javadoc. The
            // non-explicit flag is what lets a splitter-blocked hook fall back to warn instead of
            // refusing on upgrade.
            return new ResolvedMixedDdlVerify(MixedDdlVerifyMode.SPLIT, false);
        }
        String trimmed = configured.trim();
        if ("refuse".equalsIgnoreCase(trimmed)) {
            return new ResolvedMixedDdlVerify(MixedDdlVerifyMode.REFUSE, true);
        }
        if ("warn".equalsIgnoreCase(trimmed)) {
            return new ResolvedMixedDdlVerify(MixedDdlVerifyMode.WARN, true);
        }
        if ("split".equalsIgnoreCase(trimmed)) {
            return new ResolvedMixedDdlVerify(MixedDdlVerifyMode.SPLIT, true);
        }
        // A typo in an operator-set property must not silently widen or narrow what refuses -- log,
        // and fall back to the new default, non-explicit, so even a typo'd boot gets the safe default.
        System.out.println("NPDev schema lifecycle: ignoring unrecognized " + MIXED_DDL_VERIFY_PROPERTY
                + "='" + configured + "' (expected warn|refuse|split); using the default 'split'.");
        return new ResolvedMixedDdlVerify(MixedDdlVerifyMode.SPLIT, false);
    }

    // STOR-34 (boundary B12, POSTURAL_LIFT_PLAN_2026-09-07.md package P6): hook atomicity. Default
    // 'perHook' is EXACTLY today's behaviour -- each hook in its own transaction (rule 3). 'collective'
    // promises ONE transaction across every selected hook, and is refused outright (never silently
    // degraded) wherever that promise cannot be kept.
    private static final String ATOMICITY_PROPERTY = "npdev.schema.conversionHooks.atomicity";

    private enum AtomicityMode {
        PER_HOOK, COLLECTIVE
    }

    /** Resolver shaped exactly like {@link #resolveMixedDdlVerifyMode} (typo branch included): an
     *  unrecognized operator-set value logs and falls back to the per-hook default. */
    private static AtomicityMode resolveAtomicityMode() {
        String configured = System.getProperty(ATOMICITY_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return AtomicityMode.PER_HOOK;
        }
        String trimmed = configured.trim();
        if ("perHook".equalsIgnoreCase(trimmed) || "per-hook".equalsIgnoreCase(trimmed)) {
            return AtomicityMode.PER_HOOK;
        }
        if ("collective".equalsIgnoreCase(trimmed)) {
            return AtomicityMode.COLLECTIVE;
        }
        System.out.println("NPDev schema lifecycle: ignoring unrecognized " + ATOMICITY_PROPERTY
                + "='" + configured + "' (expected perHook|collective); using the default 'perHook'.");
        return AtomicityMode.PER_HOOK;
    }

    private ConversionHookRunner() {
    }

    /** Callback for {@code npdev_schema_history} rows. {@code SchemaLifecycleExecutor}'s own
     *  history-write helpers are {@code private}, so it supplies this as a lambda defined inside its
     *  own compilation unit (where that private access is legal) instead of this class reaching in. */
    @FunctionalInterface
    public interface HistoryWriter {
        void write(String label, String outcome, List<String> detailLines);
    }

    // Package-private (not private): JavaMigrationHookRunner (same package, B1) needs to read a
    // javaHook's claims/id from the SAME Hook instance ConversionHookRunner already selected and
    // parsed -- passing individual fields instead would just re-scatter what parseHook already
    // assembled in one place.
    record Hook(String id, List<String> claims, String verifySql, int verifyExpect,
            String commonSql, String h2Sql, String postgresSql, String javaHookClass, String javaHookMethod) {
        String sqlFor(String engine) {
            if ("postgres".equals(engine) && postgresSql != null) {
                return postgresSql;
            }
            if ("h2".equals(engine) && h2Sql != null) {
                return h2Sql;
            }
            return commonSql;
        }
    }

    /**
     * B1 (REAL_LIFT_PLAN_2026-09-03, B13): the Spring-managed beans a {@code javaHook} phase needs to
     * run in the isolated plugin pool -- bundled into one record so {@link #run} keeps a stable
     * signature as this grows, rather than adding parameters one at a time. {@code null} (the
     * 3-arg {@link #run} overload every existing caller/test still uses) is exactly the "this app has
     * no javaHook conversions" case: {@link JavaMigrationHookRunner} is never reached without a hook
     * whose {@code hook.json} declares one, and a generated app only ever gets one of those alongside
     * a non-empty {@code java-source-runtime-refs.json} -- the SAME condition that makes {@code
     * PluginIpcChildProcessPool}'s own bean non-null (NpdevPluginConfig).
     */
    public record JavaHookRuntimeContext(
            PluginIpcChildProcessPool pool,
            RuntimePluginAdapterRegistry registry,
            PluginExecutionPolicyEvaluator policyEvaluator) {
    }

    /** STOR-33 (boundary B14, POSTURAL_LIFT_PLAN_2026-09-07.md package P5): what one
     *  {@link #run} call did, without a second live diff. {@code applied} keeps the pre-P5 boolean
     *  semantics (a hook actually ran this call). {@code preRunUnresolvedKeys} is the unresolved
     *  diff-item key set this call SELECTED against (the same diff it already computed, never a
     *  second one); {@code claimedItemToHookId} maps every selected hook's claim key to its id,
     *  so {@code SchemaLifecycleExecutor} can name the hook that sanctioned each pre-hook
     *  destructive item. */
    public record HookRunOutcome(boolean applied, Set<String> preRunUnresolvedKeys,
            Map<String, String> claimedItemToHookId) {
    }

    /**
     * Runs every conversion hook whose claims intersect the current unresolved diff, in ascending
     * (natural) {@code id} order, each in its own transaction, verifying and re-diffing per the plan's
     * 7 numbered rules. Throws {@link IllegalStateException} to refuse the boot on any failure -- a
     * failed hook is rolled back before any refusal is thrown, and nothing in
     * {@code SchemaLifecycleExecutor}'s own destructive path runs until this method returns
     * successfully. A no-op (immediate return {@code false}, no history rows) when there is nothing
     * unresolved -- idempotent on every ordinary re-boot.
     *
     * @return {@code true} if at least one hook was applied this call (the caller uses this to tell
     *         {@link ShadowParityProbe} that a pure schema-diff snapshot can no longer explain the
     *         outcome -- a hook resolving something is a deliberate, documented exemption, not a bug).
     */
    public static boolean run(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest,
            HistoryWriter historyWriter) {
        return run(dataSource, manifest, historyWriter, null);
    }

    /** B1 (REAL_LIFT_PLAN_2026-09-03, B13): the javaHook-aware overload -- see {@link
     *  JavaHookRuntimeContext}'s own javadoc for why {@code javaHookContext} is nullable. */
    public static boolean run(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest,
            HistoryWriter historyWriter, JavaHookRuntimeContext javaHookContext) {
        return run(dataSource, manifest, historyWriter, javaHookContext, null).applied();
    }

    /** STOR-32 (boundary B7, POSTURAL_LIFT_PLAN_2026-09-07.md package P4): the production overload.
     *  {@code bootId} keys this call's {@link BootResidueJournal.LifecycleStep#CONVERSION_HOOKS} rows
     *  (started once hooks are SELECTED -- never when the unresolved diff has nothing for them --
     *  committed once every selected hook's execution completed, BEFORE the rule-5 residual re-diff
     *  so a rule-5 refusal still counts the hooks as having run). Every {@link IllegalStateException}
     *  this call throws is a schema-lifecycle refusal, so each is decorated with the boot's
     *  {@link SchemaRefusal#withResidue} block (same exception type, same cause, residue appended to
     *  the message -- never a different exception). A {@code null} bootId (the non-production
     *  overloads) is byte-identical to pre-P4 behavior: no journal rows, no decoration.
     *
     *  <p>STOR-33 (boundary B14, same plan, package P5): the return type grew from {@code boolean}
     *  to {@link HookRunOutcome} so the caller can enumerate what this call's one pre-run diff
     *  contained and which selected hook claimed each item -- the sanctioned-destruction machinery
     *  needs both without a second live introspection. The boolean-returning overloads above keep
     *  every pre-P5 caller compiling and behaving identically ({@code .applied()}). */
    public static HookRunOutcome run(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest,
            HistoryWriter historyWriter, JavaHookRuntimeContext javaHookContext, String bootId) {
        try {
            return runInternal(dataSource, manifest, historyWriter, javaHookContext, bootId);
        } catch (RuntimeException refusal) {
            throw decorateWithResidue(refusal, dataSource, bootId);
        }
    }

    private static HookRunOutcome runInternal(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest,
            HistoryWriter historyWriter, JavaHookRuntimeContext javaHookContext, String bootId) {
        Set<String> unresolvedKeys = unresolvedItemKeys(dataSource, manifest);
        if (unresolvedKeys.isEmpty()) {
            return new HookRunOutcome(false, unresolvedKeys, Map.of());
        }

        // A1 (REAL_LIFT_PLAN_2026-09-03): a hook's OWN first phase committing (e.g. its ADD COLUMN)
        // changes the live schema shape that SchemaDiffEngine diffs against -- a present-but-not-yet-
        // tightened column reclassifies from ADD_REQUIRED_COLUMN to TIGHTEN_NOT_NULL, a DIFFERENT item
        // key than the one the hook's own `claims` entry names. Selecting hooks by claim-intersection
        // alone would silently stop considering a hook the instant its first phase committed -- exactly
        // the crash window a resumable migration must resume THROUGH, not get permanently stuck on.
        // MigrationPhaseJournal.hasAnyActivity is the fix: it answers "did split mode ever touch this
        // hook for THIS migration", independent of how the diff engine currently classifies the item.
        String migrationId = MigrationPhaseJournal.migrationId(
                SchemaLifecycleExecutor.readStoredFingerprintPublic(dataSource), manifest.schemaFingerprint());

        List<Hook> selected = new ArrayList<>();
        for (Hook hook : loadHooks()) {
            boolean matches = hook.claims().stream().anyMatch(unresolvedKeys::contains)
                    || hasIncompletePhaseActivity(dataSource, migrationId, hook.id())
                    || tightenedColumnStillClaimed(hook, unresolvedKeys);
            if (matches) {
                selected.add(hook);
            } else {
                System.out.println("NPDev schema lifecycle: conversion hook '" + hook.id()
                        + "' claims nothing in the current unresolved diff; skipping (a stale hook is not "
                        + "an error -- the diff may already be converged).");
            }
        }
        if (selected.isEmpty()) {
            return new HookRunOutcome(false, unresolvedKeys, Map.of());
        }
        selected.sort(Comparator.comparing(Hook::id, ConversionHookRunner::naturalCompare));

        // STOR-33 (boundary B14, package P5): the claim->hook map the caller needs to name the hook
        // that sanctioned each pre-hook destructive item. Built from the SAME selection (no second
        // diff); a claim is only ever counted when a hook actually made it and was selected.
        Map<String, String> claimedItemToHookId = new LinkedHashMap<>();
        for (Hook hook : selected) {
            for (String claim : hook.claims()) {
                claimedItemToHookId.putIfAbsent(claim, hook.id());
            }
        }

        // STOR-35 (boundary B11, package P7): the mixed-DDL mode is resolved ONCE per boot run and carried
        // through the flow as a record -- the split-blocked fallback and the collective refusal both
        // key on its `explicit` field, never by re-reading the property at a decision point.
        ResolvedMixedDdlVerify mixedDdl = resolveMixedDdlVerifyMode();

        // STOR-34 (boundary B12, package P6): collective atomicity opt-in. NOTHING has run when these
        // refusals fire -- they sit immediately after selection, before the first HOOK_STARTED row or
        // the boots-residue step. Order matters: the explicit split+collective contradiction is
        // refused FIRST (an operator who set both asked for mutually exclusive semantics; a NON-
        // explicit default split must not refuse -- on a transactional engine split is never consulted
        // anyway, and on H2/MySQL the capability refusal below is the honest primary answer); then the
        // engine capability (H2/MySQL commit DDL implicitly, so one collective transaction is not
        // honourable -- the mode is refused outright, never silently degraded to perHook); then the
        // javaHook carve-out (a javaHook manages its own commits, so it can never join a collective
        // transaction -- a measured deviation from the plan, which named only the three B12 codes
        // above; recorded in STOR-34's detail).
        AtomicityMode atomicity = resolveAtomicityMode();
        if (atomicity == AtomicityMode.COLLECTIVE) {
            if (mixedDdl.mode() == MixedDdlVerifyMode.SPLIT && mixedDdl.explicit()) {
                throw new IllegalStateException("B12:collective_incompatible_with_split: conversion hooks cannot "
                        + "be BOTH collectively atomic (-D" + ATOMICITY_PROPERTY + "=collective) AND "
                        + "phase-split-and-journaled (-D" + MIXED_DDL_VERIFY_PROPERTY + "=split): split commits "
                        + "each phase individually by design, which is the opposite promise of one collective "
                        + "transaction. Pick one; split is the right answer on an engine without transactional DDL.");
            }
            if (!SqlDialects.active().supports(StorageCapability.DDL_IN_TRANSACTION)) {
                String engineName = SqlDialects.active().name();
                throw new IllegalStateException("B12:collective_atomicity_unavailable: engine '" + engineName
                        + "' has no transactional DDL (it commits DDL implicitly), so conversion hooks cannot be "
                        + "collectively atomic here -- refused BEFORE anything ran, never silently degraded to "
                        + "per-hook. Use individual hooks (the default) -- the DEFAULT " + MIXED_DDL_VERIFY_PROPERTY
                        + "=split is the resumable alternative this engine CAN honour: each phase is journaled "
                        + "in npdev_migration_phase_journal before the next one starts, and a boot that crashes "
                        + "mid-hook resumes at the first incomplete phase.");
            }
            for (Hook hook : selected) {
                if (hook.javaHookClass() != null) {
                    throw new IllegalStateException("B12:collective_java_hook_unavailable: conversion hook '"
                            + hook.id() + "' is a javaHook, which manages its own commits (each batch commits "
                            + "with its own journal row) and so can never join a collective transaction -- "
                            + "either unset -D" + ATOMICITY_PROPERTY + "=collective or convert the hook to SQL.");
                }
            }
        }

        // SER closure-plan G5: hooks are individually atomic by default (rule 3) -- each runs in its own
        // transaction, so a later hook failing does NOT roll back an earlier one that already committed.
        // Operators need to know this so they write idempotent convert.sql (a later boot re-runs only what
        // the diff still says is unresolved, which may re-select an already-partially-applied hook). See
        // docs/IMPACT_REPORTS.md's conversion-hooks refusal list. STOR-34 (B12): under the collective
        // opt-in the opposite message is printed instead -- one transaction, one commit at the end.
        if (selected.size() > 1) {
            if (atomicity == AtomicityMode.COLLECTIVE) {
                System.out.println("NPDev schema lifecycle: running " + selected.size() + " conversion hooks "
                        + "in ONE collective transaction (STOR-34/B12) -- a single commit at the end; any "
                        + "failure rolls the whole set back.");
            } else {
                System.out.println("NPDev schema lifecycle: running " + selected.size() + " conversion hooks "
                        + "in separate transactions -- each hook must be idempotent (a later hook failing does "
                        + "not roll back an earlier one).");
            }
        }

        // STOR-32 (boundary B7): the hooks step of this boot begins once selection is definite. A boot
        // whose diff selects nothing never journals the step (there is nothing for a retry to re-run);
        // a refusal anywhere below leaves it started-not-committed, so the residue never counts a hook
        // step that did not complete.
        if (bootId != null) {
            BootResidueJournal.started(dataSource, bootId, null,
                    BootResidueJournal.LifecycleStep.CONVERSION_HOOKS, null);
        }

        String engine = detectEngine(dataSource, manifest);
        List<Hook> applied;
        if (atomicity == AtomicityMode.COLLECTIVE) {
            // STOR-34 (B12): every selected hook on ONE connection, one commit at the end -- any
            // failure rolls the whole set back (see runCollectiveHookSet).
            applied = runCollectiveHookSet(dataSource, historyWriter, selected, engine);
        } else {
            applied = runPerHookLoop(dataSource, migrationId, historyWriter, selected, engine, javaHookContext,
                    mixedDdl);
        }

        // STOR-32 (boundary B7): every selected hook's execution completed -- committed BEFORE the
        // rule-5 re-diff, so a "claims still required" refusal still counts the hooks as having run
        // (their execution did; only the claim verification failed). A failure INSIDE the loop left
        // the step started-not-committed above, as it should.
        if (bootId != null) {
            BootResidueJournal.committed(dataSource, bootId,
                    BootResidueJournal.LifecycleStep.CONVERSION_HOOKS);
        }

        // Rule 5: re-diff against the live DB, once, after every selected hook has succeeded. A claim
        // is a promise the engine verifies, never trusts.
        Set<String> residualKeys = unresolvedItemKeys(dataSource, manifest);
        List<String> stillRequired = new ArrayList<>();
        for (Hook hook : applied) {
            for (String claim : hook.claims()) {
                if (residualKeys.contains(claim)) {
                    stillRequired.add(hook.id() + " -> " + claim);
                }
            }
        }
        if (!stillRequired.isEmpty()) {
            historyWriter.write("CONVERSION_HOOKS", "REFUSED", stillRequired);
            throw new IllegalStateException("Conversion hook(s) claimed item(s) that are still required after "
                    + "running: " + stillRequired + " -- refusing the boot.");
        }
        historyWriter.write("CONVERSION_HOOKS", "RESOLVED",
                List.of("appliedHooks=" + applied.stream().map(Hook::id).toList(),
                        "residualUnresolvedCount=" + residualKeys.size()));
        return new HookRunOutcome(true, unresolvedKeys, claimedItemToHookId);
    }

    /** STOR-32 (boundary B7): every lifecycle refusal this class throws is an {@link
     *  IllegalStateException} -- rethrow one of the SAME type and cause with the boot's residue
     *  block appended to the message, or the ORIGINAL exception when there is nothing to append
     *  ({@code bootId == null} -- the test overloads -- or a residue that could not be read). A
     *  refusal's own exception type and primary message text never change. */
    private static RuntimeException decorateWithResidue(RuntimeException refusal, DataSource dataSource, String bootId) {
        if (bootId == null || !(refusal instanceof IllegalStateException)) {
            return refusal;
        }
        String message = SchemaRefusal.withResidue(refusal.getMessage(), dataSource, bootId);
        if (message.equals(refusal.getMessage())) {
            return refusal;
        }
        IllegalStateException copy = refusal.getCause() == null
                ? new IllegalStateException(message)
                : new IllegalStateException(message, refusal.getCause());
        copy.setStackTrace(refusal.getStackTrace());
        return copy;
    }

    /**
     * A1 (REAL_LIFT_PLAN_2026-09-03, B11 "real lift"): the SPLIT-mode alternative to STOR-20's hard
     * refusal -- classifies {@code sql} into single-statement, resumable phases and runs whichever
     * ones {@link MigrationPhaseJournal} does not already show completed for this migration. Throws a
     * refusal, in the same spirit as REFUSE mode's, naming the exact blocking statement, when the hook
     * contains a DDL shape this platform does not know how to render idempotent -- refused BEFORE
     * running anything, never a partial split.
     *
     * @param migrationId precomputed once by the caller ({@code run}'s own selection step already
     *                     needs it for {@link #hasIncompletePhaseActivity}) rather than re-derived here
     * @param resolved     STOR-35 (P7): the resolved mode WITH its explicit flag -- an explicitly
     *                     chosen split refuses when the splitter blocks (as before); the DEFAULT split
     *                     falls back to warn with a named log line instead, so an app whose hook the
     *                     splitter cannot parse keeps booting exactly as it did before the flip.
     * @return {@code true} always (a boolean, not void, so the call site reads as setting a flag);
     *         every failure path throws instead of returning {@code false} -- EXCEPT a splitter-blocked
     *         DEFAULT mode, which returns {@code false} so the caller runs the hook raw (warn behavior)
     */
    private static boolean runSplitPhases(DataSource dataSource, String migrationId,
            HistoryWriter historyWriter, Hook hook, String sql, String activeEngineName,
            ResolvedMixedDdlVerify resolved) {
        ConversionHookPhaseSplitter.SplitResult splitResult =
                ConversionHookPhaseSplitter.split(sql, SqlDialects.active());
        if (!splitResult.isSplittable()) {
            ConversionHookPhaseSplitter.Blocked blocked = splitResult.blocked();
            if (resolved.explicit()) {
                historyWriter.write(historyLabel(hook), "HOOK_FAILED",
                        List.of("B11:mixed_ddl_verify_refused:" + hook.id() + " on " + activeEngineName,
                                "blockedStatement=" + blocked.statement()));
                throw new IllegalStateException("B11:mixed_ddl_verify_refused: conversion hook '" + hook.id()
                        + "' mixes DDL with a verifySql on '" + activeEngineName + "', and statement #"
                        + blocked.ordinal() + " (" + blocked.statement() + ") " + blocked.reason() + " -- refused "
                        + "before running, rather than risking a half-applied state no automatic split could make "
                        + "safe. Split it into two hooks by hand (rule 3): one with the DDL alone and no verifySql, "
                        + "one with the data movement and this verifySql. "
                        + "-D" + MIXED_DDL_VERIFY_PROPERTY + "=warn restores the previous behaviour byte-for-byte. "
                        + "Run `npdev why B11` for the full explanation.");
            }
            // STOR-35 (P7): the mode is the DEFAULT, not an explicit choice -- splitter-blocked falls
            // back to the previous warn-and-proceed behavior so the flip cannot turn a booting app into
            // a refusing one. Named log line, never silent: the fallback IS the difference between this
            // default and a hypothetical default-refuse.
            System.out.println("NPDev schema lifecycle: B11:split_blocked_fell_back_to_warn: conversion hook '"
                    + hook.id() + "' mixes DDL with a verifySql on '" + activeEngineName + "', and statement #"
                    + blocked.ordinal() + " (" + blocked.statement() + ") " + blocked.reason()
                    + " -- " + MIXED_DDL_VERIFY_PROPERTY + " was NOT set explicitly, so this boot falls back to "
                    + "the previous warn-and-proceed behavior and runs the hook raw. Set -D"
                    + MIXED_DDL_VERIFY_PROPERTY + "=split explicitly to make this a hard refusal instead, or "
                    + "=warn to restore the previous behavior byte-for-byte.");
            return false;
        }
        try {
            ConversionHookPhaseRunner.PhaseOutcome outcome =
                    ConversionHookPhaseRunner.run(dataSource, migrationId, hook.id(), splitResult.phases());
            historyWriter.write(historyLabel(hook), "HOOK_PHASES_APPLIED",
                    List.of("phases=" + splitResult.phases().size(), "ran=" + outcome.phasesRun(),
                            "resumedSkipped=" + outcome.phasesSkipped()));
        } catch (SQLException exception) {
            historyWriter.write(historyLabel(hook), "HOOK_FAILED",
                    List.of("phase execution error=" + exception.getMessage()));
            throw new IllegalStateException("Conversion hook '" + hook.id() + "' failed executing a journaled "
                    + "phase: " + exception.getMessage() + " -- refusing the boot. Every phase already "
                    + "completed stays applied (B11); the next boot resumes at the first phase not yet "
                    + "completed.", exception);
        }
        return true;
    }

    /** Runs only {@code verifySql} on a fresh connection -- the closing check for a hook whose phases
     *  already ran via {@link #runSplitPhases}. No transaction to roll back: every phase already
     *  committed for real (the whole point of forward-only resumability on an implicit-commit engine),
     *  so a verify failure here refuses the boot without touching data that is already durably
     *  applied. */
    private static HookOutcome verifyOnly(DataSource dataSource, String verifySql, int verifyExpect) throws SQLException {
        if (verifySql == null || verifySql.isBlank()) {
            return new HookOutcome(true, false, -1L, null);
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(verifySql);
             ResultSet resultSet = statement.executeQuery()) {
            long actual = resultSet.next() ? resultSet.getLong(1) : -1L;
            return new HookOutcome(actual == verifyExpect, true, actual, null);
        } catch (SQLException verifyException) {
            return new HookOutcome(false, true, -1L, verifyException.getMessage());
        }
    }

    /**
     * SER-P7.4: a read-only index of every {@code hook.json} claim currently on the classpath,
     * {@code itemKey -> hookId} -- NO diff computation, NO SQL execution. The Impact Report uses this
     * to show {@code HOOK: <id>} for an item a hook WOULD resolve if this boot actually ran, before it
     * runs (REPORT_ONLY / ControlPanel are read-only surfaces). When two hooks claim the same key, the
     * later one (classpath enumeration order) wins -- an authoring conflict an operator should
     * resolve, not a case this index needs to arbitrate cleverly. Never throws (mirrors {@link
     * #loadHooks}'s degrade-to-empty contract).
     */
    public static Map<String, String> loadClaimIndex() {
        Map<String, String> index = new LinkedHashMap<>();
        for (Hook hook : loadHooks()) {
            for (String claim : hook.claims()) {
                index.put(claim, hook.id());
                // STOR-22: also index the TIGHTEN_NOT_NULL alias so a report run after a mid-hook crash
                // shows "HOOK: <id>" (it will actually resolve on the next boot -- see
                // tightenedColumnStillClaimed) instead of a false NEEDS_ATTENTION.
                if (claim.startsWith(ADD_REQUIRED_COLUMN_PREFIX)) {
                    index.put(TIGHTEN_NOT_NULL_PREFIX + claim.substring(ADD_REQUIRED_COLUMN_PREFIX.length()),
                            hook.id());
                }
            }
        }
        return index;
    }

    /** Test-only seam (SER closure plan G1): the number of loaded hooks whose common convert SQL
     *  resolved to non-null/non-blank -- proves sibling resolution ({@link #readSiblingIfPresent})
     *  works on whatever classpath layout is in effect (a directory during ordinary test runs, a
     *  {@code jar:} URL when {@link #loadHooks} is driven from inside a packaged boot jar). */
    static long loadedHooksWithConvertSqlCount() {
        return loadHooks().stream().filter(h -> h.commonSql() != null && !h.commonSql().isBlank()).count();
    }

    /** Opens the connection {@link MigrationPhaseJournal#hasAnyActivity} needs -- a hook-selection-time
     *  pre-check, so it degrades to "no activity" (never blocks selection) rather than propagating a
     *  checked exception into the selection loop's stream/lambda-free {@code for} on a transient read
     *  failure; the same table this reads is also opened fresh inside {@link #runSplitPhases} moments
     *  later for the hooks that DO get selected, so a failure here just means one fewer resume signal
     *  this boot, not a lost migration. */
    private static boolean hasIncompletePhaseActivity(DataSource dataSource, String migrationId, String hookId) {
        try (Connection connection = dataSource.getConnection()) {
            return MigrationPhaseJournal.hasAnyActivity(connection, migrationId, hookId);
        } catch (SQLException exception) {
            return false;
        }
    }

    /** STOR-22: a hook's own ADD COLUMN committing before a crash (H2/MySQL implicit-commit-on-DDL)
     *  reclassifies its target column from ADD_REQUIRED_COLUMN to TIGHTEN_NOT_NULL
     *  (SchemaDiffEngine#compareColumn) -- a different item key than the one claims[] names. Matching on
     *  the SAME (table, column) pair under the TIGHTEN_NOT_NULL key re-selects the hook so its
     *  already-idempotent convert.sql (STOR-5/B12) can finish what the crash interrupted. Covers the
     *  single-transaction executeAndVerify path (every generated hook's default); the SPLIT-mode journal
     *  check above already covers the phase-split path. */
    private static boolean tightenedColumnStillClaimed(Hook hook, Set<String> unresolvedKeys) {
        for (String claim : hook.claims()) {
            if (claim.startsWith(ADD_REQUIRED_COLUMN_PREFIX)
                    && unresolvedKeys.contains(TIGHTEN_NOT_NULL_PREFIX
                            + claim.substring(ADD_REQUIRED_COLUMN_PREFIX.length()))) {
                return true;
            }
        }
        return false;
    }

    private static String historyLabel(Hook hook) {
        return "CONVERSION_HOOK:" + hook.id();
    }

    /** The current unresolved diff-item keys: every non-safe {@link SafetyClass} (backfill/hook/manual
     *  review/destructive) -- the same population the Impact Report shows as NEEDS_ATTENTION or
     *  DESTRUCTIVE. Scoped via {@link ShadowParityProbe#scopeToOwnedBusinessTables} exactly like
     *  {@link SchemaImpactFacade}/{@link SchemaDeltaReport} so item keys line up byte-for-byte. */
    private static Set<String> unresolvedItemKeys(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        CurrentSchema current = new CurrentSchemaReader().read(dataSource);
        SchemaDiff diff = new SchemaDiffEngine().diff(DesiredSchemaFactory.fromManifest(manifest),
                ShadowParityProbe.scopeToOwnedBusinessTables(current, manifest));
        Set<String> keys = new LinkedHashSet<>();
        for (SchemaDiffItem item : diff.items()) {
            if (isUnresolvable(item.safetyClass())) {
                keys.add(item.itemKey());
            }
        }
        return keys;
    }

    private static boolean isUnresolvable(SafetyClass safetyClass) {
        return switch (safetyClass) {
            case NEEDS_BACKFILL, NEEDS_HOOK, MANUAL_REVIEW,
                    DESTRUCTIVE_DROP_COLUMN, DESTRUCTIVE_DROP_TABLE, DESTRUCTIVE_NARROW_TYPE -> true;
            default -> false;
        };
    }

    /** Loads every {@code classpath*:db/conversion-hooks/*&#47;hook.json} -- empty when an app declares
     *  none (the normal case). A genuine IO failure degrades to "no hooks" with a log line rather than
     *  failing the boot; hooks are an optional convenience, never a hard dependency of the migration
     *  path. */
    private static List<Hook> loadHooks() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/conversion-hooks/*/hook.json");
            List<Hook> hooks = new ArrayList<>();
            for (Resource resource : resources) {
                hooks.add(parseHook(resource));
            }
            return hooks;
        } catch (IOException exception) {
            System.out.println("NPDev schema lifecycle: failed listing conversion hooks (continuing with none): "
                    + exception.getMessage());
            return List.of();
        }
    }

    private static Hook parseHook(Resource hookJsonResource) throws IOException {
        JsonNode root;
        try (InputStream stream = hookJsonResource.getInputStream()) {
            root = OBJECT_MAPPER.readTree(stream);
        }
        String id = root.path("id").asText("");
        List<String> claims = new ArrayList<>();
        for (JsonNode claim : root.path("claims")) {
            claims.add(claim.asText());
        }
        String verifySql = root.hasNonNull("verifySql") ? root.path("verifySql").asText() : null;
        int verifyExpect = root.path("verifyExpect").asInt(0);
        if (root.hasNonNull("verify")) {
            // STOR-29 (B10 lift, ALL_HITTABLE_LIFT_PLAN_2026-09-05.md package P6): the declarative
            // form wins over any verifySql/verifyExpect also present on the same hook -- compiled
            // HERE, once, into the exact same fields the rest of this class (transaction/rollback/
            // journaling) already knows how to run, so nothing downstream needs to know a hook was
            // authored declaratively at all. ConversionHookEmitter already schema-validates `where`
            // at generation time (a real npdev generate cannot emit a hook.json this fails on), but
            // loadHooks()/loadClaimIndex() both document a "never throws" contract for a ControlPanel
            // read-only caller -- degrade to no-verify with a loud log line rather than break that,
            // in the unlikely event a hook.json was hand-placed past generation.
            try {
                CompiledVerify compiled = compileDeclarativeVerify(root.path("verify"), id);
                verifySql = compiled.sql();
                verifyExpect = compiled.expect();
            } catch (IllegalArgumentException malformed) {
                System.out.println("NPDev conversion hook '" + id
                        + "': malformed declarative verify, ignoring (no verification will run for this hook): "
                        + malformed.getMessage());
            }
        }
        String commonSql = readSiblingIfPresent(hookJsonResource, "convert.sql");
        String h2Sql = readSiblingIfPresent(hookJsonResource, "convert.h2.sql");
        String postgresSql = readSiblingIfPresent(hookJsonResource, "convert.postgres.sql");
        // B1 (REAL_LIFT_PLAN_2026-09-03, B13): javaHook is a sibling alternative to convert.sql --
        // ConversionHookJavaHookEmitter (generator) never writes both for the same hook.
        JsonNode javaHookNode = root.path("javaHook");
        String javaHookClass = javaHookNode.hasNonNull("class") ? javaHookNode.path("class").asText() : null;
        String javaHookMethod = javaHookNode.hasNonNull("method") ? javaHookNode.path("method").asText() : null;
        return new Hook(id, List.copyOf(claims), verifySql, verifyExpect, commonSql, h2Sql, postgresSql,
                javaHookClass, javaHookMethod);
    }

    private static final java.util.regex.Pattern DECLARATIVE_VERIFY_WHERE_PATTERN =
            java.util.regex.Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s+IS\\s+(NOT\\s+)?NULL$");

    private record CompiledVerify(String sql, int expect) {
    }

    /**
     * STOR-29 (B10 lift): compiles a hook.json's declarative {@code verify: {concept, where,
     * expect}} into the equivalent {@code verifySql}/{@code verifyExpect} pair, via {@link
     * com.npdev.kernel.storage.sql.SqlDialect#countWhereNullSql} in the dialect package (trap 3) --
     * even though this particular fragment renders identically on every engine today, the SAME
     * place a future per-engine divergence would need to change. {@code where} is restricted to a
     * single portable null-check (the schema's own {@code pattern} constraint already rejects
     * anything else at generation/authoring time); this is the runtime's own defensive re-check,
     * since a hook.json can be hand-edited after generation.
     */
    private static CompiledVerify compileDeclarativeVerify(JsonNode verifyNode, String hookId) {
        String concept = verifyNode.path("concept").asText("");
        String where = verifyNode.path("where").asText("");
        int expect = verifyNode.path("expect").asInt(0);
        if (concept.isBlank()) {
            throw new IllegalArgumentException("hook '" + hookId + "': verify.concept is required");
        }
        java.util.regex.Matcher matcher = DECLARATIVE_VERIFY_WHERE_PATTERN.matcher(where.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("hook '" + hookId + "': verify.where '" + where
                    + "' is not a portable null-check -- expected '<column> IS NULL' or '<column> IS NOT NULL'");
        }
        String column = matcher.group(1);
        boolean isNull = matcher.group(2) == null;
        String sql = SqlDialects.active().countWhereNullSql(
                SchemaLifecycleExecutor.quotedIdentifier(concept), SchemaLifecycleExecutor.quotedIdentifier(column), isNull);
        return new CompiledVerify(sql, expect);
    }

    private static String readSiblingIfPresent(Resource base, String name) {
        try {
            Resource sibling = base.createRelative(name);
            if (!sibling.exists()) {
                return null;
            }
            try (InputStream stream = sibling.getInputStream()) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            return null;
        }
    }

    /** SER closure-plan G7: prefer the manifest's declared engine (the same source of truth the rest of
     *  the executor uses) over probing the live JDBC connection, falling back to the JDBC probe only
     *  when the manifest is absent or blank (e.g. direct unit tests that hand-build a manifest without
     *  bothering to set it). Two independent engine-detection paths reading the same DataSource were
     *  harmless today but drift-prone. */
    private static String detectEngine(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        String declared = manifest == null ? null : manifest.engine();
        if (declared != null && !declared.isBlank()) {
            return declared.toLowerCase(Locale.ROOT).contains("postgres") ? "postgres" : "h2";
        }
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("postgres") ? "postgres" : "h2";
        } catch (SQLException exception) {
            return "h2";
        }
    }

    /** The result of running a hook's convert SQL (+ optional verifySql) in one transaction.
     *  {@code committed} is true only when there was no verifySql or it matched {@code verifyExpect};
     *  on a mismatch or a verifySql that itself errored, the transaction was rolled back
     *  ({@code committed=false}) and nothing persisted. {@code verifyError} is non-null only when the
     *  verifySql failed to execute. */
    /**
     * What a rollback here actually undid, in words that are true on THIS engine.
     *
     * <p>A conversion hook's convert SQL contains DDL ({@code ALTER TABLE ... ADD COLUMN}, emitted by
     * ConversionHookEmitter). This class runs convert + verify in one transaction and rolls back on a
     * verify failure, and every refusal below used to say "nothing persisted".
     *
     * <p><b>That sentence is false on any engine that commits implicitly on DDL</b> -- which is H2
     * today (boundary B11) and MySQL tomorrow. There, the ALTER already committed, and it took any
     * DML executed before it along with it. The rollback still undoes DML issued AFTER the last DDL
     * statement, so it is not a no-op; it is just not what the message claimed.
     *
     * <p>Refusing to run hooks on such an engine would break every H2 app that uses them today and
     * would be a far larger change than the defect warrants. What must not survive is the platform
     * telling an operator the database is untouched when it is not: a false all-clear is what turns
     * a recoverable half-migration into one nobody goes looking for. So the behaviour is unchanged
     * and the SENTENCE is corrected -- the X0 rule applied to a message rather than to a code path.
     *
     * <p><b>The body moved to {@link PartialApplicationTruth#afterRollback()}</b>
     * (storage/FULL_SUPPORT_PLAN.md W3). STOR-2 corrected this one call site, which left the NEXT one
     * free to make the same mistake -- and {@code SchemaHistoryStore.recordStepPass} turned out to be
     * exactly that next one, saying nothing at all about what a half-finished pass had already
     * committed. The sentence now lives in one place, derived from the capability rather than from
     * what an author assumed, and {@code check-rollback-claims.py} fails the gate on a
     * storage-surface message that claims a rollback without going through it.
     */
    private static String rollbackTruth() {
        return PartialApplicationTruth.afterRollback();
    }

    private record HookOutcome(boolean committed, boolean verifyRan, long verifyActual, String verifyError) {
    }

    /** STOR-34 (boundary B12, POSTURAL_LIFT_PLAN_2026-09-07.md package P6): the per-hook executor --
     *  the pre-P6 loop, moved out of {@link #runInternal} verbatim (byte-identical in effect is the
     *  contract; {@code ConversionHookRunnerH2Test} is the proof). Each selected hook runs in its OWN
     *  transaction ({@link #executeAndVerify}) as it always did. STOR-34 step 6 adds one honesty
     *  improvement: when a hook fails AFTER earlier hooks committed, every refusal message now names
     *  the hooks already applied ({@link #alreadyCommittedPhrase}) -- the pre-P6 messages are
     *  untouched wherever nothing had committed yet. */
    private static List<Hook> runPerHookLoop(DataSource dataSource, String migrationId,
            HistoryWriter historyWriter, List<Hook> selected, String engine,
            JavaHookRuntimeContext javaHookContext, ResolvedMixedDdlVerify mixedDdl) {
        List<Hook> applied = new ArrayList<>();
        for (Hook hook : selected) {
            historyWriter.write(historyLabel(hook), "HOOK_STARTED", List.of("claims=" + hook.claims()));

            String sql;
            boolean usedPhaseSplit = false;
            String sqlHash;
            // B1 (REAL_LIFT_PLAN_2026-09-03, B13): javaHook is a sibling alternative to the SQL path
            // below -- it has no convert SQL at all (ConversionHookRunner dispatches it through the
            // isolated plugin pool instead), so none of the mixed-DDL/phase-split machinery below,
            // which exists entirely to reason about hand-written SQL, applies to it.
            if (hook.javaHookClass() != null) {
                sql = null;
                sqlHash = "javaHook:" + hook.javaHookClass() + "#" + hook.javaHookMethod();
            } else {
                sql = hook.sqlFor(engine);
                if (sql == null || sql.isBlank()) {
                    historyWriter.write(historyLabel(hook), "HOOK_FAILED",
                            List.of("no convert SQL available for engine '" + engine + "'"));
                    throw new IllegalStateException("Conversion hook '" + hook.id()
                            + "' has no convert SQL for engine '" + engine + "' -- refusing the boot."
                            + alreadyCommittedPhrase(applied));
                }

                // SER closure-plan G6, widened by B11.1 (boundaries-2026-08-12 plan) and package 3.4
                // (BOUNDARY_LIFT_PLAN_2026-09-02.md, B11): a detection guard for the implicit-commit-on-DDL
                // caveat -- act AT THE MOMENT it matters, when a hook actually mixes DDL with a verifySql on
                // an engine where a verify failure will NOT roll the DDL back (docs/ACCEPTED_BOUNDARIES.md
                // B11), rather than only in a javadoc an operator may never read. Asks the dialect (STOR-2's
                // own precedent, via PartialApplicationTruth) instead of hardcoding "h2" -- MySQL commits
                // implicitly on DDL too, and the OLD "h2".equals(engine) check would have missed it while
                // ALSO firing wrongly for SQL Server (detectEngine's own two-value "postgres"/"h2" fold
                // collapses every non-Postgres engine to "h2" for SQL-variant selection, which is fine for
                // that purpose but was never a correct signal for THIS check).
                if (!SqlDialects.active().supports(StorageCapability.DDL_IN_TRANSACTION)
                        && hook.verifySql() != null && !hook.verifySql().isBlank()
                        && MIXES_DDL_PATTERN.matcher(sql).matches()) {
                    String activeEngineName = SqlDialects.active().name();
                    MixedDdlVerifyMode mode = mixedDdl.mode();
                    if (mode == MixedDdlVerifyMode.SPLIT) {
                        usedPhaseSplit = runSplitPhases(dataSource, migrationId, historyWriter, hook, sql,
                                activeEngineName, mixedDdl);
                    } else if (mode == MixedDdlVerifyMode.REFUSE) {
                        // Refused BEFORE executeAndVerify runs -- the mixed state (DDL already committed,
                        // DML rolled back) is never authored into existence at all, not merely warned about
                        // after the fact. The auto-split suggestion names a concrete two-hook shape rather
                        // than attempting to split the SQL automatically (rejecting a DDL-journal-style
                        // "clever" fix for the same reason the plan rejects one platform-wide: executing
                        // generated DDL at the moment state is least certain is the higher-risk move, not
                        // the safer one).
                        historyWriter.write(historyLabel(hook), "HOOK_FAILED",
                                List.of("B11:mixed_ddl_verify_refused:" + hook.id() + " on " + activeEngineName));
                        throw new IllegalStateException("B11:mixed_ddl_verify_refused: conversion hook '" + hook.id()
                                + "' mixes DDL with a verifySql on '" + activeEngineName + "'. That engine COMMITS "
                                + "IMPLICITLY ON DDL, so if the verify failed the DDL would NOT be rolled back "
                                + "(data changes made after it would be) -- refused before running, rather than "
                                + "risking that half-applied state. Split it into two hooks that run in the "
                                + "existing ascending-id order, each in its own transaction (rule 3): one with the "
                                + "DDL alone and no verifySql (e.g. id '" + hook.id() + "-1-ddl'), one with the "
                                + "data movement and this verifySql (e.g. id '" + hook.id() + "-2-verify'). "
                                + "-D" + MIXED_DDL_VERIFY_PROPERTY + "=warn restores the previous behaviour "
                                + "byte-for-byte. Run `npdev why B11` for the full explanation."
                                + alreadyCommittedPhrase(applied));
                    } else {
                        System.out.println("NPDev schema lifecycle: WARNING -- conversion hook '" + hook.id()
                                + "' mixes DDL with a verifySql on '" + activeEngineName + "'. That engine COMMITS "
                                + "IMPLICITLY ON DDL, so if the verify fails the DDL will NOT be rolled back (data "
                                + "changes made after it will be). Split destructive DDL and data movement into "
                                + "separate hooks/boots, or run this conversion on an engine with transactional DDL "
                                + "(Postgres, SQL Server). Leave the property unset -- split is the default and now "
                                + "does the decomposition automatically, resuming safely on a crash. Set -D"
                                + MIXED_DDL_VERIFY_PROPERTY + "=refuse to refuse this shape outright instead of only "
                                + "warning, or =warn to restore the previous behavior byte-for-byte. Run `npdev why "
                                + "B11` for the full explanation.");
                    }
                }

                sqlHash = sha256Hex(sql);
            }

            // A1 (REAL_LIFT_PLAN_2026-09-03): a phase-split hook already ran every statement, each its
            // own journaled, resumable phase (ConversionHookPhaseRunner) -- only the closing verify
            // still needs to run, on its own read-only connection. Everything below this branch treats
            // "outcome" identically either way; only how it was produced differs.
            HookOutcome outcome;
            if (hook.javaHookClass() != null) {
                JavaMigrationHookRunner.run(dataSource, migrationId, hook, javaHookContext);
                try {
                    outcome = verifyOnly(dataSource, hook.verifySql(), hook.verifyExpect());
                } catch (SQLException exception) {
                    historyWriter.write(historyLabel(hook), "HOOK_FAILED",
                            List.of("sqlHash=" + sqlHash, "error=" + exception.getMessage()));
                    throw new IllegalStateException("Conversion hook '" + hook.id()
                            + "' ran its javaHook but its closing verifySql failed to run: "
                            + exception.getMessage() + " -- refusing the boot. Batches the hook already wrote "
                            + "remain applied (each batch commits together with its own journal row, A1's same "
                            + "atomicity argument as a DML phase); the next boot resumes from the journal and "
                            + "re-checks the verify." + alreadyCommittedPhrase(applied), exception);
                }
            } else if (usedPhaseSplit) {
                try {
                    outcome = verifyOnly(dataSource, hook.verifySql(), hook.verifyExpect());
                } catch (SQLException exception) {
                    historyWriter.write(historyLabel(hook), "HOOK_FAILED",
                            List.of("sqlHash=" + sqlHash, "error=" + exception.getMessage()));
                    throw new IllegalStateException("Conversion hook '" + hook.id()
                            + "' ran every journaled phase but its closing verifySql failed to run: "
                            + exception.getMessage() + " -- refusing the boot. The phases already applied "
                            + "remain applied (B11: no rollback on an implicit-commit engine); the next boot "
                            + "resumes from the journal and re-checks the verify." + alreadyCommittedPhrase(applied),
                            exception);
                }
            } else {
                // SER-P7 (finding #1 fix): run the convert SQL AND its verifySql in ONE transaction, so a
                // verify mismatch (or a verifySql that errors) rolls the WHOLE hook back -- nothing persists.
                // Previously the convert SQL committed first and verify ran on a separate connection, so a
                // failing verify aborted the boot but the hook's (possibly destructive) changes stayed
                // committed and a re-boot silently proceeded.
                //
                // "Nothing persisted" is literally true only on an engine that keeps DDL inside the
                // transaction. H2 does not (boundary B11) and MySQL does not, so what the refusal says
                // now comes from rollbackTruth() rather than from this assumption. See its javadoc.
                try {
                    outcome = executeAndVerify(dataSource, sql, hook.verifySql(), hook.verifyExpect());
                } catch (SQLException exception) {
                    historyWriter.write(historyLabel(hook), "HOOK_FAILED",
                            List.of("sqlHash=" + sqlHash, "error=" + exception.getMessage()));
                    throw new IllegalStateException("Conversion hook '" + hook.id()
                            + "' failed executing its convert SQL (" + rollbackTruth() + "): "
                            + exception.getMessage() + " -- refusing the boot." + alreadyCommittedPhrase(applied),
                            exception);
                }
            }

            if (outcome.verifyRan() && outcome.verifyError() != null) {
                historyWriter.write(historyLabel(hook), "HOOK_VERIFY_FAILED",
                        List.of("verifySql failed to run: " + outcome.verifyError()));
                throw new IllegalStateException("Conversion hook '" + hook.id()
                        + "' verifySql failed to run: " + outcome.verifyError()
                        + " -- refusing the boot (" + rollbackTruth() + ")." + alreadyCommittedPhrase(applied));
            }
            if (outcome.verifyRan() && !outcome.committed()) {
                historyWriter.write(historyLabel(hook), "HOOK_VERIFY_FAILED",
                        List.of("expected=" + hook.verifyExpect(), "actual=" + outcome.verifyActual()));
                throw new IllegalStateException("Conversion hook '" + hook.id()
                        + "' verification failed: expected " + hook.verifyExpect() + " but got " + outcome.verifyActual()
                        + " -- refusing the boot (" + rollbackTruth() + ")." + alreadyCommittedPhrase(applied));
            }
            if (outcome.verifyRan()) {
                historyWriter.write(historyLabel(hook), "HOOK_VERIFIED",
                        List.of("expected=" + hook.verifyExpect(), "actual=" + outcome.verifyActual()));
            }

            historyWriter.write(historyLabel(hook), "HOOK_APPLIED",
                    List.of("claims=" + hook.claims(), "sqlHash=" + sqlHash));
            applied.add(hook);
        }
        return applied;
    }

    /** STOR-34 (boundary B12, package P6): the per-hook honesty phrase -- names the hooks that already
     *  committed when a later hook fails (their changes stay; per-hook is individually atomic by
     *  design). Empty string for a first-hook failure, so every pre-P6 message reads byte-identically
     *  where nothing had committed yet. */
    private static String alreadyCommittedPhrase(List<Hook> applied) {
        if (applied.isEmpty()) {
            return "";
        }
        return " Earlier hook(s) in this boot already committed and remain applied: "
                + applied.stream().map(Hook::id).toList() + ".";
    }

    /** STOR-34 (boundary B12, package P6): the collective executor -- every selected hook's convert +
     *  verify on ONE connection with autocommit off, a SINGLE commit at the end, and any failure
     *  (convert SQL error, verify mismatch, verify-SQL error, or the commit itself) rolls the whole
     *  set back and refuses with {@code B12:collective_rollback:} naming the failing hook and the
     *  number of hooks rolled back. Only reachable after the collective refusals upstream: the engine
     *  supports transactional DDL, split is not configured explicitly, and no selected hook is a
     *  javaHook. The history rows are written through {@code historyWriter}, which opens its OWN
     *  connection (the caller's DataSource) -- they SURVIVE the rollback on purpose: the audit trail
     *  must say what the rolled-back set attempted, or no operator could explain the refusal. That
     *  independence looks like a bug to the next reader; it is the point. */
    private static List<Hook> runCollectiveHookSet(DataSource dataSource, HistoryWriter historyWriter,
            List<Hook> selected, String engine) {
        List<Hook> applied = new ArrayList<>();
        int attempted = 0;
        String failedHookId = null;
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (Hook hook : selected) {
                    attempted++;
                    failedHookId = hook.id();
                    historyWriter.write(historyLabel(hook), "HOOK_STARTED", List.of("claims=" + hook.claims()));
                    String sql = hook.sqlFor(engine);
                    if (sql == null || sql.isBlank()) {
                        historyWriter.write(historyLabel(hook), "HOOK_FAILED",
                                List.of("no convert SQL available for engine '" + engine + "'"));
                        throw new IllegalStateException("Conversion hook '" + hook.id()
                                + "' has no convert SQL for engine '" + engine + "' -- refusing the boot.");
                    }
                    String sqlHash = sha256Hex(sql);
                    HookOutcome outcome;
                    try {
                        outcome = executeAndVerifyOn(connection, sql, hook.verifySql(), hook.verifyExpect());
                    } catch (SQLException exception) {
                        historyWriter.write(historyLabel(hook), "HOOK_FAILED",
                                List.of("sqlHash=" + sqlHash, "error=" + exception.getMessage()));
                        throw new IllegalStateException("Conversion hook '" + hook.id()
                                + "' failed executing its convert SQL: " + exception.getMessage()
                                + " -- refusing the boot.", exception);
                    }
                    if (outcome.verifyRan() && outcome.verifyError() != null) {
                        historyWriter.write(historyLabel(hook), "HOOK_VERIFY_FAILED",
                                List.of("verifySql failed to run: " + outcome.verifyError()));
                        throw new IllegalStateException("Conversion hook '" + hook.id()
                                + "' verifySql failed to run: " + outcome.verifyError()
                                + " -- refusing the boot.");
                    }
                    if (outcome.verifyRan() && !outcome.committed()) {
                        historyWriter.write(historyLabel(hook), "HOOK_VERIFY_FAILED",
                                List.of("expected=" + hook.verifyExpect(), "actual=" + outcome.verifyActual()));
                        throw new IllegalStateException("Conversion hook '" + hook.id()
                                + "' verification failed: expected " + hook.verifyExpect() + " but got "
                                + outcome.verifyActual() + " -- refusing the boot.");
                    }
                    if (outcome.verifyRan()) {
                        historyWriter.write(historyLabel(hook), "HOOK_VERIFIED",
                                List.of("expected=" + hook.verifyExpect(), "actual=" + outcome.verifyActual()));
                    }
                    historyWriter.write(historyLabel(hook), "HOOK_APPLIED",
                            List.of("claims=" + hook.claims(), "sqlHash=" + sqlHash));
                    applied.add(hook);
                }
                // STOR-34 (B12): the ONE commit. A failure here rolls back just like any hook failure.
                connection.commit();
                return applied;
            } catch (RuntimeException | SQLException failure) {
                collectRollback(connection, historyWriter, failedHookId, attempted);
                if (failure instanceof IllegalStateException refusal) {
                    throw new IllegalStateException("B12:collective_rollback: " + refusal.getMessage()
                            + " Collective set of " + attempted + " hook(s) failed (" + rollbackTruth() + ").",
                            refusal);
                }
                throw new IllegalStateException("B12:collective_rollback: the collective hook set failed: "
                        + failure.getMessage() + " -- " + attempted + " hook(s) attempted ("
                        + rollbackTruth() + ").", failure);
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // connection is being closed regardless
                }
            }
        } catch (SQLException connectionException) {
            // Opening the connection (or restoring autocommit) failed before any hook work could exist.
            throw new IllegalStateException("B12:collective_rollback: could not open the collective hook "
                    + "connection: " + connectionException.getMessage(), connectionException);
        }
    }

    /** STOR-34 (B12): roll the collective set back and record its audit row. A rollback failure must
     *  never mask the refusal itself -- it logs and lets the refusal stand (same discipline as
     *  {@link #safeRollback}). */
    private static void collectRollback(Connection connection, HistoryWriter historyWriter,
            String failedHookId, int attempted) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // the refusal still stands
        }
        historyWriter.write("CONVERSION_HOOKS", "COLLECTIVE_ROLLED_BACK",
                List.of("failedHook=" + (failedHookId == null ? "(connection-level failure)" : failedHookId),
                        "rolledBackCount=" + attempted));
    }

    /**
     * SER-P7 (finding #1 fix): execute the hook's convert SQL and, when present, its verifySql in ONE
     * transaction on ONE connection, so a verify failure rolls the entire hook back. A convert-SQL
     * failure propagates as {@link SQLException} (already rolled back). Otherwise the transaction commits
     * ONLY when there is no verifySql or the verify matched; a mismatch or a verifySql execution error
     * rolls back and returns {@code committed=false} so the caller can refuse the boot with nothing
     * persisted.
     */
    private static HookOutcome executeAndVerify(DataSource dataSource, String convertSql, String verifySql,
            int verifyExpect) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                HookOutcome outcome = executeAndVerifyOn(connection, convertSql, verifySql, verifyExpect);
                if (outcome.verifyRan() && (outcome.verifyError() != null || !outcome.committed())) {
                    // verify mismatch or verify-SQL error: the whole hook rolls back, exactly as before.
                    safeRollback(connection);
                    return outcome;
                }
                connection.commit();
                return outcome;
            } catch (SQLException convertException) {
                safeRollback(connection);
                throw convertException;
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // connection is being closed regardless
                }
            }
        }
    }

    /** STOR-34 (boundary B12, package P6): the convert+verify body of {@link #executeAndVerify} moved
     *  onto a caller-supplied connection -- it neither opens, commits nor rolls back, so the same body
     *  serves the per-hook path (the wrapper above commits or rolls back) and the collective path
     *  ({@link #runCollectiveHookSet}, which commits once at the end). The outcome keeps today's
     *  semantics: {@code committed=true} means "no verifySql, or the verify matched". A convert-SQL
     *  failure throws {@link SQLException} with the caller's transaction still open. */
    private static HookOutcome executeAndVerifyOn(Connection connection, String convertSql, String verifySql,
            int verifyExpect) throws SQLException {
        for (String statementSql : splitStatements(convertSql)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementSql);
            }
        }
        if (verifySql == null || verifySql.isBlank()) {
            return new HookOutcome(true, false, -1L, null);
        }
        long actual;
        try (PreparedStatement statement = connection.prepareStatement(verifySql);
             ResultSet resultSet = statement.executeQuery()) {
            actual = resultSet.next() ? resultSet.getLong(1) : -1L;
        } catch (SQLException verifyException) {
            return new HookOutcome(false, true, -1L, verifyException.getMessage());
        }
        if (actual != verifyExpect) {
            return new HookOutcome(false, true, actual, null);
        }
        return new HookOutcome(true, true, actual, null);
    }

    private static void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best-effort rollback; the outcome/exception already tells the caller what to do
        }
    }

    /** SER closure-plan G4: the lexical states {@link #splitStatements} tracks. Not a SQL parser --
     *  just enough to know when a {@code ;} is inside something that isn't a statement terminator. */
    private enum SplitterState {
        NORMAL, SINGLE_QUOTE, DOUBLE_QUOTE, LINE_COMMENT, BLOCK_COMMENT, DOLLAR_QUOTE
    }

    /**
     * Comment/quote-aware {@code ;}-statement splitter, a single-pass explicit state machine (SER
     * closure-plan G4 -- deliberately still no SQL parser dependency, matching the level of
     * sophistication conversion hooks are meant to need; anything fancier belongs in a future Java
     * {@code DataMigrationHook}, explicitly deferred). A {@code ;} does NOT split while inside:
     * <ul>
     *   <li>a {@code '...'} single-quoted literal (doubled {@code ''} escapes fall out correctly: each
     *       quote char still just toggles the state, and a doubled pair has no room for a real
     *       {@code ;} between the two quote chars anyway);</li>
     *   <li>a {@code "..."} double-quoted identifier;</li>
     *   <li>a {@code -- ...} line comment (ends at the next newline);</li>
     *   <li>a {@code /* ... *&#47;} block comment;</li>
     *   <li>Postgres {@code $$...$$} / {@code $tag$...$tag$} dollar-quoting.</li>
     * </ul>
     * Comment/quote text is preserved verbatim in the output (not stripped) -- the target engine
     * understands its own comment syntax fine; this only decides where NOT to split.
     *
     * <p>Package-private (not {@code private}) since A1 (REAL_LIFT_PLAN_2026-09-03):
     * {@link ConversionHookPhaseSplitter} reuses this exact lexer rather than duplicating it, so SPLIT
     * mode's statement boundaries are byte-identical to the ones {@link #splitStatements} always used
     * to execute a non-split hook.
     */
    static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        SplitterState state = SplitterState.NORMAL;
        String dollarTag = null;
        int length = sql.length();
        int i = 0;
        while (i < length) {
            char c = sql.charAt(i);
            switch (state) {
                case NORMAL -> {
                    if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                        current.append(c).append(sql.charAt(i + 1));
                        state = SplitterState.LINE_COMMENT;
                        i += 2;
                        continue;
                    }
                    if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                        current.append(c).append(sql.charAt(i + 1));
                        state = SplitterState.BLOCK_COMMENT;
                        i += 2;
                        continue;
                    }
                    if (c == '\'') {
                        current.append(c);
                        state = SplitterState.SINGLE_QUOTE;
                        i++;
                        continue;
                    }
                    if (c == '"') {
                        current.append(c);
                        state = SplitterState.DOUBLE_QUOTE;
                        i++;
                        continue;
                    }
                    if (c == '$') {
                        String tag = matchDollarQuoteStart(sql, i);
                        if (tag != null) {
                            current.append(tag);
                            dollarTag = tag;
                            state = SplitterState.DOLLAR_QUOTE;
                            i += tag.length();
                            continue;
                        }
                    }
                    if (c == ';') {
                        String statement = current.toString().trim();
                        if (!statement.isEmpty()) {
                            statements.add(statement);
                        }
                        current.setLength(0);
                        i++;
                        continue;
                    }
                    current.append(c);
                    i++;
                }
                case SINGLE_QUOTE -> {
                    current.append(c);
                    if (c == '\'') {
                        state = SplitterState.NORMAL;
                    }
                    i++;
                }
                case DOUBLE_QUOTE -> {
                    current.append(c);
                    if (c == '"') {
                        state = SplitterState.NORMAL;
                    }
                    i++;
                }
                case LINE_COMMENT -> {
                    current.append(c);
                    if (c == '\n') {
                        state = SplitterState.NORMAL;
                    }
                    i++;
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && i + 1 < length && sql.charAt(i + 1) == '/') {
                        current.append(c).append(sql.charAt(i + 1));
                        state = SplitterState.NORMAL;
                        i += 2;
                        continue;
                    }
                    current.append(c);
                    i++;
                }
                case DOLLAR_QUOTE -> {
                    if (c == '$' && sql.regionMatches(i, dollarTag, 0, dollarTag.length())) {
                        current.append(dollarTag);
                        state = SplitterState.NORMAL;
                        i += dollarTag.length();
                        continue;
                    }
                    current.append(c);
                    i++;
                }
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            statements.add(last);
        }
        return statements;
    }

    /** Detects a dollar-quote START tag at {@code sql[index]} (which must be {@code '$'}): {@code $$} or
     *  {@code $tag$} where {@code tag} matches {@code [A-Za-z_][A-Za-z0-9_]*}. Returns the full opener
     *  (e.g. {@code "$$"} or {@code "$tag$"}), or {@code null} when this isn't a valid dollar-quote
     *  opener (e.g. a bare {@code $1} positional parameter with no matching second {@code $}). */
    private static String matchDollarQuoteStart(String sql, int index) {
        int closeIndex = sql.indexOf('$', index + 1);
        if (closeIndex < 0) {
            return null;
        }
        String inner = sql.substring(index + 1, closeIndex);
        if (!inner.isEmpty() && !inner.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return null;
        }
        return sql.substring(index, closeIndex + 1);
    }

    /** Test-only seam (SER closure plan G4): {@link #splitStatements} is {@code private}. */
    static List<String> splitStatementsForTest(String sql) {
        return splitStatements(sql);
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    /** Numeric-aware string comparison so hook ids like {@code "2-x"}/{@code "10-x"} sort in the
     *  intuitive ordinal order (plain lexical sort would put {@code "10-x"} before {@code "2-x"}). */
    private static int naturalCompare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startI = i;
                int startJ = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                String numA = a.substring(startI, i).replaceFirst("^0+(?=.)", "");
                String numB = b.substring(startJ, j).replaceFirst("^0+(?=.)", "");
                if (numA.length() != numB.length()) {
                    return Integer.compare(numA.length(), numB.length());
                }
                int comparison = numA.compareTo(numB);
                if (comparison != 0) {
                    return comparison;
                }
            } else {
                if (ca != cb) {
                    return Character.compare(ca, cb);
                }
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
