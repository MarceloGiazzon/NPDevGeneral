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

    private ConversionHookRunner() {
    }

    /** Callback for {@code npdev_schema_history} rows. {@code SchemaLifecycleExecutor}'s own
     *  history-write helpers are {@code private}, so it supplies this as a lambda defined inside its
     *  own compilation unit (where that private access is legal) instead of this class reaching in. */
    @FunctionalInterface
    public interface HistoryWriter {
        void write(String label, String outcome, List<String> detailLines);
    }

    private record Hook(String id, List<String> claims, String verifySql, int verifyExpect,
            String commonSql, String h2Sql, String postgresSql) {
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
        Set<String> unresolvedKeys = unresolvedItemKeys(dataSource, manifest);
        if (unresolvedKeys.isEmpty()) {
            return false;
        }

        List<Hook> selected = new ArrayList<>();
        for (Hook hook : loadHooks()) {
            boolean matches = hook.claims().stream().anyMatch(unresolvedKeys::contains);
            if (matches) {
                selected.add(hook);
            } else {
                System.out.println("NPDev schema lifecycle: conversion hook '" + hook.id()
                        + "' claims nothing in the current unresolved diff; skipping (a stale hook is not "
                        + "an error -- the diff may already be converged).");
            }
        }
        if (selected.isEmpty()) {
            return false;
        }
        selected.sort(Comparator.comparing(Hook::id, ConversionHookRunner::naturalCompare));

        // SER closure-plan G5: hooks are individually atomic, not collectively atomic (rule 3) -- each
        // runs in its own transaction, so a later hook failing does NOT roll back an earlier one that
        // already committed. Operators need to know this so they write idempotent convert.sql (a later
        // boot re-runs only what the diff still says is unresolved, which may re-select an already-
        // partially-applied hook). See docs/IMPACT_REPORTS.md's conversion-hooks refusal list.
        if (selected.size() > 1) {
            System.out.println("NPDev schema lifecycle: running " + selected.size() + " conversion hooks "
                    + "in separate transactions -- each hook must be idempotent (a later hook failing does "
                    + "not roll back an earlier one).");
        }

        String engine = detectEngine(dataSource, manifest);
        List<Hook> applied = new ArrayList<>();
        for (Hook hook : selected) {
            historyWriter.write(historyLabel(hook), "HOOK_STARTED", List.of("claims=" + hook.claims()));

            String sql = hook.sqlFor(engine);
            if (sql == null || sql.isBlank()) {
                historyWriter.write(historyLabel(hook), "HOOK_FAILED",
                        List.of("no convert SQL available for engine '" + engine + "'"));
                throw new IllegalStateException("Conversion hook '" + hook.id()
                        + "' has no convert SQL for engine '" + engine + "' -- refusing the boot.");
            }

            // SER closure-plan G6, widened by B11.1 (boundaries-2026-08-12 plan): a detection guard for
            // the implicit-commit-on-DDL caveat -- warn AT THE MOMENT it matters, when a hook actually
            // mixes DDL with a verifySql on an engine where a verify failure will NOT roll the DDL back
            // (docs/ACCEPTED_BOUNDARIES.md B11), rather than only in a javadoc an operator may never
            // read. Asks the dialect (STOR-2's own precedent, via PartialApplicationTruth) instead of
            // hardcoding "h2" -- MySQL commits implicitly on DDL too, and the OLD "h2".equals(engine)
            // check would have missed it while ALSO firing wrongly for SQL Server (detectEngine's own
            // two-value "postgres"/"h2" fold collapses every non-Postgres engine to "h2" for SQL-variant
            // selection, which is fine for that purpose but was never a correct signal for THIS warning).
            if (!SqlDialects.active().supports(StorageCapability.DDL_IN_TRANSACTION)
                    && hook.verifySql() != null && !hook.verifySql().isBlank()
                    && MIXES_DDL_PATTERN.matcher(sql).matches()) {
                String activeEngineName = SqlDialects.active().name();
                System.out.println("NPDev schema lifecycle: WARNING -- conversion hook '" + hook.id()
                        + "' mixes DDL with a verifySql on '" + activeEngineName + "'. That engine COMMITS "
                        + "IMPLICITLY ON DDL, so if the verify fails the DDL will NOT be rolled back (data "
                        + "changes made after it will be). Split destructive DDL and data movement into "
                        + "separate hooks/boots, or run this conversion on an engine with transactional DDL "
                        + "(Postgres, SQL Server). Run `npdev why B11` for the full explanation.");
            }

            String sqlHash = sha256Hex(sql);

            // SER-P7 (finding #1 fix): run the convert SQL AND its verifySql in ONE transaction, so a
            // verify mismatch (or a verifySql that errors) rolls the WHOLE hook back -- nothing persists.
            // Previously the convert SQL committed first and verify ran on a separate connection, so a
            // failing verify aborted the boot but the hook's (possibly destructive) changes stayed
            // committed and a re-boot silently proceeded.
            //
            // "Nothing persisted" is literally true only on an engine that keeps DDL inside the
            // transaction. H2 does not (boundary B11) and MySQL does not, so what the refusal says
            // now comes from rollbackTruth() rather than from this assumption. See its javadoc.
            HookOutcome outcome;
            try {
                outcome = executeAndVerify(dataSource, sql, hook.verifySql(), hook.verifyExpect());
            } catch (SQLException exception) {
                historyWriter.write(historyLabel(hook), "HOOK_FAILED",
                        List.of("sqlHash=" + sqlHash, "error=" + exception.getMessage()));
                throw new IllegalStateException("Conversion hook '" + hook.id()
                        + "' failed executing its convert SQL (" + rollbackTruth() + "): "
                        + exception.getMessage() + " -- refusing the boot.", exception);
            }

            if (outcome.verifyRan() && outcome.verifyError() != null) {
                historyWriter.write(historyLabel(hook), "HOOK_VERIFY_FAILED",
                        List.of("verifySql failed to run: " + outcome.verifyError()));
                throw new IllegalStateException("Conversion hook '" + hook.id()
                        + "' verifySql failed to run: " + outcome.verifyError()
                        + " -- refusing the boot (" + rollbackTruth() + ").");
            }
            if (outcome.verifyRan() && !outcome.committed()) {
                historyWriter.write(historyLabel(hook), "HOOK_VERIFY_FAILED",
                        List.of("expected=" + hook.verifyExpect(), "actual=" + outcome.verifyActual()));
                throw new IllegalStateException("Conversion hook '" + hook.id()
                        + "' verification failed: expected " + hook.verifyExpect() + " but got " + outcome.verifyActual()
                        + " -- refusing the boot (" + rollbackTruth() + ").");
            }
            if (outcome.verifyRan()) {
                historyWriter.write(historyLabel(hook), "HOOK_VERIFIED",
                        List.of("expected=" + hook.verifyExpect(), "actual=" + outcome.verifyActual()));
            }

            historyWriter.write(historyLabel(hook), "HOOK_APPLIED",
                    List.of("claims=" + hook.claims(), "sqlHash=" + sqlHash));
            applied.add(hook);
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
        return true;
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
        String commonSql = readSiblingIfPresent(hookJsonResource, "convert.sql");
        String h2Sql = readSiblingIfPresent(hookJsonResource, "convert.h2.sql");
        String postgresSql = readSiblingIfPresent(hookJsonResource, "convert.postgres.sql");
        return new Hook(id, List.copyOf(claims), verifySql, verifyExpect, commonSql, h2Sql, postgresSql);
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
                for (String statementSql : splitStatements(convertSql)) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(statementSql);
                    }
                }
                if (verifySql == null || verifySql.isBlank()) {
                    connection.commit();
                    return new HookOutcome(true, false, -1L, null);
                }
                long actual;
                try (PreparedStatement statement = connection.prepareStatement(verifySql);
                     ResultSet resultSet = statement.executeQuery()) {
                    actual = resultSet.next() ? resultSet.getLong(1) : -1L;
                } catch (SQLException verifyException) {
                    safeRollback(connection);
                    return new HookOutcome(false, true, -1L, verifyException.getMessage());
                }
                if (actual != verifyExpect) {
                    safeRollback(connection);
                    return new HookOutcome(false, true, actual, null);
                }
                connection.commit();
                return new HookOutcome(true, true, actual, null);
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
     */
    private static List<String> splitStatements(String sql) {
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
