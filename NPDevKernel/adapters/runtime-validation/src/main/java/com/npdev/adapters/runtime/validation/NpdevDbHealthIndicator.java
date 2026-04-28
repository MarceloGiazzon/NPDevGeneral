package com.npdev.adapters.runtime.validation;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public final class NpdevDbHealthIndicator implements HealthIndicator {
    private final RuntimeSettings settings;
    private final DataSource dataSource;

    public NpdevDbHealthIndicator(RuntimeSettings settings, DataSource dataSource) {
        this.settings = settings;
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        if (settings == null || !settings.isPostgresMode()) {
            return Health.up()
                    .withDetail("mode", settings == null ? "unknown" : settings.mode())
                    .withDetail("database", "not-required")
                    .build();
        }
        if (dataSource == null) {
            return Health.down().withDetail("reason", "missing_datasource").build();
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1");
             ResultSet rs = statement.executeQuery()) {
            if (!rs.next() || rs.getInt(1) != 1) {
                return Health.down().withDetail("reason", "select_1_failed").build();
            }
            return Health.up()
                    .withDetail("mode", settings.mode())
                    .withDetail("database", "reachable")
                    .build();
        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("mode", settings.mode())
                    .withDetail("reason", "db_unreachable")
                    .build();
        }
    }
}
