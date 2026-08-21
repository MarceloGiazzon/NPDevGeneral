package com.finalexec.scheduler;

import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import com.npdev.kernel.storage.sql.StorageCapability;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/**
 * R2.7: makes two (or more) {@link NpdevCronSchedulerService} instances polling ONE database safe
 * against double-firing the SAME scheduled tick of the SAME (flow, tenant) pair -- the multi-instance
 * hazard RUN-2 already closed for flow-resume. Reuses RUN-2's exact proven mechanism ({@code SELECT
 * ... FOR UPDATE SKIP LOCKED} + a claimed_by/claimed_until lease, see
 * {@code JdbcFlowInstanceStore#claimWaitingEligibleToResume}) rather than inventing a second one --
 * table shape is {@code npdev_cron_fire_claim} ({@link com.npdev.kernel.dbschema.NpdevCronFireClaimTable}).
 *
 * <h2>Why this needs one extra step RUN-2 did not</h2>
 *
 * <p>RUN-2's claim narrows an already-existing batch of rows (a flow instance is created, and only
 * later becomes resume-eligible). A cron tick's claim row does not exist ahead of time -- there is
 * exactly one legitimate fire attempt per (flow_name, tenant_id, scheduled_fire_time), and nothing
 * creates that row until the first instance to observe the tick asks for it. So claiming here is two
 * steps: (1) idempotently ensure the row exists (an {@code INSERT} that tolerates losing a race to a
 * concurrent instance -- {@link SqlDialect#isUniqueViolation} is the exact same portable duplicate-key
 * check {@code MigrationClaimStore} already uses), then (2) the RUN-2 pattern itself -- {@code SELECT
 * ... FOR UPDATE SKIP LOCKED} on that one row, filtered to "unclaimed or lease-expired", then an
 * {@code UPDATE} to claim it, all inside one transaction. A concurrent instance racing the same tick
 * either loses the insert (harmless -- the row it needed is already there), loses the SKIP LOCKED
 * select (its transaction is mid-flight on the very same row), or sees {@code claimed_until} still in
 * the future (the other instance already won and has not finished): every one of those outcomes
 * returns {@code false} -- don't fire.
 */
public final class CronFireClaimStore {

    /**
     * Mirrors {@code JdbcFlowInstanceStore.DEFAULT_CLAIM_LEASE_MILLIS} exactly, though the reasoning
     * is thinner here: unlike RUN-2's resume sweep, nothing ever retries the SAME scheduled_fire_time
     * (a missed cron tick is skipped, not caught up -- see {@code docs/reference/SCHEDULED_FLOWS.md}),
     * so the lease only matters for the narrow window where two instances' claim attempts land at
     * genuinely the same instant. Kept anyway for shape-parity with the proven mechanism and as a
     * safety net if a future retry path is ever added.
     */
    private static final long DEFAULT_CLAIM_LEASE_MILLIS = 30_000L;

    private final DataSource dataSource;
    private final SqlDialect dialect;

    public CronFireClaimStore(DataSource dataSource) {
        this(dataSource, SqlDialects.active());
    }

    /** Explicit dialect, for tests that pin an engine rather than reading the active one. */
    public CronFireClaimStore(DataSource dataSource, SqlDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    /**
     * Attempts to claim exclusive firing rights for one (flowName, tenantId, scheduledFireTime)
     * window. Returns {@code true} only if THIS call won the claim -- the caller should fire the flow
     * if and only if this returns true, and quietly skip otherwise (another instance already claimed,
     * or is currently claiming, this exact tick).
     *
     * @param leaseMillis how long this claim is held before it becomes reclaimable; non-positive
     *                    means {@link #DEFAULT_CLAIM_LEASE_MILLIS}.
     */
    public boolean tryClaim(
            String flowName, String tenantId, Instant scheduledFireTime, String claimantId, long leaseMillis) {
        Objects.requireNonNull(flowName, "flowName");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(scheduledFireTime, "scheduledFireTime");
        Objects.requireNonNull(claimantId, "claimantId");
        long effectiveLeaseMillis = leaseMillis > 0 ? leaseMillis : DEFAULT_CLAIM_LEASE_MILLIS;
        Timestamp fireTimestamp = Timestamp.from(scheduledFireTime);

        ensureRowExists(flowName, tenantId, fireTimestamp);

        // X0 rule: ask before building skip-locked SQL, rather than assume every future engine
        // answers yes just because all four supported today do (StorageCapability#SKIP_LOCKED_READS).
        dialect.require(StorageCapability.SKIP_LOCKED_READS);
        String selectSql = dialect.selectForUpdateSkipLocked(
                "flow_name, tenant_id, scheduled_fire_time",
                "npdev_cron_fire_claim",
                "flow_name = ? AND tenant_id = ? AND scheduled_fire_time = ? "
                        + "AND (claimed_until IS NULL OR claimed_until < ?)",
                "scheduled_fire_time ASC",
                1);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Timestamp leaseExpiry = new Timestamp(now.getTime() + effectiveLeaseMillis);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean won = selectAndLock(connection, selectSql, flowName, tenantId, fireTimestamp, now);
                if (won) {
                    markClaimed(connection, flowName, tenantId, fireTimestamp, claimantId, leaseExpiry);
                }
                connection.commit();
                return won;
            } catch (SQLException failure) {
                rollbackQuietly(connection);
                throw new IllegalStateException("Failed claiming cron fire for " + flowName + "/" + tenantId
                        + " @ " + scheduledFireTime, failure);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed claiming cron fire for " + flowName + "/" + tenantId
                    + " @ " + scheduledFireTime, exception);
        }
    }

    /**
     * Idempotently seeds the row this claim needs, tolerating a concurrent instance (or an earlier
     * attempt at the same tick) winning the insert race -- either way the row now exists for the
     * SKIP LOCKED claim below to act on. Mirrors {@code MigrationClaimStore#ensureCanonicalRow}
     * exactly: only a genuine unique-constraint violation is swallowed; any other failure propagates,
     * because anything else means this row is NOT guaranteed to exist and the claim below would
     * silently see zero rows for a reason that has nothing to do with contention.
     */
    private void ensureRowExists(String flowName, String tenantId, Timestamp fireTimestamp) {
        String sql = "INSERT INTO npdev_cron_fire_claim "
                + "(flow_name, tenant_id, scheduled_fire_time, created_at) VALUES (?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, flowName);
            insert.setString(2, tenantId);
            insert.setTimestamp(3, fireTimestamp);
            insert.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
            insert.executeUpdate();
        } catch (SQLException failure) {
            if (!dialect.isUniqueViolation(failure)) {
                throw new IllegalStateException("Failed seeding cron fire claim row for " + flowName + "/"
                        + tenantId + " @ " + fireTimestamp, failure);
            }
            // Expected: a concurrent instance (or an earlier attempt on the same tick) already
            // inserted this row -- exactly what this insert exists to guarantee.
        }
    }

    private boolean selectAndLock(Connection connection, String sql, String flowName, String tenantId,
            Timestamp fireTimestamp, Timestamp now) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, flowName);
            select.setString(2, tenantId);
            select.setTimestamp(3, fireTimestamp);
            select.setTimestamp(4, now);
            try (ResultSet resultSet = select.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void markClaimed(Connection connection, String flowName, String tenantId, Timestamp fireTimestamp,
            String claimantId, Timestamp leaseExpiry) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE npdev_cron_fire_claim SET claimed_by = ?, claimed_until = ? "
                        + "WHERE flow_name = ? AND tenant_id = ? AND scheduled_fire_time = ?")) {
            update.setString(1, claimantId);
            update.setTimestamp(2, leaseExpiry);
            update.setString(3, flowName);
            update.setString(4, tenantId);
            update.setTimestamp(5, fireTimestamp);
            update.executeUpdate();
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best-effort cleanup only -- the claim attempt has already failed
        }
    }
}
