package com.npdev.adapters.bulkhead.postgres;

import com.npdev.kernel.capability.CapabilityOpKey;
import com.npdev.kernel.ports.BulkheadStore;

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
            long lockId = lockId(key, slot);
            Connection connection = tryAcquireLock(lockId);
            if (connection != null) {
                heldLocks.get()
                        .computeIfAbsent(key, ignored -> new ArrayDeque<>())
                        .push(new HeldLock(lockId, connection));
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

    private Connection tryAcquireLock(long lockId) {
        String sql = "SELECT pg_try_advisory_lock(?)";
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql);
            try (statement) {
                statement.setLong(1, lockId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next() && resultSet.getBoolean(1)) {
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
        String sql = "SELECT pg_advisory_unlock(?)";
        try (Connection connection = heldLock.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, heldLock.lockId());
            statement.executeQuery();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to release advisory bulkhead lock", exception);
        }
    }

    private static long lockId(CapabilityOpKey key, int slot) {
        return stableHash64(key.tenantId() + "|" + key.capabilityName() + "|" + key.operationName() + "|" + slot);
    }

    // FNV-1a 64-bit hash provides stable key derivation across process restarts.
    private static long stableHash64(String input) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < input.length(); index++) {
            hash ^= input.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
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

    private record HeldLock(long lockId, Connection connection) {
    }
}
