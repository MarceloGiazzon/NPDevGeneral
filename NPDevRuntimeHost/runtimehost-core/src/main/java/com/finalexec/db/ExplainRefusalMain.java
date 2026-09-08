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
 * {@code npdev db explain-refusal} -- STOR-32 (boundary B7, POSTURAL_LIFT_PLAN_2026-09-07.md package
 * P4): prints the platform-computed answer to the row's old workaround ("treat a refused boot as
 * 'inspect before retrying'") -- the most recently refused boot's committed steps, each marked
 * idempotent or not, its retry verdict, and the step that was in flight when it refused -- with NO
 * app boot required. Follows {@link SchemaAheadMain}'s exact shape (a plain-JDBC {@code UrlDataSource},
 * no Spring, no Flyway) so this works even while the app itself refuses to boot -- precisely when an
 * operator needs it most. Unlike {@code SchemaAheadMain} it needs no schema-realization manifest: the
 * journal is read straight out of the configured database.
 *
 * <h2>Exit codes</h2>
 * <ul>
 *   <li>{@code 0} -- no refusal recorded (or the journal table has never been created), printed as such.</li>
 *   <li>{@code 1} -- the residue of the most recently refused boot, on stdout.</li>
 *   <li>{@code 2} -- could not determine (the database could not be reached, or an argument was missing).</li>
 * </ul>
 */
public final class ExplainRefusalMain {

    static final int EXIT_NONE_RECORDED = 0;
    static final int EXIT_REFUSAL_SHOWN = 1;
    static final int EXIT_COULD_NOT_DETERMINE = 2;

    private ExplainRefusalMain() {
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
            } else {
                err.println("npdev db explain-refusal: unrecognized or incomplete argument '" + arg + "'");
                err.println("usage: ExplainRefusalMain --url <jdbcUrl> [--user <user>] [--password <password>]");
                return EXIT_COULD_NOT_DETERMINE;
            }
        }
        if (url == null || url.isBlank()) {
            err.println("usage: ExplainRefusalMain --url <jdbcUrl> [--user <user>] [--password <password>]");
            return EXIT_COULD_NOT_DETERMINE;
        }

        DataSource dataSource = new UrlDataSource(url, user, password);
        try (Connection probe = dataSource.getConnection()) {
            // Same reasoning as SchemaAheadMain: this process has no app configuration to read the
            // engine from, so pin the dialect from the live connection.
            SqlDialect dialect = SqlDialects.forConnection(probe);
            SqlDialects.setActive(dialect);
        } catch (SQLException failure) {
            err.println("npdev db explain-refusal: could not connect to " + url + ": " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }

        Optional<String> bootId = RefusalResidue.latestRefusedBootId(dataSource);
        if (bootId.isEmpty()) {
            out.println("npdev db explain-refusal: no refused boot recorded -- either no boot has ever "
                    + "refused on this database, or npdev_boot_residue_journal has not been created yet "
                    + "(it is created by the first schema lifecycle boot that reaches a journalled step).");
            return EXIT_NONE_RECORDED;
        }

        RefusalResidue.Residue residue = RefusalResidue.forBoot(dataSource, bootId.get());
        Optional<String> refusingStep = RefusalResidue.refusingStepName(dataSource, bootId.get());
        out.println("npdev db explain-refusal: the most recently refused boot (" + bootId.get() + ")");
        if (refusingStep.isPresent()) {
            out.println("refused while running step: " + refusingStep.get()
                    + " (started, but not committed -- this is where the boot stopped)");
        }
        out.print(SchemaRefusal.render(residue));
        return EXIT_REFUSAL_SHOWN;
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