package com.finalexec.db;

import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * {@code npdev db schema-ahead --report} -- B5-A, boundary-lift 2026-09-02 package 2.3, done-when #4:
 * renders the SAME itemized diagnosis a B5 boot refusal now embeds, with NO app boot required. Follows
 * {@link SchemaVerifyMain}'s exact shape (a plain-JDBC {@code UrlDataSource}, no Spring, no Flyway) so
 * this works even while the app itself refuses to boot -- precisely when an operator needs it most.
 *
 * <h2>Exit codes</h2>
 * <ul>
 *   <li>{@code 0} -- not ahead: the stored fingerprint matches this build, or the database has never
 *       been touched by anything newer.</li>
 *   <li>{@code 1} -- ahead: the report (on stdout) itemizes it, exactly as the boot refusal would.</li>
 *   <li>{@code 2} -- could not determine (no manifest on the classpath, or the database could not be
 *       reached).</li>
 * </ul>
 */
public final class SchemaAheadMain {

    static final int EXIT_NOT_AHEAD = 0;
    static final int EXIT_AHEAD = 1;
    static final int EXIT_COULD_NOT_DETERMINE = 2;

    private SchemaAheadMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        String url = null;
        String user = null;
        String password = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--url".equals(arg) && i + 1 < args.length) {
                url = args[++i];
            } else if ("--user".equals(arg) && i + 1 < args.length) {
                user = args[++i];
            } else if ("--password".equals(arg) && i + 1 < args.length) {
                password = args[++i];
            } else if ("--report".equals(arg)) {
                // The only mode this command has -- accepted and ignored so the CLI's own
                // `schema-ahead --report` spelling passes straight through unmodified.
            } else {
                err.println("npdev db schema-ahead: unrecognized or incomplete argument '" + arg + "'");
                err.println("usage: SchemaAheadMain --url <jdbcUrl> [--user <user>] [--password <password>] [--report]");
                return EXIT_COULD_NOT_DETERMINE;
            }
        }
        if (url == null || url.isBlank()) {
            err.println("usage: SchemaAheadMain --url <jdbcUrl> [--user <user>] [--password <password>] [--report]");
            return EXIT_COULD_NOT_DETERMINE;
        }

        SchemaLifecycleExecutor.SchemaManifest manifest;
        try {
            manifest = SchemaLifecycleExecutor.loadManifest();
        } catch (RuntimeException failure) {
            err.println("npdev db schema-ahead: could not read the schema-realization manifest: " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }
        if (manifest == null) {
            err.println("npdev db schema-ahead: no schema-realization-manifest.json found on the classpath. "
                    + "Build this app at least once (npdev generate/run app) before checking its database.");
            return EXIT_COULD_NOT_DETERMINE;
        }
        if (!manifest.physicalDatabase()) {
            out.println("npdev db schema-ahead: this app has no physical database (InMemory storage) -- nothing to check.");
            return EXIT_NOT_AHEAD;
        }

        DataSource dataSource = new UrlDataSource(url, user, password);
        try (Connection probe = dataSource.getConnection()) {
            // SqlDialects.forConnection, not .active(): this process has no app configuration to read
            // the engine from -- same reasoning as SchemaVerifyMain/MigrationMutex.
            SqlDialect dialect = SqlDialects.forConnection(probe);
            SqlDialects.setActive(dialect);
        } catch (SQLException failure) {
            err.println("npdev db schema-ahead: could not connect to " + url + ": " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }

        Optional<SchemaHistoryStore.HistoryPoint> aheadOfBuild;
        try {
            aheadOfBuild = SchemaHistoryStore.databaseMigratedPastThisBuild(dataSource, manifest);
        } catch (RuntimeException failure) {
            err.println("npdev db schema-ahead: could not read npdev_schema_history: " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }
        if (aheadOfBuild.isEmpty()) {
            out.println("npdev db schema-ahead: not ahead -- this build's own fingerprint ("
                    + manifest.schemaFingerprint() + ") is either what the database is already at, or has "
                    + "never been superseded by anything newer.");
            return EXIT_NOT_AHEAD;
        }

        String aheadFingerprint = aheadOfBuild.get().toFingerprint();
        out.println("npdev db schema-ahead: this database was migrated PAST this build -- it is already at "
                + "fingerprint " + aheadFingerprint + ", a later state than this build's own "
                + manifest.schemaFingerprint() + ". Exactly what differs:");
        out.println(SchemaAheadAnalysis.render(dataSource, manifest, aheadFingerprint));
        return EXIT_AHEAD;
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
