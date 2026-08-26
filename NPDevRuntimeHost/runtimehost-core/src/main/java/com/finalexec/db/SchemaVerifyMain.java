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
import java.time.Instant;
import java.util.logging.Logger;

/**
 * {@code npdev db verify} -- item 1, SUPPORT_FEATURES_PLAN_2026-08-26. Answers "does my database
 * match my model?" with NO app boot required: reads the same generated
 * {@code npdev-generated/src/main/resources/npdev/db/schema-realization-manifest.json} a real boot
 * reads ({@link SchemaLifecycleExecutor#loadManifest}, via {@code ClassPathResource} -- the caller
 * ({@code npdev_cli.py}'s {@code db verify}) is responsible for putting the manifest's resources
 * root on this JVM's classpath, exactly like it already does for the runtimehost-libs jars and the
 * target engine's JDBC driver), opens a PLAIN JDBC connection (no pooling, no Spring context, no
 * Flyway) to the target database, and runs the SAME {@link SchemaDiffEngine}/{@link
 * CurrentSchemaReader}/{@link DesiredSchemaFactory} a live boot's Impact Report uses -- so this
 * command can never disagree with what the app itself would compute, and it works even when the app
 * refuses to boot (B5 schema-ahead, B4 lock) precisely because it needs none of what a boot needs.
 *
 * <h2>Exit codes -- deliberately not collapsed</h2>
 * <ul>
 *   <li>{@code 0} -- the live database matches the model (no diff items needing attention).</li>
 *   <li>{@code 1} -- drift found; the report (on stdout) names it.</li>
 *   <li>{@code 2} -- could not determine (no manifest on the classpath, or the database could not be
 *       reached) -- a DIFFERENT answer than "your schema is wrong": a first-month user acts on the
 *       two very differently.</li>
 * </ul>
 */
public final class SchemaVerifyMain {

    static final int EXIT_MATCHES = 0;
    static final int EXIT_DRIFT = 1;
    static final int EXIT_COULD_NOT_DETERMINE = 2;

    private SchemaVerifyMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        String url = null;
        String user = null;
        String password = null;
        boolean json = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--url".equals(arg) && i + 1 < args.length) {
                url = args[++i];
            } else if ("--user".equals(arg) && i + 1 < args.length) {
                user = args[++i];
            } else if ("--password".equals(arg) && i + 1 < args.length) {
                password = args[++i];
            } else if ("--json".equals(arg)) {
                json = true;
            } else {
                err.println("npdev db verify: unrecognized or incomplete argument '" + arg + "'");
                err.println("usage: SchemaVerifyMain --url <jdbcUrl> [--user <user>] [--password <password>] [--json]");
                return EXIT_COULD_NOT_DETERMINE;
            }
        }
        if (url == null || url.isBlank()) {
            err.println("usage: SchemaVerifyMain --url <jdbcUrl> [--user <user>] [--password <password>] [--json]");
            return EXIT_COULD_NOT_DETERMINE;
        }

        SchemaLifecycleExecutor.SchemaManifest manifest;
        try {
            manifest = SchemaLifecycleExecutor.loadManifest();
        } catch (RuntimeException failure) {
            err.println("npdev db verify: could not read the schema-realization manifest: " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }
        if (manifest == null) {
            err.println("npdev db verify: no schema-realization-manifest.json found on the classpath. Build "
                    + "this app at least once (npdev generate/run app) before verifying its database.");
            return EXIT_COULD_NOT_DETERMINE;
        }
        if (!manifest.physicalDatabase()) {
            out.println("npdev db verify: this app has no physical database (InMemory storage) -- nothing to verify.");
            return EXIT_MATCHES;
        }

        DataSource dataSource = new UrlDataSource(url, user, password);
        try (Connection probe = dataSource.getConnection()) {
            // SqlDialects.forConnection, not .active(): this process has no app configuration to read
            // the engine from, and .active() defaults to Postgres, same reasoning as MigrationMutex.
            SqlDialect dialect = SqlDialects.forConnection(probe);
            SqlDialects.setActive(dialect);
        } catch (SQLException failure) {
            err.println("npdev db verify: could not connect to " + url + ": " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }

        ImpactReport report;
        ConstraintSurplusReport surplus;
        try {
            CurrentSchema current = new CurrentSchemaReader().read(dataSource);
            CurrentSchema scopedCurrent = ShadowParityProbe.scopeToOwnedBusinessTables(current, manifest);
            DesiredSchema desired = DesiredSchemaFactory.fromManifest(manifest);
            SchemaDiffEngine diffEngine = new SchemaDiffEngine();
            SchemaDiff diff = diffEngine.diff(desired, scopedCurrent);
            report = ImpactReport.generate(diff, dataSource);
            surplus = diffEngine.findSurplusConstraints(desired, scopedCurrent);
        } catch (RuntimeException failure) {
            err.println("npdev db verify: could not read the live database schema: " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }

        String storedFingerprint;
        try {
            storedFingerprint = SchemaLifecycleExecutor.readStoredFingerprintPublic(dataSource);
        } catch (RuntimeException ignored) {
            storedFingerprint = null;
        }
        String toFingerprint = manifest.schemaFingerprint();

        if (json) {
            out.println(ImpactReportJson.render(
                    report, Instant.now().toString(), storedFingerprint, toFingerprint, null, surplus));
        } else {
            out.println(ImpactReportText.render(report, storedFingerprint, toFingerprint, null, surplus));
        }

        boolean matches = report.verdict() == ImpactReport.Verdict.NO_CHANGES
                || report.verdict() == ImpactReport.Verdict.SAFE;
        return matches ? EXIT_MATCHES : EXIT_DRIFT;
    }

    /** Minimal {@link DataSource} over a single JDBC URL -- this command needs no pooling, and must
     *  not depend on any Spring-managed DataSource bean, since the whole point of {@code db verify}
     *  is that it works with none of Spring, Flyway, or an ApplicationContext running. */
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
