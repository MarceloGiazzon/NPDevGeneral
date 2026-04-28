package com.npdev.adapters.runtime.validation;

import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.FlowInstanceStore;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

public final class StartupValidator implements InitializingBean {
    private static final String CONFIG_DOC = "docs/CONFIGURATION.md";
    private static final String MODE_AND_PROFILE_ANCHOR = "mode-and-profile-contract";
    private static final String API_SAFETY_ANCHOR = "request-and-runtime-safety-limits";
    private static final String SCHEDULER_ANCHOR = "scheduler-settings";
    private static final String AUTH_ANCHOR = "authentication";
    private static final String POSTGRES_ANCHOR = "postgres-mode-required-variables";

    private final RuntimeSettings settings;
    private final DataSource dataSource;
    private final EventStore eventStore;
    private final FlowInstanceStore flowInstanceStore;
    private final Environment environment;
    private final String authMode;
    private final String apiKeyMappings;
    private final String jwtIssuer;
    private final String jwtAudience;
    private final String jwtPublicKeyPath;

    public StartupValidator(
            RuntimeSettings settings,
            DataSource dataSource,
            EventStore eventStore,
            FlowInstanceStore flowInstanceStore,
            Environment environment,
            String authMode,
            String apiKeyMappings,
            String jwtIssuer,
            String jwtAudience,
            String jwtPublicKeyPath
    ) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.dataSource = dataSource;
        this.eventStore = eventStore;
        this.flowInstanceStore = flowInstanceStore;
        this.environment = environment;
        this.authMode = authMode;
        this.apiKeyMappings = apiKeyMappings;
        this.jwtIssuer = jwtIssuer;
        this.jwtAudience = jwtAudience;
        this.jwtPublicKeyPath = jwtPublicKeyPath;
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    public void validate() {
        validateModeAndProfiles();
        validateApiSafety();
        validateScheduler();
        validateAuth();
        if (settings.isPostgresMode()) {
            validatePostgres();
        }
    }

    private void validateModeAndProfiles() {
        String mode = normalize(settings.mode());
        if (mode == null) {
            throw configError("npdev.runtime.mode must be set to 'inproc' or 'postgres'", MODE_AND_PROFILE_ANCHOR);
        }
        if (!"inproc".equals(mode) && !"postgres".equals(mode)) {
            throw configError("npdev.runtime.mode must be either 'inproc' or 'postgres'", MODE_AND_PROFILE_ANCHOR);
        }
        if (environment == null) {
            return;
        }
        boolean postgresProfileActive = Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile == null ? "" : profile.trim().toLowerCase(Locale.ROOT))
                .anyMatch("postgres"::equals);
        if (settings.isPostgresMode() && !postgresProfileActive) {
            throw configError("npdev.runtime.mode=postgres requires active Spring profile 'postgres'", MODE_AND_PROFILE_ANCHOR);
        }
        if (!settings.isPostgresMode() && postgresProfileActive) {
            throw configError("Spring profile 'postgres' requires npdev.runtime.mode=postgres", MODE_AND_PROFILE_ANCHOR);
        }
    }

    private void validateApiSafety() {
        if (settings.apiMaxBodyBytes() <= 0) {
            throw configError("npdev.api.max-body-bytes must be > 0", API_SAFETY_ANCHOR);
        }
        if (settings.apiMaxJsonDepth() <= 0) {
            throw configError("npdev.api.max-json-depth must be > 0", API_SAFETY_ANCHOR);
        }
        if (settings.circuitOpenAfterFailures() <= 0) {
            throw configError("npdev.capability.circuit.open-after-failures must be > 0", API_SAFETY_ANCHOR);
        }
        if (settings.circuitOpenSeconds() <= 0) {
            throw configError("npdev.capability.circuit.open-seconds must be > 0", API_SAFETY_ANCHOR);
        }
        if (settings.bulkheadMaxConcurrentDefault() <= 0) {
            throw configError("npdev.capability.bulkhead.max-concurrent-default must be > 0", API_SAFETY_ANCHOR);
        }
        if (settings.idempotencyMaxBytes() <= 0) {
            throw configError("npdev.capability.idempotency.max-bytes must be > 0", API_SAFETY_ANCHOR);
        }
    }

    private void validateScheduler() {
        if (!settings.schedulerEnabled()) {
            return;
        }
        if (settings.schedulerBatchLimit() <= 0) {
            throw configError("npdev.scheduler.batch-limit must be > 0 when scheduler is enabled", SCHEDULER_ANCHOR);
        }
        if (settings.schedulerTickMillis() <= 0) {
            throw configError("npdev.scheduler.tick-millis must be > 0 when scheduler is enabled", SCHEDULER_ANCHOR);
        }
        if (eventStore == null) {
            throw configError("Scheduler requires EventStore bean", SCHEDULER_ANCHOR);
        }
        if (flowInstanceStore == null) {
            throw configError("Scheduler requires FlowInstanceStore bean", SCHEDULER_ANCHOR);
        }
        String flowStoreType = flowInstanceStore.getClass().getName();
        if (flowStoreType.contains("NoopHolder")) {
            throw configError("Scheduler cannot run with FlowInstanceStore.noop()", SCHEDULER_ANCHOR);
        }
    }

    private void validateAuth() {
        if (!settings.authEnabled()) {
            return;
        }
        String normalizedAuthMode = normalize(authMode);
        if (normalizedAuthMode == null) {
            normalizedAuthMode = "apikey";
        }
        if (!"apikey".equalsIgnoreCase(normalizedAuthMode) && !"jwt".equalsIgnoreCase(normalizedAuthMode)) {
            throw configError("npdev.auth.mode must be either 'apikey' or 'jwt' when auth is enabled", AUTH_ANCHOR);
        }
        if ("jwt".equalsIgnoreCase(normalizedAuthMode)) {
            validateJwtSettings();
            return;
        }
        String mappings = normalize(apiKeyMappings);
        if (mappings == null || !mappings.contains("=")) {
            throw configError("npdev.auth.api-keys must define at least one mapping when auth is enabled", AUTH_ANCHOR);
        }
    }

    private void validateJwtSettings() {
        validateJwtSetting("npdev.auth.jwt.issuer", jwtIssuer);
        validateJwtSetting("npdev.auth.jwt.audience", jwtAudience);
        validateJwtSetting("npdev.auth.jwt.public-key-path", jwtPublicKeyPath);
    }

    private void validateJwtSetting(String propertyName, String propertyValue) {
        String normalized = normalize(propertyValue);
        if (normalized == null) {
            throw configError(propertyName + " is required when npdev.auth.mode=jwt", AUTH_ANCHOR);
        }
        if (looksLikePlaceholder(normalized)) {
            throw configError(propertyName + " must not use placeholder/example values when npdev.auth.mode=jwt", AUTH_ANCHOR);
        }
    }

    private void validatePostgres() {
        if (normalize(settings.datasourceUrl()) == null) {
            throw configError("spring.datasource.url is required when mode=postgres", POSTGRES_ANCHOR);
        }
        if (normalize(settings.datasourceUser()) == null) {
            throw configError("spring.datasource.username is required when mode=postgres", POSTGRES_ANCHOR);
        }
        if (normalize(settings.datasourcePassword()) == null) {
            throw configError("spring.datasource.password is required when mode=postgres", POSTGRES_ANCHOR);
        }
        if (dataSource == null) {
            throw configError("DataSource bean is required when mode=postgres", POSTGRES_ANCHOR);
        }

        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement selectOne = connection.prepareStatement("SELECT 1")) {
                try (ResultSet rs = selectOne.executeQuery()) {
                    if (!rs.next() || rs.getInt(1) != 1) {
                        throw configError("Postgres connectivity check failed (SELECT 1)", POSTGRES_ANCHOR);
                    }
                }
            }
            boolean flywayHistoryExists;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_schema) = LOWER(CURRENT_SCHEMA()) AND LOWER(table_name) = 'flyway_schema_history'"
            )) {
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    flywayHistoryExists = rs.getInt(1) > 0;
                }
            }
            if (!flywayHistoryExists) {
                throw configError("Flyway schema history table not found in current schema", POSTGRES_ANCHOR);
            }
            try (PreparedStatement applied = connection.prepareStatement(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE"
            )) {
                try (ResultSet rs = applied.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) <= 0) {
                        throw configError("Flyway has no successful migrations in schema history", POSTGRES_ANCHOR);
                    }
                }
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw configError("Postgres startup validation failed: " + ex.getMessage(), POSTGRES_ANCHOR, ex);
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static boolean looksLikePlaceholder(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return true;
        }
        return normalized.contains("example.com")
                || normalized.contains("your-auth-provider")
                || normalized.contains("changeme")
                || normalized.contains("change-me")
                || normalized.contains("replace-me")
                || normalized.contains("set-me")
                || normalized.contains("<")
                || normalized.contains("todo");
    }

    private static IllegalStateException configError(String message, String anchor) {
        return new IllegalStateException(message + " (See " + CONFIG_DOC + "#" + anchor + ")");
    }

    private static IllegalStateException configError(String message, String anchor, Exception cause) {
        return new IllegalStateException(message + " (See " + CONFIG_DOC + "#" + anchor + ")", cause);
    }
}
