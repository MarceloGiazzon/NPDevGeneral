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
    private final ThreadLocal<Map<CapabilityOpKey, ArrayDeque<Long>>> heldLocks = ThreadLocal.withInitial(HashMap::new);

    public PostgresAdvisoryBulkheadStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public boolean tryAcquire(CapabilityOpKey key, int maxConcurrent, long nowMs) {
        Objects.requireNonNull(key, "key");
        int safeMax = maxConcurrent <= 0 ? 1 : maxConcurrent;
        for (int slot = 0; slot < safeMax; slot++) {
            long lockId = lockId(key, slot);
            if (tryAcquireLock(lockId)) {
                heldLocks.get()
                        .computeIfAbsent(key, ignored -> new ArrayDeque<>())
                        .push(lockId);
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
        Map<CapabilityOpKey, ArrayDeque<Long>> local = heldLocks.get();
        ArrayDeque<Long> stack = local.get(key);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        Long lockId = stack.pop();
        if (stack.isEmpty()) {
            local.remove(key);
        }
        if (local.isEmpty()) {
            heldLocks.remove();
        }
        unlock(lockId);
    }

    private boolean tryAcquireLock(long lockId) {
        String sql = "SELECT pg_try_advisory_lock(?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, lockId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to acquire advisory bulkhead lock", exception);
        }
    }

    private void unlock(long lockId) {
        String sql = "SELECT pg_advisory_unlock(?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, lockId);
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
}
