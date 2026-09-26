package com.finalexec.db;

import com.finalexec.db.schemastate.ConstraintSurplusReport;
import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffEngine;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;

/**
 * {@code npdev db mark-done} -- Wave 5, boundary B6 lift (docs/ACCEPTED_BOUNDARIES.md). B6's own row
 * documented "mark-done trusts the operator completely" as deliberate: the REST endpoint
 * ({@link com.finalexec.controlpanel.SchemaAcknowledgmentController#markDone}) is a raw, unverified
 * primitive by design (REG-7.2 D2: "ControlPanel-only v1, no generator/CLI round-trip"), for exactly
 * the case where an operator has hand-migrated and wants NPDev to stop trying to converge. That
 * primitive is UNCHANGED by this class. What was missing is the gate {@link SchemaVerifyMain}'s own
 * exit-code javadoc already named as the real gap: "an operator who hand-migrated outside NPDev (B13),
 * created nothing, and is about to `mark-done` on the strength of a green verify (B6)" -- a `db verify`
 * SAFE verdict (changes exist but are non-destructive) reads as "fine" at a glance, but is NOT a match.
 *
 * <p>This command closes that gap directly: it runs the SAME expected-vs-live diff {@code db verify}
 * runs (this app's OWN {@link DesiredSchemaFactory#fromManifest manifest-derived schema} against the
 * live database, via {@link SchemaDiffEngine}), and only records the mark
 * ({@link MigrationMarkStore#insert}) when the verdict is the strict {@code NO_CHANGES} -- not the
 * looser "no error" {@code SAFE}. Follows {@link SchemaVerifyMain}'s exact shape (a plain-JDBC
 * {@code UrlDataSource}, no Spring, no Flyway, no app boot required) so it works from the target
 * build's OWN generated app directory before that build has ever been deployed -- which is when an
 * operator has this app dir on disk but has not yet booted it, exactly REG-7.2's original scenario.
 *
 * <p>{@code --force} is the escape hatch that keeps the "trust the operator, deliberately" spirit
 * B6's row protects: a mismatch never blocks a determined operator, it only stops an ACCIDENTAL
 * mark-done from being silently recorded as if verified. A forced mark is stamped {@code forced: true}
 * so the audit trail ({@code GET /api/admin/schema-migration/marks}) shows it was not a clean match.
 *
 * <h2>Exit codes</h2>
 * <ul>
 *   <li>{@code 0} -- recorded: the live database matched this app's own desired schema exactly, or
 *       {@code --force} was given.</li>
 *   <li>{@code 1} -- refused: drift found (the itemized diff is on stdout) and {@code --force} was not
 *       given -- nothing was recorded.</li>
 *   <li>{@code 2} -- could not determine (no manifest, no fromFingerprint resolvable, or the database
 *       could not be reached) -- never a claim about whether the schema matches.</li>
 * </ul>
 */
public final class MigrationMarkDoneMain {

    static final int EXIT_RECORDED = 0;
    static final int EXIT_REFUSED = 1;
    static final int EXIT_COULD_NOT_DETERMINE = 2;

    private MigrationMarkDoneMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        String url = null;
        String user = null;
        String password = null;
        String fromFingerprint = null;
        String markedBy = null;
        String note = null;
        boolean force = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--url".equals(arg) && i + 1 < args.length) {
                url = args[++i];
            } else if ("--user".equals(arg) && i + 1 < args.length) {
                user = args[++i];
            } else if ("--password".equals(arg) && i + 1 < args.length) {
                password = args[++i];
            } else if ("--from-fingerprint".equals(arg) && i + 1 < args.length) {
                fromFingerprint = args[++i];
            } else if ("--by".equals(arg) && i + 1 < args.length) {
                markedBy = args[++i];
            } else if ("--note".equals(arg) && i + 1 < args.length) {
                note = args[++i];
            } else if ("--force".equals(arg)) {
                force = true;
            } else {
                err.println("npdev db mark-done: unrecognized or incomplete argument '" + arg + "'");
                printUsage(err);
                return EXIT_COULD_NOT_DETERMINE;
            }
        }
        if (url == null || url.isBlank()) {
            printUsage(err);
            return EXIT_COULD_NOT_DETERMINE;
        }

        SchemaLifecycleExecutor.SchemaManifest manifest;
        try {
            manifest = SchemaLifecycleExecutor.loadManifest();
        } catch (RuntimeException failure) {
            err.println("npdev db mark-done: could not read the schema-realization manifest: " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }
        if (manifest == null) {
            err.println("npdev db mark-done: no schema-realization-manifest.json found on the classpath. "
                    + "Build this app at least once (npdev generate/run app) before marking its database done.");
            return EXIT_COULD_NOT_DETERMINE;
        }
        if (!manifest.physicalDatabase()) {
            out.println("npdev db mark-done: this app has no physical database (InMemory storage) -- nothing to mark.");
            return EXIT_RECORDED;
        }
        String toFingerprint = manifest.schemaFingerprint();

        DataSource dataSource = new UrlDataSource(url, user, password);
        try (Connection probe = dataSource.getConnection()) {
            // SqlDialects.forConnection, not .active(): this process has no app configuration to read
            // the engine from -- same reasoning as SchemaVerifyMain/SchemaAheadMain/MigrationMutex.
            SqlDialect dialect = SqlDialects.forConnection(probe);
            SqlDialects.setActive(dialect);
        } catch (SQLException failure) {
            err.println("npdev db mark-done: could not connect to " + url + ": " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }

        if (fromFingerprint == null || fromFingerprint.isBlank()) {
            try {
                fromFingerprint = SchemaLifecycleExecutor.readStoredFingerprintPublic(dataSource);
            } catch (RuntimeException failure) {
                err.println("npdev db mark-done: could not read the live database's stored fingerprint: "
                        + failure.getMessage() + " -- pass --from-fingerprint explicitly.");
                return EXIT_COULD_NOT_DETERMINE;
            }
        }
        if (fromFingerprint == null || fromFingerprint.isBlank()) {
            err.println("npdev db mark-done: the live database has no stored fingerprint yet (a database "
                    + "NPDev has never migrated) -- pass --from-fingerprint explicitly if this transition is "
                    + "genuinely intended.");
            return EXIT_COULD_NOT_DETERMINE;
        }

        ImpactReport report;
        try {
            CurrentSchema current = new CurrentSchemaReader().read(dataSource);
            CurrentSchema scopedCurrent = ShadowParityProbe.scopeToOwnedBusinessTables(current, manifest);
            DesiredSchema desired = DesiredSchemaFactory.fromManifest(manifest);
            SchemaDiffEngine diffEngine = new SchemaDiffEngine();
            SchemaDiff diff = diffEngine.diff(desired, scopedCurrent);
            report = ImpactReport.generate(diff, dataSource);
        } catch (RuntimeException failure) {
            err.println("npdev db mark-done: could not read the live database schema: " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }

        // Deliberately NO_CHANGES only, exactly the distinction SchemaVerifyMain.exitCodeFor's own
        // javadoc calls out: SAFE means changes exist (even an entirely empty database verifies SAFE,
        // every table a SAFE_TABLE_CREATE) -- that is maximal drift, not a match, and is exactly the
        // false-positive "green verify" this command exists to stop from reaching mark-done unchecked.
        boolean matches = report.verdict() == ImpactReport.Verdict.NO_CHANGES;
        if (!matches && !force) {
            out.println("npdev db mark-done: REFUSED -- the live database does not match this build's own "
                    + "desired schema (fingerprint " + toFingerprint + "). Exactly what differs:");
            out.println(ImpactReportText.render(report, fromFingerprint, toFingerprint, null, ConstraintSurplusReport.EMPTY, List.of()));
            out.println("Nothing was recorded. Apply the migration for real, or re-run with --force if you "
                    + "are certain the live schema is correct despite this diff (it will be stamped forced: true).");
            return EXIT_REFUSED;
        }

        // REG-30 (MigrationMarkStore's own unique index): a second mark for the SAME (from, to)
        // transition cannot be inserted -- this happens whenever fromFingerprint equals toFingerprint
        // (marking a build against its own already-live schema, then retrying) or an operator simply
        // re-runs the command. Checked explicitly rather than caught after the fact: an idempotent
        // "already recorded" is the right answer here, not a raw SQLIntegrityConstraintViolationException
        // surfacing as an uncaught stack trace -- especially since --force retrying after a refusal is
        // exactly the sequence this command's own usage encourages.
        var existing = MigrationMarkStore.findMatching(dataSource, fromFingerprint, toFingerprint);
        if (existing.isPresent()) {
            out.println("npdev db mark-done: already recorded -- mark id " + existing.get().id() + " already "
                    + "covers this exact transition (" + fromFingerprint + " -> " + toFingerprint + ", forced: "
                    + existing.get().forced() + "). Nothing new was written.");
            return EXIT_RECORDED;
        }

        MigrationMarkStore.Mark inserted = MigrationMarkStore.insert(
                dataSource, fromFingerprint, toFingerprint, resolveMarkedBy(markedBy), note, !matches);

        if (!matches) {
            out.println("npdev db mark-done: FORCED -- recording despite drift (see the diff above). "
                    + "mark id " + inserted.id() + ", forced: true.");
        } else {
            out.println("npdev db mark-done: recorded -- live database confirmed to match fingerprint "
                    + toFingerprint + " exactly. mark id " + inserted.id() + " (" + fromFingerprint + " -> "
                    + toFingerprint + ").");
        }
        return EXIT_RECORDED;
    }

    private static String resolveMarkedBy(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String osUser = System.getProperty("user.name");
        return (osUser == null || osUser.isBlank()) ? null : osUser;
    }

    private static void printUsage(PrintStream err) {
        err.println("usage: MigrationMarkDoneMain --url <jdbcUrl> [--user <user>] [--password <password>] "
                + "[--from-fingerprint <fp>] [--by <name>] [--note <text>] [--force]");
    }

    /** Minimal {@link DataSource} over a single JDBC URL -- see {@link SchemaVerifyMain}'s own copy for
     *  why this command must not depend on any Spring-managed DataSource bean. */
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
