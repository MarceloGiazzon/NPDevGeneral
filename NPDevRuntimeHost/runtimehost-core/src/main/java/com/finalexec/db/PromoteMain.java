package com.finalexec.db;

import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * {@code npdev db promote} -- B10, boundary-lift 2026-09-02 package 3.3: the ONE-COMMAND arc the
 * plan's own text asks for ("realize the target schema, preview, apply, verify") on top of
 * {@link CrossEngineDataPromotion} (data movement, already built, Move 9 A4) and
 * {@link PromotionVerifier} (deep verification, this package). Follows {@link ReverseMigrateMain}'s
 * exact no-Spring shape -- plain {@code UrlDataSource}s, {@code SchemaLifecycleExecutor.loadManifest}
 * off the classpath -- with ONE deliberate exception: the schema-realization step below constructs a
 * real {@link Flyway} instance and calls the package-private
 * {@link SchemaLifecycleExecutor#migrate(Flyway, SchemaLifecycleExecutor.SchemaManifest)} directly,
 * because that IS package 3.2's MIGRATE_ONLY mode's underlying mechanism -- MIGRATE_ONLY is "run this
 * method, then exit before serving," and this process never serves anything, so calling the method
 * directly reaches the identical DDL with no subprocess, no port, no Spring context to boot.
 *
 * <h2>Why the target's dialect is derived from its CONNECTION, not the manifest</h2>
 *
 * <p>{@code SchemaLifecycleExecutor.migrate}'s {@code pinDialectFromManifest} trusts
 * {@code manifest.engine()} unconditionally -- correct for a normal boot (the manifest IS that app's
 * own configured engine) and WRONG here: this manifest describes the SOURCE app, and promoting to a
 * genuinely different target engine (H2 source, Postgres target) would pin the source's dialect
 * against the target connection, generating the wrong DDL guard syntax. {@link
 * SchemaLifecycleExecutor.SchemaManifest#withEngine} exists for exactly this call site -- see its own
 * javadoc for the full reasoning. Every OTHER step ({@link CrossEngineDataPromotion},
 * {@link PromotionVerifier}) keeps {@code SqlDialects.active()} pinned to the SOURCE's dialect
 * throughout, matching the identical assumption the existing {@code /promote/apply} REST endpoint
 * already ships under (a running app's own home dialect, active for its whole process) -- safe in
 * practice because {@code safeIdentifier} restricts every identifier this platform generates to plain
 * lowercase ASCII, which quotes identically (or needs no quoting at all) under every engine's dialect.
 *
 * <h2>{@code --dry-run}</h2>
 *
 * <p>Writes NOTHING to the target -- no schema realization, no data copy -- even if the target has no
 * schema yet. Runs {@link CrossEngineDataPromotion#preview} only, which already tolerates a missing
 * target table (reports it as zero rows), so a dry run against a genuinely empty target still shows
 * what the source side has to offer.
 *
 * <h2>Exit codes</h2>
 * <ul>
 *   <li>{@code 0} -- dry-run preview printed cleanly, or a real run ended {@code PROMOTION VERIFIED}.</li>
 *   <li>{@code 1} -- a real run completed but something needs attention: a copy failure, a row-count
 *       mismatch, or a verification mismatch -- itemized on stdout, never buried in a stack trace.</li>
 *   <li>{@code 2} -- could not determine (bad arguments, no manifest, could not connect to either
 *       side, or schema realization itself failed).</li>
 * </ul>
 */
public final class PromoteMain {

    static final int EXIT_OK = 0;
    static final int EXIT_NEEDS_ATTENTION = 1;
    static final int EXIT_COULD_NOT_DETERMINE = 2;

    private PromoteMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        String url = null;
        String user = null;
        String password = null;
        String toUrl = null;
        String toUser = null;
        String toPassword = null;
        boolean dryRun = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--url".equals(arg) && i + 1 < args.length) {
                url = args[++i];
            } else if ("--user".equals(arg) && i + 1 < args.length) {
                user = args[++i];
            } else if ("--password".equals(arg) && i + 1 < args.length) {
                password = args[++i];
            } else if ("--to".equals(arg) && i + 1 < args.length) {
                toUrl = args[++i];
            } else if ("--to-user".equals(arg) && i + 1 < args.length) {
                toUser = args[++i];
            } else if ("--to-password".equals(arg) && i + 1 < args.length) {
                toPassword = args[++i];
            } else if ("--dry-run".equals(arg)) {
                dryRun = true;
            } else {
                err.println("npdev db promote: unrecognized or incomplete argument '" + arg + "'");
                printUsage(err);
                return EXIT_COULD_NOT_DETERMINE;
            }
        }
        if (url == null || url.isBlank() || toUrl == null || toUrl.isBlank()) {
            printUsage(err);
            return EXIT_COULD_NOT_DETERMINE;
        }

        SchemaLifecycleExecutor.SchemaManifest manifest;
        try {
            manifest = SchemaLifecycleExecutor.loadManifest();
        } catch (RuntimeException failure) {
            err.println("npdev db promote: could not read the schema-realization manifest: " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }
        if (manifest == null) {
            err.println("npdev db promote: no schema-realization-manifest.json found on the classpath. "
                    + "Build this app at least once (npdev generate/run app) before promoting its database.");
            return EXIT_COULD_NOT_DETERMINE;
        }
        if (!manifest.physicalDatabase()) {
            out.println("npdev db promote: this app has no physical database (InMemory storage) -- nothing to promote.");
            return EXIT_OK;
        }

        DataSource source = new UrlDataSource(url, user, password);
        SqlDialect sourceDialect;
        try (Connection probe = source.getConnection()) {
            // SqlDialects.forConnection, not .active(): this process has no app configuration to read
            // the engine from -- same reasoning as every sibling *Main class.
            sourceDialect = SqlDialects.forConnection(probe);
            SqlDialects.setActive(sourceDialect);
        } catch (SQLException failure) {
            err.println("npdev db promote: could not connect to source " + url + ": " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }

        DataSource target = new UrlDataSource(toUrl, toUser, toPassword);
        SqlDialect targetDialect;
        try (Connection probe = target.getConnection()) {
            targetDialect = SqlDialects.forConnection(probe);
        } catch (SQLException failure) {
            err.println("npdev db promote: could not connect to target " + toUrl + ": " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }

        if (dryRun) {
            return runDryRun(source, target, manifest, out);
        }
        return runPromotion(source, target, manifest, sourceDialect, targetDialect, out, err);
    }

    /** Package-private for direct unit testing (see {@link #runAfterSchemaRealized}'s own javadoc for
     *  why): no {@code SchemaLifecycleExecutor.loadManifest} dependency, so this needs no real
     *  generated app on the classpath. */
    static int runDryRun(
            DataSource source, DataSource target, SchemaLifecycleExecutor.SchemaManifest manifest, PrintStream out) {
        out.println("npdev db promote: --dry-run -- writing nothing to the target. Preview:");
        CrossEngineDataPromotion.Preview preview = CrossEngineDataPromotion.preview(source, target, manifest);
        printPreview(preview, out);
        return EXIT_OK;
    }

    private static int runPromotion(
            DataSource source, DataSource target, SchemaLifecycleExecutor.SchemaManifest manifest,
            SqlDialect sourceDialect, SqlDialect targetDialect, PrintStream out, PrintStream err) {
        out.println("npdev db promote: realizing schema on the target (" + targetDialect.name() + ")...");
        try {
            realizeTargetSchema(target, manifest, sourceDialect, targetDialect);
        } catch (RuntimeException failure) {
            err.println("npdev db promote: FAILED realizing schema on the target: " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }
        out.println("npdev db promote: schema realized. Previewing...");
        return runAfterSchemaRealized(source, target, manifest, out);
    }

    /**
     * The preview/apply/verify arc, extracted from {@link #runPromotion} so it is directly unit
     * testable against two real {@link DataSource}s with hand-built schema+data (standing in for "the
     * target's schema was already realized") -- {@code loadManifest}/real Flyway migration content is
     * `runPromotion`'s OWN concern (via {@link #realizeTargetSchema}), not this method's, matching
     * {@link #realizeTargetSchema}'s own extraction rationale.
     */
    static int runAfterSchemaRealized(
            DataSource source, DataSource target, SchemaLifecycleExecutor.SchemaManifest manifest, PrintStream out) {
        printPreview(CrossEngineDataPromotion.preview(source, target, manifest), out);

        out.println("npdev db promote: applying...");
        CrossEngineDataPromotion.PromotionResult applyResult = CrossEngineDataPromotion.apply(source, target, manifest);
        printApply(applyResult, out);
        if (!applyResult.allMatched()) {
            out.println("npdev db promote: PROMOTION NOT VERIFIED -- one or more tables failed to copy "
                    + "cleanly (see above). Not attempting verification against incomplete data.");
            return EXIT_NEEDS_ATTENTION;
        }

        out.println("npdev db promote: verifying...");
        PromotionVerifier.VerificationResult verifyResult = PromotionVerifier.verify(source, target, manifest);
        printVerify(verifyResult, out);
        if (verifyResult.allVerified()) {
            out.println("npdev db promote: PROMOTION VERIFIED");
            return EXIT_OK;
        }
        out.println("npdev db promote: PROMOTION NOT VERIFIED -- see the itemized mismatches above.");
        return EXIT_NEEDS_ATTENTION;
    }

    /**
     * The one genuinely novel mechanism this class adds on top of already-shipped pieces --
     * extracted so it is directly unit-testable against a real {@link DataSource} with a hand-built
     * manifest, the same way {@link SchemaLifecycleExecutor#codeFor} and
     * {@link SchemaVerifyMain#exitCodeFor} are split out from their own {@code System.exit}-adjacent
     * callers. See the class javadoc's dialect section for why {@code targetDialect} pins
     * {@code SqlDialects.active()} only for this call, restored to {@code sourceDialect} in every
     * exit path (including a thrown failure) so a caller mid-arc is never left with the wrong
     * dialect active for the {@link CrossEngineDataPromotion}/{@link PromotionVerifier} steps after
     * it.
     */
    static void realizeTargetSchema(
            DataSource target, SchemaLifecycleExecutor.SchemaManifest manifest,
            SqlDialect sourceDialect, SqlDialect targetDialect) {
        try {
            SqlDialects.setActive(targetDialect);
            Flyway flyway = Flyway.configure()
                    .dataSource(target)
                    .locations("classpath:db/schema-realization")
                    .load();
            new SchemaLifecycleExecutor().migrate(flyway, manifest.withEngine(targetDialect.name()));
        } finally {
            SqlDialects.setActive(sourceDialect);
        }
    }

    private static void printPreview(CrossEngineDataPromotion.Preview preview, PrintStream out) {
        for (CrossEngineDataPromotion.TableCounts counts : preview.tableCounts()) {
            out.println("  " + counts.table() + ": source=" + counts.sourceRowCount()
                    + " target(before)=" + counts.targetRowCountBefore());
        }
        for (CrossEngineDataPromotion.TypeMappingNote note : preview.notes()) {
            out.println("  note: " + note.table() + "." + note.column() + " (" + note.sqlType() + ") -- " + note.note());
        }
    }

    private static void printApply(CrossEngineDataPromotion.PromotionResult result, PrintStream out) {
        for (CrossEngineDataPromotion.TableCopyResult table : result.tables()) {
            if (table.matched()) {
                out.println("  " + table.table() + ": copied " + table.rowsCopied() + " row(s), matched.");
            } else {
                out.println("  " + table.table() + ": NOT MATCHED (source=" + table.sourceRowCount()
                        + " copied=" + table.rowsCopied() + " targetAfter=" + table.targetRowCountAfter()
                        + (table.error() == null ? "" : ", error: " + table.error()) + ")");
            }
        }
    }

    private static void printVerify(PromotionVerifier.VerificationResult result, PrintStream out) {
        for (PromotionVerifier.TableVerification table : result.tables()) {
            if (table.verified()) {
                out.println("  " + table.table() + ": verified (row count, content hash, null counts, shape).");
                continue;
            }
            if (table.error() != null) {
                out.println("  " + table.table() + ": COULD NOT VERIFY -- " + table.error());
                continue;
            }
            out.println("  " + table.table() + ": NOT VERIFIED");
            if (!table.rowCountMatches()) {
                out.println("    row count mismatch: source=" + table.sourceRowCount()
                        + " target=" + table.targetRowCount());
            }
            if (!table.contentHashMatches()) {
                out.println("    content hash mismatch: source=" + table.sourceContentHash()
                        + " target=" + table.targetContentHash());
            }
            if (!table.nullCountsMatch()) {
                for (PromotionVerifier.ColumnNullCount column : table.columnNullCounts()) {
                    if (!column.matches()) {
                        out.println("    column '" + column.column() + "' null count mismatch: source="
                                + column.sourceNulls() + " target=" + column.targetNulls());
                    }
                }
            }
            for (String problem : table.shapeProblems()) {
                out.println("    " + problem);
            }
        }
    }

    private static void printUsage(PrintStream err) {
        err.println("usage: PromoteMain --url <sourceJdbcUrl> [--user <user>] [--password <password>] "
                + "--to <targetJdbcUrl> [--to-user <user>] [--to-password <password>] [--dry-run]");
    }

    /** Minimal {@link DataSource} over a single JDBC URL -- see {@link ReverseMigrateMain}'s own copy
     *  for why this command must not depend on any Spring-managed DataSource bean. */
    private static final class UrlDataSource implements DataSource {
        private final String url;
        private final String user;
        private final String password;

        private UrlDataSource(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return (user == null && password == null)
                    ? DriverManager.getConnection(url)
                    : DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String username, String pass) throws SQLException {
            return DriverManager.getConnection(url, username, pass);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(getClass().getName());
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
