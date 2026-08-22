package com.finalexec.db;

import com.npdev.kernel.ports.SequenceAllocator;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;

/**
 * R5.3: the JDBC-backed {@link SequenceAllocator} -- persists each counter in {@code
 * npdev_sequence_counter} ({@code com.npdev.kernel.dbschema.NpdevSequenceCounterTable}) and
 * allocates the next value under a real row lock.
 *
 * <p><b>Runs on the AMBIENT connection, not an independent one -- the one deliberate difference
 * from {@code CronFireClaimStore} (R2.7/RUN-2), which this otherwise mirrors.</b> {@code
 * CronFireClaimStore} opens its own connection and commits independently, because nothing else
 * needs to share its outcome. A sequence allocation is different: it MUST commit or roll back
 * together with the concept row {@code DefaultConceptGateway.save} is about to insert with the
 * number this allocated -- two half-committed pieces (a counter bumped but its owning row never
 * inserted, or the reverse) would be a worse defect than the double-fire RUN-2/R2.7 guard against.
 * {@link #allocateNext} therefore reads its lock/update through {@link DataSourceUtils#getConnection},
 * the SAME mechanism {@link JdbcBusinessConceptStore#openConnection} already documents: it joins
 * the ambient Spring transaction when one is active (a {@code SpringTransactionRunner}-wrapped
 * {@code DefaultConceptGateway.save}), so both writes land in the one transaction that call opened.
 *
 * <p><b>MEASURED, not assumed: joining an ambient transaction is not enough on its own.</b> The
 * first version of this class did exactly what {@link JdbcBusinessConceptStore} does -- read/write
 * through {@code DataSourceUtils.getConnection} and nothing else -- on the theory that {@code SELECT
 * ... FOR UPDATE}'s lock would hold for the subsequent {@code UPDATE}. Proven wrong by
 * {@code JdbcSequenceAllocatorConcurrencyTest} run WITHOUT wrapping each call in a transaction (a
 * caller-error the type system does not prevent): 20 threads x 25 allocations for one scope key
 * produced only 90 distinct values out of 500 calls. The reason is autocommit -- with no ambient
 * transaction, {@code DataSourceUtils.getConnection} hands back a plain autocommit connection, so
 * the {@code FOR UPDATE} row lock is released the instant the {@code SELECT} itself completes
 * (autocommit makes each statement its own transaction), leaving the {@code UPDATE} that follows
 * completely unprotected. {@link #allocateNext} now inspects {@link Connection#getAutoCommit()} and
 * manages a LOCAL transaction (temporarily false, commit, restore) only when the connection is not
 * already inside one -- i.e. only when nothing else (Spring) is already managing the boundary. This
 * makes the class correct on its own, in addition to (not instead of) correctly joining a real
 * ambient transaction when the production call site (Spring-wrapped {@code DefaultConceptGateway.save})
 * provides one.
 *
 * <p>The idempotent row-seed insert is the one step kept on ITS OWN independent connection/
 * transaction (autocommit), exactly like {@code CronFireClaimStore.ensureRowExists} -- attempting
 * it on the ambient connection would risk poisoning that transaction on an engine that aborts the
 * whole transaction after any statement error (Postgres) the moment two concurrent callers race the
 * FIRST allocation for a brand-new scope key, which is not a rare edge case here: it is the common
 * case the two-thread concurrency proof exercises. By the time the ambient-connection SELECT ...
 * FOR UPDATE below runs, that independent insert has already committed (or lost harmlessly to a
 * concurrent winner), so the row is guaranteed visible under ordinary READ COMMITTED semantics.
 */
public final class JdbcSequenceAllocator implements SequenceAllocator {
    private final DataSource dataSource;
    private final SqlDialect dialect;

    public JdbcSequenceAllocator(DataSource dataSource) {
        this(dataSource, SqlDialects.active());
    }

    /** Explicit dialect, for tests that pin an engine rather than reading the active one. */
    public JdbcSequenceAllocator(DataSource dataSource, SqlDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public long allocateNext(String scopeKey) {
        Objects.requireNonNull(scopeKey, "scopeKey");
        ensureRowExists(scopeKey);

        Connection connection = DataSourceUtils.getConnection(dataSource);
        // See this class's javadoc: manage a local transaction ONLY when nothing else already is.
        // Spring's DataSourceTransactionManager sets autoCommit false the moment it binds a
        // connection to a transaction, so autoCommit==true here means this call is the sole owner
        // of the boundary -- committing/rolling back an ambient transaction we did not start would
        // be the opposite bug.
        boolean manageOwnTransaction;
        try {
            manageOwnTransaction = connection.getAutoCommit();
            if (manageOwnTransaction) {
                connection.setAutoCommit(false);
            }
        } catch (SQLException failure) {
            DataSourceUtils.releaseConnection(connection, dataSource);
            throw new IllegalStateException("Failed preparing allocation transaction for scope " + scopeKey, failure);
        }
        try {
            String selectSql = dialect.selectForUpdate("current_value", "npdev_sequence_counter", "scope_key = ?");
            long current = selectCurrent(connection, selectSql, scopeKey);
            long next = current + 1;
            updateValue(connection, scopeKey, next);
            if (manageOwnTransaction) {
                connection.commit();
            }
            return next;
        } catch (SQLException failure) {
            if (manageOwnTransaction) {
                rollbackQuietly(connection);
            }
            throw new IllegalStateException("Failed allocating sequence number for scope " + scopeKey, failure);
        } finally {
            if (manageOwnTransaction) {
                restoreAutoCommitQuietly(connection);
            }
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best-effort cleanup only -- the allocation attempt has already failed
        }
    }

    private static void restoreAutoCommitQuietly(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // best-effort restore only -- releaseConnection still runs either way
        }
    }

    /**
     * Idempotently seeds the row this allocation needs, tolerating a concurrent caller (or an
     * earlier call for the same scope key) winning the insert race -- mirrors {@code
     * CronFireClaimStore#ensureRowExists} exactly, including running on its OWN connection (see
     * this class's javadoc for why that matters here beyond mere precedent-following).
     */
    private void ensureRowExists(String scopeKey) {
        String sql = "INSERT INTO npdev_sequence_counter (scope_key, current_value, created_at) VALUES (?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, scopeKey);
            insert.setLong(2, 0L);
            insert.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            insert.executeUpdate();
        } catch (SQLException failure) {
            if (!dialect.isUniqueViolation(failure)) {
                throw new IllegalStateException("Failed seeding sequence counter row for scope " + scopeKey, failure);
            }
            // Expected: a concurrent allocator (or an earlier call for the same scopeKey) already
            // inserted this row -- exactly what this insert exists to guarantee.
        }
    }

    private long selectCurrent(Connection connection, String sql, String scopeKey) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, scopeKey);
            try (ResultSet resultSet = select.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("Sequence counter row vanished for scope " + scopeKey);
                }
                return resultSet.getLong(1);
            }
        }
    }

    private void updateValue(Connection connection, String scopeKey, long next) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE npdev_sequence_counter SET current_value = ? WHERE scope_key = ?")) {
            update.setLong(1, next);
            update.setString(2, scopeKey);
            update.executeUpdate();
        }
    }
}
