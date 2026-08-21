package com.npdev.test.postgres;

import org.junit.jupiter.api.Assumptions;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Objects;

public final class PostgresTestSupport {
    private static final String IMAGE = "postgres:15-alpine";
    private static final PostgreSQLContainer<?> POSTGRES = createContainer();

    private PostgresTestSupport() {
    }

    public static DataSource dataSource() {
        Assumptions.assumeTrue(postgresEnabled(),
                "Postgres disabled locally (scripts/policy/local-test-profile.json) -- "
                        + "set NPDEV_TEST_PROFILE_ENGINES=postgres to opt in, or run with CI=true");
        return DataSourceHolder.DATA_SOURCE;
    }

    private static boolean postgresEnabled() {
        if ("true".equalsIgnoreCase(System.getenv("CI"))) {
            return true;
        }
        String override = System.getenv("NPDEV_TEST_PROFILE_ENGINES");
        if (override == null) {
            return false;
        }
        return Arrays.stream(override.split(","))
                .map(String::trim)
                .anyMatch(engine -> engine.equalsIgnoreCase("postgres"));
    }

    public static void execute(DataSource dataSource, String... statements) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String raw : statements) {
                String sql = raw == null ? "" : raw.trim();
                if (!sql.isEmpty()) {
                    statement.execute(sql);
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed executing Postgres test SQL", exception);
        }
    }

    public static void truncate(DataSource dataSource, String... tableNames) {
        Objects.requireNonNull(dataSource, "dataSource");
        if (tableNames == null || tableNames.length == 0) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String tableName : tableNames) {
                String normalized = normalizeTableName(tableName);
                statement.execute("TRUNCATE TABLE " + normalized + " RESTART IDENTITY CASCADE");
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed truncating Postgres test tables", exception);
        }
    }

    public static String jdbcUrlForEvidence() {
        return POSTGRES.getJdbcUrl();
    }

    private static DataSource createDataSource() {
        POSTGRES.start();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setServerNames(new String[]{POSTGRES.getHost()});
        dataSource.setPortNumbers(new int[]{POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)});
        dataSource.setDatabaseName(POSTGRES.getDatabaseName());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private static PostgreSQLContainer<?> createContainer() {
        configureDockerHost();
        return new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName("npdev_kernel_test")
                .withUsername("npdev")
                .withPassword("npdev")
                .withReuse(true);
    }

    private static void configureDockerHost() {
        DockerHostConfiguration configuration = resolveDockerHostConfiguration(
                System.getProperty("os.name", ""),
                System.getenv("DOCKER_HOST"),
                System.getProperty("docker.host")
        );
        if (configuration.dockerHost() != null) {
            System.setProperty("docker.host", configuration.dockerHost());
        }
        if (configuration.clientStrategy() != null) {
            System.setProperty("docker.client.strategy", configuration.clientStrategy());
        }
    }

    static DockerHostConfiguration resolveDockerHostConfiguration(
            String osName,
            String dockerHostEnvironment,
            String dockerHostProperty
    ) {
        if (isPresent(dockerHostEnvironment) || isPresent(dockerHostProperty)) {
            return DockerHostConfiguration.autoDiscovery();
        }
        if (osName != null && osName.toLowerCase().contains("win")) {
            return new DockerHostConfiguration(
                    "npipe:////./pipe/dockerDesktopLinuxEngine",
                    "org.testcontainers.dockerclient.NpipeSocketClientProviderStrategy"
            );
        }
        return DockerHostConfiguration.autoDiscovery();
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizeTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName must be non-blank");
        }
        String trimmed = tableName.trim();
        if (!trimmed.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Unsafe table name: " + tableName);
        }
        return trimmed;
    }

    private static final class DataSourceHolder {
        private static final DataSource DATA_SOURCE = createDataSource();

        private DataSourceHolder() {
        }
    }

    record DockerHostConfiguration(String dockerHost, String clientStrategy) {
        static DockerHostConfiguration autoDiscovery() {
            return new DockerHostConfiguration(null, null);
        }
    }
}
