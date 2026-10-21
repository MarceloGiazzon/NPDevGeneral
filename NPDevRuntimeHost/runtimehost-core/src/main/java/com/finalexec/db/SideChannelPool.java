package com.finalexec.db;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;

import javax.sql.DataSource;

/**
 * A small second connection pool, onto the same database, for the kernel's own bookkeeping stores
 * (events, flow instances, correlation ownership, traces, audit, idempotency, circuit state).
 *
 * <p>Those stores each take their own connection while the request that triggered them is inside a
 * transaction holding one from the main pool. With one shared pool, N concurrent writes (N >= pool
 * size) each hold a connection and queue for a second until the 30 s pool timeout, a deadlock the
 * LOAD-2 harness hit at 10 parallel creates (threads parked in {@code JdbcAuditLogStore.append},
 * {@code JdbcCorrelationOwnershipStore.findTenantByCorrelationId}). The stores already ran on
 * connections separate from the request's transaction, so moving them to their own pool changes no
 * transactional behaviour; it only stops the two kinds of holder waiting on each other. A store call
 * never nests another, so this pool cannot deadlock itself.
 *
 * <p>When the main data source is not a {@link HikariDataSource} there is nothing to copy settings
 * from, and the main data source is shared as before.
 */
public final class SideChannelPool implements DisposableBean {

    private final DataSource dataSource;
    private final HikariDataSource owned;

    public SideChannelPool(DataSource primary, int maxPoolSize) {
        if (primary instanceof HikariDataSource hikari && hikari.getJdbcUrl() != null) {
            HikariDataSource side = new HikariDataSource();
            side.setPoolName("npdev-side-channel");
            side.setJdbcUrl(hikari.getJdbcUrl());
            side.setUsername(hikari.getUsername());
            side.setPassword(hikari.getPassword());
            if (hikari.getDriverClassName() != null && !hikari.getDriverClassName().isBlank()) {
                side.setDriverClassName(hikari.getDriverClassName());
            }
            side.setMaximumPoolSize(Math.max(2, maxPoolSize));
            this.owned = side;
            this.dataSource = side;
        } else {
            this.owned = null;
            this.dataSource = primary;
        }
    }

    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public void destroy() {
        if (owned != null) {
            owned.close();
        }
    }
}
