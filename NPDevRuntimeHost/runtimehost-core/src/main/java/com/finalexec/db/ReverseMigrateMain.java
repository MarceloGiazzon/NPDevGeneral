package com.finalexec.db;

import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * {@code npdev db reverse-migrate} -- B5-B, boundary-lift 2026-09-02 package 4.1: turns 2.3's advisory
 * schema-ahead diagnosis into an action for the pure-superset case (see {@link ReverseMigrationPlanner}
 * for the full design). Follows {@link SchemaAheadMain}'s exact shape (a plain-JDBC
 * {@code UrlDataSource}, no Spring, no Flyway) -- this must work even while the app itself refuses to
 * boot, precisely the B5 scenario it exists to resolve.
 *
 * <h2>Two modes, never both</h2>
 * <ul>
 *   <li>{@code --preview} -- computes the plan and prints it (itemized items + the ack token needed to
 *       execute it, when one exists), never runs DDL.</li>
 *   <li>{@code --ack-token <token>} -- recomputes the plan fresh and, only if the supplied token matches
 *       exactly, executes it.</li>
 * </ul>
 *
 * <h2>Exit codes (both modes, same vocabulary as {@link SchemaAheadMain})</h2>
 * <ul>
 *   <li>{@code 0} -- nothing to do: not ahead, or ahead with nothing that needs reversing (and, in
 *       {@code --ack-token} mode, DDL applied successfully).</li>
 *   <li>{@code 1} -- there is something to look at: a ready plan or a blocked/ambiguous one in
 *       {@code --preview}; a refusal (blocked, or a mismatched token) in {@code --ack-token}.</li>
 *   <li>{@code 2} -- could not determine (bad arguments, no manifest, could not connect).</li>
 * </ul>
 */
public final class ReverseMigrateMain {

    static final int EXIT_OK = 0;
    static final int EXIT_NEEDS_ATTENTION = 1;
    static final int EXIT_COULD_NOT_DETERMINE = 2;

    private ReverseMigrateMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        String url = null;
        String user = null;
        String password = null;
        boolean preview = false;
        String ackToken = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--url".equals(arg) && i + 1 < args.length) {
                url = args[++i];
            } else if ("--user".equals(arg) && i + 1 < args.length) {
                user = args[++i];
            } else if ("--password".equals(arg) && i + 1 < args.length) {
                password = args[++i];
            } else if ("--preview".equals(arg)) {
                preview = true;
            } else if ("--ack-token".equals(arg) && i + 1 < args.length) {
                ackToken = args[++i];
            } else {
                err.println("npdev db reverse-migrate: unrecognized or incomplete argument '" + arg + "'");
                printUsage(err);
                return EXIT_COULD_NOT_DETERMINE;
            }
        }
        if (url == null || url.isBlank() || (!preview && ackToken == null)) {
            printUsage(err);
            return EXIT_COULD_NOT_DETERMINE;
        }
        if (preview && ackToken != null) {
            err.println("npdev db reverse-migrate: --preview and --ack-token are mutually exclusive -- "
                    + "preview first, then re-run with the token it prints.");
            return EXIT_COULD_NOT_DETERMINE;
        }

        SchemaLifecycleExecutor.SchemaManifest manifest;
        try {
            manifest = SchemaLifecycleExecutor.loadManifest();
        } catch (RuntimeException failure) {
            err.println("npdev db reverse-migrate: could not read the schema-realization manifest: " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }
        if (manifest == null) {
            err.println("npdev db reverse-migrate: no schema-realization-manifest.json found on the classpath. "
                    + "Build this app at least once (npdev generate/run app) before checking its database.");
            return EXIT_COULD_NOT_DETERMINE;
        }
        if (!manifest.physicalDatabase()) {
            out.println("npdev db reverse-migrate: this app has no physical database (InMemory storage) -- nothing to reverse.");
            return EXIT_OK;
        }

        DataSource dataSource = new UrlDataSource(url, user, password);
        try (Connection probe = dataSource.getConnection()) {
            // SqlDialects.forConnection, not .active(): this process has no app configuration to read
            // the engine from -- same reasoning as SchemaAheadMain/SchemaVerifyMain/MigrationMutex.
            SqlDialect dialect = SqlDialects.forConnection(probe);
            SqlDialects.setActive(dialect);
        } catch (SQLException failure) {
            err.println("npdev db reverse-migrate: could not connect to " + url + ": " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }

        if (preview) {
            return runPreview(dataSource, manifest, out);
        }
        return runExecute(dataSource, manifest, ackToken, out, err);
    }

    private static int runPreview(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest, PrintStream out) {
        ReverseMigrationPlanner.Plan plan = ReverseMigrationPlanner.plan(dataSource, manifest);
        if (plan instanceof ReverseMigrationPlanner.NotAhead) {
            out.println("npdev db reverse-migrate: not ahead -- this build's own fingerprint ("
                    + manifest.schemaFingerprint() + ") is either what the database is already at, or has "
                    + "never been superseded by anything newer. Nothing to reverse.");
            return EXIT_OK;
        }
        if (plan instanceof ReverseMigrationPlanner.NothingToDo) {
            out.println("npdev db reverse-migrate: this database is ahead, but every difference is already "
                    + "safe to proceed past -- nothing needs dropping or narrowing.");
            return EXIT_OK;
        }
        if (plan instanceof ReverseMigrationPlanner.Blocked blocked) {
            out.println("npdev db reverse-migrate: BLOCKED -- " + blocked.reason());
            return EXIT_NEEDS_ATTENTION;
        }
        ReverseMigrationPlanner.Ready ready = (ReverseMigrationPlanner.Ready) plan;
        out.println("npdev db reverse-migrate: this database is ahead of this build (currently at "
                + ready.aheadFingerprint() + "). Reversing it to this build's own fingerprint ("
                + manifest.schemaFingerprint() + ") would apply " + ready.items().size() + " item(s):");
        for (var item : ready.items()) {
            out.println("  - " + item.itemKey());
        }
        out.println("To execute this, re-run with: --ack-token " + ready.ackToken());
        return EXIT_NEEDS_ATTENTION;
    }

    private static int runExecute(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest,
            String ackToken, PrintStream out, PrintStream err) {
        ReverseMigrationPlanner.ExecutionResult result;
        try {
            result = ReverseMigrationPlanner.execute(dataSource, manifest, ackToken);
        } catch (RuntimeException failure) {
            err.println("npdev db reverse-migrate: FAILED applying the reverse migration: " + failure.getMessage());
            return EXIT_NEEDS_ATTENTION;
        }
        return switch (result.outcome()) {
            case APPLIED -> {
                out.println("npdev db reverse-migrate: " + result.message());
                yield EXIT_OK;
            }
            case NOT_AHEAD, NOTHING_TO_DO -> {
                out.println("npdev db reverse-migrate: " + result.message());
                yield EXIT_OK;
            }
            case BLOCKED, TOKEN_MISMATCH -> {
                out.println("npdev db reverse-migrate: REFUSED -- " + result.message());
                yield EXIT_NEEDS_ATTENTION;
            }
        };
    }

    private static void printUsage(PrintStream err) {
        err.println("usage: ReverseMigrateMain --url <jdbcUrl> [--user <user>] [--password <password>] "
                + "(--preview | --ack-token <token>)");
    }

    /** Minimal {@link DataSource} over a single JDBC URL -- see {@link SchemaAheadMain}'s own copy for
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
