package com.npdev.adapters.bulkhead.postgres;

import com.npdev.kernel.capability.CapabilityOpKey;
import com.npdev.kernel.ports.BulkheadStore;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class PostgresAdvisoryBulkheadStore implements BulkheadStore {
    private final DataSource dataSource;
    private final ThreadLocal<Map<CapabilityOpKey, ArrayDeque<HeldLock>>> heldLocks = ThreadLocal.withInitial(HashMap::new);

    public PostgresAdvisoryBulkheadStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public boolean tryAcquire(CapabilityOpKey key, int maxConcurrent, long nowMs) {
        Objects.requireNonNull(key, "key");
        int safeMax = maxConcurrent <= 0 ? 1 : maxConcurrent;
        for (int slot = 0; slot < safeMax; slot++) {
            String lockName = lockName(key, slot);
            Connection connection = tryAcquireLock(lockName);
            if (connection != null) {
                heldLocks.get()
                        .computeIfAbsent(key, ignored -> new ArrayDeque<>())
                        .push(new HeldLock(lockName, connection));
                return true;
            }
        }
        return false;
    }

    @Override
    public void release(CapabilityOpKey key) {
        if (key == null) {
            return;
        }
        Map<CapabilityOpKey, ArrayDeque<HeldLock>> local = heldLocks.get();
        ArrayDeque<HeldLock> stack = local.get(key);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        HeldLock heldLock = stack.pop();
        if (stack.isEmpty()) {
            local.remove(key);
        }
        if (local.isEmpty()) {
            heldLocks.remove();
        }
        unlock(heldLock);
    }

    private Connection tryAcquireLock(String lockName) {
        SqlDialect dialect = SqlDialects.active();
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(dialect.tryAdvisoryLockSql());
            try (statement) {
                statement.setObject(1, dialect.advisoryLockKey(lockName));
                try (ResultSet resultSet = statement.executeQuery()) {
                    // The dialect normalises every engine's answer to 1/0 -- Postgres returns a
                    // boolean, MySQL 1/0/NULL, SQL Server a procedure code that is >= 0 on success.
                    if (resultSet.next() && resultSet.getInt(1) == 1) {
                        return connection;
                    }
                    connection.close();
                    return null;
                }
            }
        } catch (SQLException exception) {
            closeQuietly(connection);
            throw new IllegalStateException("Failed to acquire advisory bulkhead lock", exception);
        }
    }

    private void unlock(HeldLock heldLock) {
        SqlDialect dialect = SqlDialects.active();
        try (Connection connection = heldLock.connection();
             PreparedStatement statement = connection.prepareStatement(dialect.releaseAdvisoryLockSql())) {
            statement.setObject(1, dialect.advisoryLockKey(heldLock.lockName()));
            statement.executeQuery();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to release advisory bulkhead lock", exception);
        }
    }

    /**
     * The logical lock name. Hashing it to whatever the engine keys locks by is the DIALECT's job
     * now (R9.3): Postgres needs a bigint, MySQL and SQL Server take the name as-is, and this class
     * spelled `pg_try_advisory_lock` inline for all of them. The FNV-1a 64-bit derivation this class
     * used to do here moved to PostgresDialect.advisoryLockKey UNCHANGED, so every lock id stays
     * byte-identical to what a running system already holds.
     */
    private static String lockName(CapabilityOpKey key, int slot) {
        return key.tenantId() + "|" + key.capabilityName() + "|" + key.operationName() + "|" + slot;
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Preserve the original acquisition failure.
        }
    }

    private record HeldLock(String lockName, Connection connection) {
    }
}
