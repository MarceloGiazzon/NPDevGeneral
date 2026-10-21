package com.finalexec.db;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SideChannelPoolTest {

    @Test
    void sideChannelPoolHoldsNoIdleConnectionsUntilUsed() {
        try (HikariDataSource primary = new HikariDataSource()) {
            primary.setJdbcUrl("jdbc:h2:mem:side-channel-pool-test;DB_CLOSE_DELAY=-1");
            primary.setUsername("sa");
            primary.setPassword("");
            primary.setInitializationFailTimeout(-1);

            SideChannelPool pool = new SideChannelPool(primary, 10);
            try {
                HikariDataSource side = (HikariDataSource) pool.dataSource();
                assertNotNull(side);
                assertEquals(10, side.getMaximumPoolSize());
                // Default would be maximumPoolSize: ten idle connections per Spring context on top of the main pool.
                assertEquals(0, side.getMinimumIdle());
            } finally {
                pool.destroy();
            }
        }
    }

    @Test
    void nonHikariPrimaryIsSharedAsIs() {
        DataSource primary = new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:side-channel-shared");
        SideChannelPool pool = new SideChannelPool(primary, 10);
        assertSame(primary, pool.dataSource());
        pool.destroy();
    }
}
