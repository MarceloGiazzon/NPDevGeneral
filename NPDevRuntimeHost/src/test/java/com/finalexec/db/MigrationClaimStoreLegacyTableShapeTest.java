package com.finalexec.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-91 (Move 11 W1). Three composed defects made a real app permanently unbootable with a message
 * naming neither the table nor the cause:
 *
 * <ol>
 *   <li>{@code ensureCanonicalRow} swallowed EVERY {@link SQLException} as "the row already exists";</li>
 *   <li>{@code claimH2} ignored {@link ResultSet#next()}'s return value, so an empty result set
 *       surfaced as H2's generic {@code No data is available};</li>
 *   <li>the seed wrote all-{@code NULL} holder columns, which a pre-existing stricter table rejects.</li>
 * </ol>
 *
 * <p><b>The live trigger, and the answer to REG-91's open second question.</b> The strict table shape
 * is not database corruption and was not written by the schema engine -- it is this class's OWN
 * earlier DDL. Commit {@code 2404605} (REG-7.3 P3) declared
 * {@code instance_id TEXT NOT NULL, hostname TEXT, claimed_at_utc BIGINT NOT NULL}, correct while the
 * row was inserted per claim and deleted on release. Move 9 A1 made the row persist with a blanked
 * holder and relaxed the DDL to all-nullable -- but {@code CREATE TABLE IF NOT EXISTS} is a no-op
 * against an existing table, so every database that ever booted a pre-A1 build keeps the strict shape
 * permanently. {@link #legacyStrictShapeFromCommit2404605} reproduces that exact DDL, so this is an
 * upgrade-path regression reproduction, not a synthetic one.
 */
class MigrationClaimStoreLegacyTableShapeTest {

    /**
     * The verbatim pre-Move-9-A1 DDL, from {@code git show 2404605:...MigrationClaimStore.java}.
     * Confirmed identical to the shape read out of the live WmsOffice database that wedged
     * (claim_key NOT NULL, instance_id NOT NULL, hostname NULLABLE, claimed_at_utc bigint NOT NULL).
     */
    private static final String LEGACY_DDL =
            "CREATE TABLE " + MigrationClaimStore.TABLE
                    + " (claim_key TEXT PRIMARY KEY, instance_id TEXT NOT NULL, hostname TEXT, "
                    + "claimed_at_utc BIGINT NOT NULL)";

    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new UrlDataSource(url);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    @DisplayName("REG-91: a database carrying the pre-A1 NOT NULL claim table still boots -- claim, then release")
    void legacyStrictShapeFromCommit2404605() throws SQLException {
        execute(LEGACY_DDL);

        MigrationClaimStore.Claim claim = MigrationClaimStore.claim(dataSource, false);

        assertTrue(claim != null, "a claim against the legacy strict table shape must succeed, not wedge the boot");
        Optional<MigrationClaimStore.Claim> held = MigrationClaimStore.current(dataSource);
        assertTrue(held.isPresent(), "the claim must be visible to current()");
        assertEquals(claim.instanceId(), held.get().instanceId());

        MigrationClaimStore.release(dataSource, claim.instanceId());

        assertTrue(MigrationClaimStore.current(dataSource).isEmpty(),
                "release() must actually blank the holder against the legacy shape too -- writing NULLs there "
                        + "fails silently and leaves the claim held forever, refusing every later boot");
    }

    @Test
    @DisplayName("REG-91: clear() -- the operator escape hatch -- works against the legacy strict shape")
    void clearWorksAgainstTheLegacyStrictShape() throws SQLException {
        execute(LEGACY_DDL);
        // R9.3: a crashed instance is now simulated by its leftover ROW, not by calling claim() and
        // walking away. claim() takes a connection-scoped mutex it would still be holding, which is
        // precisely what a crashed process does NOT do -- its connection died with it.
        execute("INSERT INTO " + MigrationClaimStore.TABLE
                + " (claim_key, instance_id, hostname, claimed_at_utc) "
                + "VALUES ('schema-migration', 'crashed-instance', 'some-host', 1)");

        MigrationClaimStore.clear(dataSource);

        assertTrue(MigrationClaimStore.current(dataSource).isEmpty(),
                "clear() must leave the row unheld; against the legacy shape the NULL form threw outright");
        MigrationClaimStore.Claim next = MigrationClaimStore.claim(dataSource, false);
        assertTrue(next != null, "and the next boot must then be able to claim normally");
        MigrationClaimStore.release(dataSource, next.instanceId());
    }

    @Test
    @DisplayName("REG-91 defect 1: a NON-duplicate seed failure surfaces the driver's own message, not silence")
    void nonDuplicateSeedFailureIsRethrownWithTheDriverMessage() throws SQLException {
        // A NOT NULL column with no default that the seed insert does not name: the insert fails with
        // SQLState 23502 (NULL not allowed), which is an integrity violation but NOT a duplicate row.
        // The old blanket catch treated it as "the row already exists" and carried on with an empty table.
        execute("CREATE TABLE " + MigrationClaimStore.TABLE
                + " (claim_key TEXT PRIMARY KEY, instance_id TEXT, hostname TEXT, claimed_at_utc BIGINT, "
                + "unexpected_column TEXT NOT NULL)");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> MigrationClaimStore.claim(dataSource, false));

        String rendered = render(failure);
        assertTrue(rendered.contains(MigrationClaimStore.TABLE),
                "the failure must name the table it could not seed; got: " + rendered);
        assertTrue(rendered.contains("NOT a duplicate-row race"),
                "the failure must say the swallow rule did not apply; got: " + rendered);
        assertTrue(rendered.contains("23502"),
                "the failure must carry the driver's SQLState; got: " + rendered);
        assertTrue(rendered.contains("UNEXPECTED_COLUMN") || rendered.contains("unexpected_column"),
                "the failure must carry the driver's own message naming the rejecting column; got: " + rendered);
        assertFalse(rendered.contains("No data is available"),
                "and must NOT be the downstream symptom REG-91 reported; got: " + rendered);
    }

    @Test
    @DisplayName("REG-91 defect 2: a genuinely absent canonical row is named, not reported as 'No data is available'")
    void absentCanonicalRowIsNamed() throws SQLException {
        // A UNIQUE constraint on a column OTHER than the primary key, already occupied by unrelated
        // rows. The seed then fails with a real duplicate-key violation (23505), which is legitimately
        // swallowed as "a concurrent bootstrap race" -- yet THIS key's row genuinely does not exist.
        // This is the residual hole the narrowed catch cannot close, and exactly what the unchecked
        // next() turned into an undiagnosable message.
        //
        // NULLS NOT DISTINCT plus BOTH occupied values is deliberate: it blocks the pre-fix seed
        // (hostname NULL) and the fixed seed (hostname '') alike, so this test reproduces the
        // production symptom against the OLD code rather than merely behaving differently.
        execute("CREATE TABLE " + MigrationClaimStore.TABLE
                + " (claim_key TEXT PRIMARY KEY, instance_id TEXT, hostname TEXT, claimed_at_utc BIGINT, "
                + "CONSTRAINT uq_hostname UNIQUE NULLS NOT DISTINCT (hostname))");
        execute("INSERT INTO " + MigrationClaimStore.TABLE
                + " (claim_key, instance_id, hostname, claimed_at_utc) VALUES ('other-key-null-host', '', NULL, 0)");
        execute("INSERT INTO " + MigrationClaimStore.TABLE
                + " (claim_key, instance_id, hostname, claimed_at_utc) VALUES ('other-key-blank-host', '', '', 0)");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> MigrationClaimStore.claim(dataSource, false));

        String rendered = render(failure);
        assertTrue(rendered.contains("canonical migration claim row is missing"),
                "the failure must say the canonical row is missing; got: " + rendered);
        assertTrue(rendered.contains(MigrationClaimStore.TABLE),
                "and must name the table; got: " + rendered);
        assertFalse(rendered.contains("No data is available"),
                "and must NOT be the driver's generic message REG-91 was reported as; got: " + rendered);
    }

    @Test
    @DisplayName("the current (A1) all-nullable shape is unaffected -- sentinels read as unheld exactly like NULLs did")
    void currentShapeIsUnaffected() throws SQLException {
        execute("CREATE TABLE " + MigrationClaimStore.TABLE
                + " (claim_key TEXT PRIMARY KEY, instance_id TEXT, hostname TEXT, claimed_at_utc BIGINT)");

        MigrationClaimStore.Claim first = MigrationClaimStore.claim(dataSource, false);
        assertTrue(first != null);
        assertThrows(IllegalStateException.class, () -> MigrationClaimStore.claim(dataSource, false),
                "a second claim while the first is held must still be refused as a collision");

        MigrationClaimStore.release(dataSource, first.instanceId());
        assertTrue(MigrationClaimStore.current(dataSource).isEmpty());
        assertTrue(MigrationClaimStore.claim(dataSource, false) != null,
                "and the slot is reusable after release");
    }

    @Test
    @DisplayName("a NULL holder left by a pre-REG-91 release still reads as unheld -- sentinels are additive")
    void preExistingNullHolderStillReadsAsUnheld() throws SQLException {
        execute("CREATE TABLE " + MigrationClaimStore.TABLE
                + " (claim_key TEXT PRIMARY KEY, instance_id TEXT, hostname TEXT, claimed_at_utc BIGINT)");
        execute("INSERT INTO " + MigrationClaimStore.TABLE
                + " (claim_key, instance_id, hostname, claimed_at_utc) VALUES ('schema-migration', NULL, NULL, NULL)");

        assertTrue(MigrationClaimStore.current(dataSource).isEmpty(), "a NULL holder is unheld");
        assertTrue(MigrationClaimStore.claim(dataSource, false) != null,
                "and a boot must still be able to claim it");
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Full throwable chain as text -- the diagnosis may be the message or a cause. */
    private static String render(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            builder.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage()).append('\n');
            if (current.getCause() == current) {
                break;
            }
        }
        return builder.toString();
    }

    /** Minimal {@link DataSource} over {@link DriverManager}; mirrors the sibling claim test's helper. */
    private static final class UrlDataSource implements DataSource {
        private final String url;

        private UrlDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
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
