package com.finalexec.db;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * REG-7.3 ("collision detection", D3): a single-row claim on the fixed key {@code "schema-migration"}
 * -- the PK constraint is what makes a second concurrent claim attempt fail. NOT an advisory lock (the
 * register's original "how to fix" proposed {@code pg_advisory_lock} + an H2 lock table; the owner
 * explicitly rejected that for v1): this is portable, ordinary SQL that behaves identically on H2 and
 * Postgres, and it is <b>deliberately weaker than a lock</b> -- a true TOCTOU race between two
 * near-simultaneous {@code INSERT}s on an engine without strict insert serialization is possible. That
 * is acceptable per the owner's "detect-and-refuse now, add guard rails later if this becomes
 * frequent." Document the limitation honestly; do not claim the race is closed.
 *
 * <p><b>Fresh-install scoping (VERIFIED LIVE, see the REG-7.2/{@link MigrationMarkStore} fix in this
 * same package for the identical mechanism):</b> {@link SchemaLifecycleExecutor#migrate} only attempts
 * {@link #claim} when a schema fingerprint is ALREADY stored -- i.e. this is an upgrade/repeat boot,
 * not a genuinely virgin database. Claiming unconditionally would self-bootstrap this table via
 * {@code CREATE TABLE IF NOT EXISTS} before {@code flyway.migrate()} ever runs on a truly fresh
 * schema, which makes Flyway see a non-empty {@code public} schema with no history table and refuse
 * outright -- exactly the bug REG-7.2 hit and fixed. Net effect: the very-first-ever boot of a brand
 * new database is not claim-protected (an even narrower race than the register's own "two containers
 * against an already-initialized Postgres" practical example, which IS protected, since that example
 * itself describes both instances reading an already-stored fingerprint).
 *
 * <p>Self-bootstrapped exactly like {@link MigrationMarkStore} / {@link PendingSchemaAcknowledgmentStore}
 * -- a plain {@code CREATE TABLE IF NOT EXISTS} this class issues itself, never routed through the
 * generator's {@code internalTables} catalog.
 */
public final class MigrationClaimStore {

    static final String TABLE = "npdev_schema_migration_claim";
    private static final String CLAIM_KEY = "schema-migration";

    private MigrationClaimStore() {
    }

    public record Claim(String claimKey, String instanceId, String hostname, long claimedAtUtc) {
    }

    static void ensureTable(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS " + TABLE
                        + " (claim_key TEXT PRIMARY KEY, instance_id TEXT NOT NULL, hostname TEXT, "
                        + "claimed_at_utc BIGINT NOT NULL)"
        )) {
            statement.executeUpdate();
        }
    }

    /**
     * Attempts to claim the single logical migration slot for THIS boot. Success returns the claim
     * (to later {@link #release}); failure (a row already exists -- someone else holds it) throws,
     * naming the existing claimant and pointing at the clear-claim escape hatch. Never silently
     * proceeds: an unreachable/broken claim table fails the boot loudly too (unlike the read-only
     * stores in this package, a collision guard that fails open is worse than no guard at all).
     */
    static Claim claim(DataSource dataSource) {
        String instanceId = UUID.randomUUID().toString();
        String hostname = localHostname();
        long claimedAtUtc = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection()) {
            ensureTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + TABLE + " (claim_key, instance_id, hostname, claimed_at_utc) VALUES (?, ?, ?, ?)"
            )) {
                statement.setString(1, CLAIM_KEY);
                statement.setString(2, instanceId);
                statement.setString(3, hostname);
                statement.setLong(4, claimedAtUtc);
                statement.executeUpdate();
            }
            return new Claim(CLAIM_KEY, instanceId, hostname, claimedAtUtc);
        } catch (SQLException insertFailed) {
            Optional<Claim> holder = current(dataSource);
            String holderDescription = holder
                    .map(claim -> "instance " + claim.instanceId() + " on host " + claim.hostname()
                            + ", claimed at epoch-ms " + claim.claimedAtUtc())
                    .orElse("an unknown instance (the claim row could not be read back)");
            throw new IllegalStateException("Another NPDev instance is currently migrating this database ("
                    + holderDescription + "). Concurrent schema migrations are not supported (REG-7.3) -- wait "
                    + "for it to finish and retry, or if it crashed mid-migration, clear the stale claim via "
                    + "POST /api/admin/schema-migration/clear-claim (SUPERUSER) or the ControlPanel schema-migration "
                    + "screen. Clearing a claim while another instance genuinely holds it re-introduces the race "
                    + "-- that is an operator decision. See docs/SCHEMA_EVOLUTION.md#collision-detection.",
                    insertFailed);
        }
    }

    /** Releases a claim THIS boot holds (matches on both the fixed key and the specific instance id,
     * so a release never removes a DIFFERENT instance's claim). A failure here is logged but never
     * propagated -- the migration this claim protected has already finished by the time this runs. */
    static void release(DataSource dataSource, String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM " + TABLE + " WHERE claim_key = ? AND instance_id = ?")) {
            statement.setString(1, CLAIM_KEY);
            statement.setString(2, instanceId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            System.out.println("NPDev schema lifecycle: failed releasing migration claim for instance " + instanceId
                    + " (the migration it protected already finished -- only this cleanup write failed): "
                    + exception.getMessage());
        }
    }

    /** The current claim, if any. Never throws -- an unreachable/missing table means "no claim". */
    public static Optional<Claim> current(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            ensureTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT claim_key, instance_id, hostname, claimed_at_utc FROM " + TABLE + " WHERE claim_key = ?")) {
                statement.setString(1, CLAIM_KEY);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Claim(
                            resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getLong(4)));
                }
            }
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    /** REG-7.3's manual escape hatch (D3): unconditionally deletes the claim row regardless of who
     * holds it -- for the crashed-holder case. The caller (ControlPanel, SUPERUSER-gated) is trusting
     * the operator's judgment that the held claim is genuinely stale. */
    public static void clear(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            ensureTable(connection);
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + TABLE + " WHERE claim_key = ?")) {
                statement.setString(1, CLAIM_KEY);
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed clearing the migration claim", exception);
        }
    }

    private static String localHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException | RuntimeException exception) {
            return "unknown-host";
        }
    }
}
