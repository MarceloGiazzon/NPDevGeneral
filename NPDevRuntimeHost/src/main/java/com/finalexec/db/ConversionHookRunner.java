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
 */
public final class ConversionHookRunner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

        String engine = detectEngine(dataSource);
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
            String sqlHash = sha256Hex(sql);

            try {
                executeInOwnTransaction(dataSource, sql);
            } catch (SQLException exception) {
                historyWriter.write(historyLabel(hook), "HOOK_FAILED",
                        List.of("sqlHash=" + sqlHash, "error=" + exception.getMessage()));
                throw new IllegalStateException("Conversion hook '" + hook.id()
                        + "' failed executing its convert SQL (transaction rolled back): "
                        + exception.getMessage() + " -- refusing the boot.", exception);
            }

            if (hook.verifySql() != null && !hook.verifySql().isBlank()) {
                long actual;
                try {
                    actual = runVerify(dataSource, hook.verifySql());
                } catch (SQLException exception) {
                    historyWriter.write(historyLabel(hook), "HOOK_VERIFY_FAILED",
                            List.of("verifySql failed to run: " + exception.getMessage()));
                    throw new IllegalStateException("Conversion hook '" + hook.id()
                            + "' verifySql failed to run: " + exception.getMessage()
                            + " -- refusing the boot (nothing destructive has run yet).", exception);
                }
                if (actual != hook.verifyExpect()) {
                    historyWriter.write(historyLabel(hook), "HOOK_VERIFY_FAILED",
                            List.of("expected=" + hook.verifyExpect(), "actual=" + actual));
                    throw new IllegalStateException("Conversion hook '" + hook.id()
                            + "' verification failed: expected " + hook.verifyExpect() + " but got " + actual
                            + " -- refusing the boot (nothing destructive has run yet).");
                }
                historyWriter.write(historyLabel(hook), "HOOK_VERIFIED",
                        List.of("expected=" + hook.verifyExpect(), "actual=" + actual));
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

    private static String detectEngine(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("postgres") ? "postgres" : "h2";
        } catch (SQLException exception) {
            return "h2";
        }
    }

    private static void executeInOwnTransaction(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (String statementSql : splitStatements(sql)) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(statementSql);
                    }
                }
                connection.commit();
            } catch (SQLException exception) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // best-effort rollback; the original exception is what matters
                }
                throw exception;
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // connection is being closed regardless
                }
            }
        }
    }

    private static long runVerify(DataSource dataSource, String verifySql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(verifySql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : -1L;
        }
    }

    /** Naive but string-literal-aware {@code ;}-statement splitter: a semicolon inside a single-quoted
     *  literal does not split. No support for {@code --}/{@code /* *&#47;} comments -- keep hook SQL
     *  simple (this mirrors the level of sophistication conversion hooks are meant to need; anything
     *  fancier belongs in a future Java {@code DataMigrationHook}, explicitly deferred). */
    private static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inSingleQuote = !inSingleQuote;
            }
            if (c == ';' && !inSingleQuote) {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            statements.add(last);
        }
        return statements;
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
