package com.npdev.adapters.runtime.validation;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.FlowInstanceStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupValidatorTest {

    @Test
    void shouldFailWhenSchedulerConfigIsInvalid() {
        RuntimeSettings settings = new RuntimeSettings(
                "inproc",
                true,
                0,
                0,
                false,
                1024,
                64,
                null,
                null,
                null,
                5,
                30,
                10,
                4096,
                null
        );
        StartupValidator validator = new StartupValidator(
                settings,
                null,
                eventStore(),
                flowInstanceStore(),
                new MockEnvironment(),
                "apikey",
                "dev-key=tenant:actor:USER",
                null,
                null,
                null,
                null
        );

        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void shouldFailWhenAuthEnabledAndMappingsMissing() {
        RuntimeSettings settings = new RuntimeSettings(
                "inproc",
                false,
                100,
                2000,
                true,
                1024,
                64,
                null,
                null,
                null,
                5,
                30,
                10,
                4096,
                null
        );
        StartupValidator validator = new StartupValidator(
                settings,
                null,
                eventStore(),
                flowInstanceStore(),
                new MockEnvironment(),
                "apikey",
                "",
                null,
                null,
                null,
                null
        );

        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void shouldFailWhenPostgresModeWithoutDatasourceProperties() {
        RuntimeSettings settings = new RuntimeSettings(
                "postgres",
                false,
                100,
                2000,
                false,
                1024,
                64,
                null,
                null,
                null,
                5,
                30,
                10,
                4096,
                null
        );
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("postgres");

        StartupValidator validator = new StartupValidator(
                settings,
                null,
                eventStore(),
                flowInstanceStore(),
                environment,
                "apikey",
                "dev-key=tenant:actor:USER",
                null,
                null,
                null,
                null
        );

        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void shouldPointToConfigurationDocWhenValidationFails() {
        RuntimeSettings settings = new RuntimeSettings(
                "postgres",
                false,
                100,
                2000,
                true,
                1024,
                64,
                null,
                null,
                null,
                5,
                30,
                10,
                4096,
                null
        );
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("postgres");

        StartupValidator validator = new StartupValidator(
                settings,
                null,
                eventStore(),
                flowInstanceStore(),
                environment,
                "apikey",
                "admin-key=tenant:actor:ADMIN",
                null,
                null,
                null,
                null
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(
                exception.getMessage() != null
                        && exception.getMessage().contains("CONFIGURATION.md#postgres-mode-required-variables"),
                "Expected validation message to reference CONFIGURATION.md postgres section"
        );
    }

    @Test
    void shouldPassWithValidPostgresConnectivityAndFlywayHistory() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:runtime_validation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("sa");

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE flyway_schema_history (installed_rank INT PRIMARY KEY, version VARCHAR(50), description VARCHAR(200), success BOOLEAN)");
            statement.execute("INSERT INTO flyway_schema_history(installed_rank, version, description, success) VALUES (1, '1', 'init', TRUE)");
        }

        RuntimeSettings settings = new RuntimeSettings(
                "postgres",
                true,
                100,
                2000,
                true,
                1024,
                64,
                "jdbc:h2:mem:runtime_validation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "sa",
                5,
                30,
                10,
                4096,
                null
        );

        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("postgres");

        StartupValidator validator = new StartupValidator(
                settings,
                dataSource,
                eventStore(),
                flowInstanceStore(),
                environment,
                "apikey",
                "dev-key=tenant:actor:ADMIN",
                null,
                null,
                null,
                null
        );

        assertDoesNotThrow(validator::validate);
    }

    @Test
    void shouldFailWhenJwtModeConfigurationIsMissing() {
        RuntimeSettings settings = new RuntimeSettings(
                "inproc",
                false,
                100,
                2000,
                true,
                1024,
                64,
                null,
                null,
                null,
                5,
                30,
                10,
                4096,
                null
        );

        StartupValidator validator = new StartupValidator(
                settings,
                null,
                eventStore(),
                flowInstanceStore(),
                new MockEnvironment(),
                "jwt",
                "",
                "",
                "",
                "",
                null
        );

        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void shouldFailWhenJwtModeUsesPlaceholderIssuer() {
        RuntimeSettings settings = new RuntimeSettings(
                "inproc",
                false,
                100,
                2000,
                true,
                1024,
                64,
                null,
                null,
                null,
                5,
                30,
                10,
                4096,
                null
        );

        StartupValidator validator = new StartupValidator(
                settings,
                null,
                eventStore(),
                flowInstanceStore(),
                new MockEnvironment(),
                "jwt",
                "",
                "https://your-auth-provider.example.com",
                "npdev-runtime",
                "classpath:npdev/security/test-jwt-public.pem",
                null
        );

        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void shouldAllowJwtModeWithExplicitSettings() {
        RuntimeSettings settings = new RuntimeSettings(
                "inproc",
                false,
                100,
                2000,
                true,
                1024,
                64,
                null,
                null,
                null,
                5,
                30,
                10,
                4096,
                null
        );

        StartupValidator validator = new StartupValidator(
                settings,
                null,
                eventStore(),
                flowInstanceStore(),
                new MockEnvironment(),
                "jwt",
                "",
                "https://issuer.npdev.test",
                "npdev-runtime",
                "classpath:npdev/security/test-jwt-public.pem",
                null
        );

        assertDoesNotThrow(validator::validate);
    }

    @Test
    void shouldAllowVerifyOnlyJwtWithNoPrivateKey() {
        // REG-9: a verify-only deployment (external-beta) validates externally-issued tokens with
        // the public key and never mints its own, so a blank private-key-path is legitimate and
        // must not fail startup.
        StartupValidator validator = new StartupValidator(
                jwtSettings(),
                null,
                eventStore(),
                flowInstanceStore(),
                new MockEnvironment(),
                "jwt",
                "",
                "https://issuer.npdev.test",
                "npdev-runtime",
                "classpath:npdev/security/test-jwt-public.pem",
                ""
        );

        assertDoesNotThrow(validator::validate);
    }

    @Test
    void shouldAllowFullJwtWithReadablePrivateKey() {
        StartupValidator validator = new StartupValidator(
                jwtSettings(),
                null,
                eventStore(),
                flowInstanceStore(),
                new MockEnvironment(),
                "jwt",
                "",
                "https://issuer.npdev.test",
                "npdev-runtime",
                "classpath:npdev/security/test-jwt-public.pem",
                "classpath:npdev/security/test-jwt-private.pem"
        );

        assertDoesNotThrow(validator::validate);
    }

    @Test
    void shouldFailWhenJwtPublicKeyPathIsUnreadable() {
        StartupValidator validator = new StartupValidator(
                jwtSettings(),
                null,
                eventStore(),
                flowInstanceStore(),
                new MockEnvironment(),
                "jwt",
                "",
                "https://issuer.npdev.test",
                "npdev-runtime",
                "classpath:npdev/security/does-not-exist.pem",
                ""
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("public-key-path"),
                "Expected the message to name the unreadable public-key-path");
        assertTrue(ex.getMessage().contains("CONFIGURATION.md#authentication"),
                "Expected the message to link the authentication config doc");
    }

    @Test
    void shouldFailWhenJwtPrivateKeyPathIsSetButUnreadable() {
        // A set-but-broken signing key must fail fast at startup with a clear message, not deep in
        // LoginController's bean creation with a raw NoSuchFileException.
        StartupValidator validator = new StartupValidator(
                jwtSettings(),
                null,
                eventStore(),
                flowInstanceStore(),
                new MockEnvironment(),
                "jwt",
                "",
                "https://issuer.npdev.test",
                "npdev-runtime",
                "classpath:npdev/security/test-jwt-public.pem",
                "classpath:npdev/security/does-not-exist.pem"
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("private-key-path"),
                "Expected the message to name the unreadable private-key-path");
    }

    private static RuntimeSettings jwtSettings() {
        return new RuntimeSettings(
                "inproc",
                false,
                100,
                2000,
                true,
                1024,
                64,
                null,
                null,
                null,
                5,
                30,
                10,
                4096,
                null
        );
    }

    private static EventStore eventStore() {
        return new EventStore() {
            @Override
            public void append(EventEnvelope event) {
            }

            @Override
            public List<EventEnvelope> readByCorrelation(String correlationId) {
                return List.of();
            }

            @Override
            public List<EventEnvelope> readByEventName(String eventName) {
                return List.of();
            }
        };
    }

    private static FlowInstanceStore flowInstanceStore() {
        return new FlowInstanceStore() {
            @Override
            public void save(FlowInstance instance) {
            }

            @Override
            public void update(FlowInstance instance) {
            }

            @Override
            public Optional<FlowInstance> findByExecutionId(String executionId) {
                return Optional.empty();
            }

            @Override
            public List<FlowInstance> findWaitingByCorrelation(String correlationId) {
                return List.of();
            }

            @Override
            public List<FlowInstance> findWaitingByEvent(String eventName) {
                return List.of();
            }

            @Override
            public List<FlowInstance> findAllWaiting(int limit) {
                return List.of();
            }

            @Override
            public List<FlowInstance> findWaitingEligibleToResume(String tenantId, long nowEpochMs, int limit) {
                return List.of();
            }

            @Override
            public List<FlowInstance> findStaleWaiting(String tenantId, long olderThanEpochMs, int limit, int offset) {
                return List.of();
            }

            @Override
            public List<com.npdev.kernel.execution.FlowInstance> findRecent(String tenantId, int limit, int offset) {
                return List.of();
            }

            @Override
            public List<com.npdev.kernel.execution.FlowInstance> findWaiting(String tenantId, int limit, int offset) {
                return List.of();
            }

            @Override
            public List<com.npdev.kernel.execution.FlowInstance> findByCorrelationId(String tenantId, String correlationId) {
                return List.of();
            }
        };
    }
}
