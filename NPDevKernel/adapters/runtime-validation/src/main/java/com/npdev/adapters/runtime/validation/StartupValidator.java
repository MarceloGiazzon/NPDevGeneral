package com.npdev.adapters.runtime.validation;

import com.npdev.dsl.v1.compiled.CompiledCapabilityCall;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.FlowInstanceStore;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class StartupValidator implements InitializingBean {
    private static final String CONFIG_DOC = "docs/CONFIGURATION.md";
    private static final String MODE_AND_PROFILE_ANCHOR = "mode-and-profile-contract";
    private static final String API_SAFETY_ANCHOR = "request-and-runtime-safety-limits";
    private static final String SCHEDULER_ANCHOR = "scheduler-settings";
    private static final String AUTH_ANCHOR = "authentication";
    private static final String POSTGRES_ANCHOR = "postgres-mode-required-variables";
    private static final String CAPABILITY_BINDING_ANCHOR = "persistence-capability-binding-checked-at-boot";
    private static final String IDENTITY_PACK_ANCHOR = "identity-pack-freshness-checked-at-boot";
    private static final String IDENTITY_USER_CONCEPT = "identity::User";
    private static final String TOKEN_VERSION_FIELD = "tokenVersion";

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
    private final String jwtPrivateKeyPath;
    private final CompiledModel compiledModel;
    private final CapabilityRegistry capabilityRegistry;
    private final ResourceLoader resourceLoader;

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
            String jwtPublicKeyPath,
            String jwtPrivateKeyPath
    ) {
        this(settings, dataSource, eventStore, flowInstanceStore, environment, authMode, apiKeyMappings,
                jwtIssuer, jwtAudience, jwtPublicKeyPath, jwtPrivateKeyPath, null, null);
    }

    // LEDGER-1: overload adding compiledModel/capabilityRegistry for the persistence-binding
    // boot-time check. Both null is equivalent to the 11-arg constructor above (check skipped) --
    // kept separate rather than folding into one signature so existing callers/tests are untouched.
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
            String jwtPublicKeyPath,
            String jwtPrivateKeyPath,
            CompiledModel compiledModel,
            CapabilityRegistry capabilityRegistry
    ) {
        this(settings, dataSource, eventStore, flowInstanceStore, environment, authMode, apiKeyMappings,
                jwtIssuer, jwtAudience, jwtPublicKeyPath, jwtPrivateKeyPath, compiledModel, capabilityRegistry,
                new DefaultResourceLoader());
    }

    // Package-visible for tests: lets a test inject a ResourceLoader (and thus point key paths at
    // fixtures) without touching the real classpath/filesystem.
    StartupValidator(
            RuntimeSettings settings,
            DataSource dataSource,
            EventStore eventStore,
            FlowInstanceStore flowInstanceStore,
            Environment environment,
            String authMode,
            String apiKeyMappings,
            String jwtIssuer,
            String jwtAudience,
            String jwtPublicKeyPath,
            String jwtPrivateKeyPath,
            CompiledModel compiledModel,
            CapabilityRegistry capabilityRegistry,
            ResourceLoader resourceLoader
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
        this.jwtPrivateKeyPath = jwtPrivateKeyPath;
        this.compiledModel = compiledModel;
        this.capabilityRegistry = capabilityRegistry;
        this.resourceLoader = resourceLoader == null ? new DefaultResourceLoader() : resourceLoader;
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
        validatePersistenceBinding();
        validateIdentityPackFreshness();
    }

    // LEDGER-1: a model whose flows persist (createConcept/updateConcept/saveConcept, or a direct
    // persistence.* capabilityCall -- all of these compile down to a CompiledFlowStep whose
    // capabilityCall.capabilityName is "persistence") but has no bound persistence adapter currently
    // 500s opaquely on the first such call (RegistryCapabilityDispatcher returns a structured
    // CAPABILITY_BINDING_MISSING failure that a flow step turns into an uncaught exception -> bare
    // Spring 500; the clear message reaches only stdout). Refuse to boot instead, naming the flow and
    // the fix. compiledModel/capabilityRegistry are null for callers that don't wire them (skip check).
    private void validatePersistenceBinding() {
        if (compiledModel == null || capabilityRegistry == null) {
            return;
        }
        if (capabilityRegistry.has("persistence")) {
            return;
        }
        String offendingFlow = findFlowReferencingPersistence(compiledModel);
        if (offendingFlow == null) {
            return;
        }
        throw configError(
                "Model flow '" + offendingFlow + "' persists via capability 'persistence' but no "
                        + "persistence capability binding is registered. Declare a binding "
                        + "(persistence-inproc for dev / persistence-postgres for prod).",
                CAPABILITY_BINDING_ANCHOR
        );
    }

    private static String findFlowReferencingPersistence(CompiledModel model) {
        for (CompiledFlow flow : model.getFlows()) {
            if (stepsReferencePersistence(flow.getSteps())) {
                return flow.getName();
            }
        }
        return null;
    }

    // REG-39: an app can carry its OWN copy of a built-in pack (a local $ref under its model root,
    // rather than composing NPDevContract/packs/<alias>/pack.json fresh via BuiltinPackComposer at
    // every generation). When the platform's pack gains a field that platform code (LoginController,
    // PasswordResetController, ControlPanelTenantUsersController, IdentityRoleLookup) then reads
    // unconditionally, every app whose copy predates that addition breaks -- and breaks misleadingly,
    // as a swallowed SQLException masquerading as invalid_credentials, not as a schema error (this is
    // exactly what happened to WmsOffice: its local identity-pack copy predated the LNCH-4
    // tokenVersion field). compiledModel already carries the FULLY MERGED, pack-namespaced concept set
    // ("identity::User" etc.) regardless of which mechanism contributed it, so this check needs no new
    // generation-time plumbing: if this app declares an "identity::User" concept at all, it must carry
    // the tokenVersion field the platform's current identity pack has had since LNCH-4. An app that
    // doesn't use the identity pack at all has no "identity::User" concept -- skip, nothing to check.
    private void validateIdentityPackFreshness() {
        if (compiledModel == null) {
            return;
        }
        CompiledConcept identityUser = null;
        for (CompiledConcept concept : compiledModel.getConcepts()) {
            if (IDENTITY_USER_CONCEPT.equalsIgnoreCase(concept.getName())) {
                identityUser = concept;
                break;
            }
        }
        if (identityUser == null) {
            return;
        }
        boolean hasTokenVersion = identityUser.getFields().stream()
                .anyMatch(field -> TOKEN_VERSION_FIELD.equalsIgnoreCase(field.getName()));
        if (!hasTokenVersion) {
            throw configError(
                    "This app's copy of the built-in 'identity' pack is STALE: concept '" + IDENTITY_USER_CONCEPT
                            + "' is missing the '" + TOKEN_VERSION_FIELD + "' field the platform's identity pack "
                            + "has carried since LNCH-4. Left unfixed, this makes login fail with a misleading "
                            + "'invalid_credentials' error instead of this message (REG-39). Fix: regenerate this "
                            + "app so it composes the platform's CURRENT NPDevContract/packs/identity/pack.json "
                            + "(the normal path via BuiltinPackComposer), or bring any locally-committed copy of "
                            + "the identity pack up to date with it.",
                    IDENTITY_PACK_ANCHOR
            );
        }
    }

    private static boolean stepsReferencePersistence(List<CompiledFlowStep> steps) {
        if (steps == null) {
            return false;
        }
        for (CompiledFlowStep step : steps) {
            CompiledCapabilityCall capabilityCall = step.getCapabilityCall();
            if (capabilityCall != null && "persistence".equalsIgnoreCase(capabilityCall.getCapabilityName())) {
                return true;
            }
            if (stepsReferencePersistence(step.getThenSteps())
                    || stepsReferencePersistence(step.getElseSteps())
                    || stepsReferencePersistence(step.getLoopSteps())
                    || stepsReferencePersistence(step.getOnFailureSteps())) {
                return true;
            }
        }
        return false;
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
        // The public key is always required in jwt mode (both full and verify-only deployments
        // validate tokens with it), so confirm it actually resolves - a wrong path otherwise fails
        // per-request inside JwtBearerAuthFilter with an opaque jwt_public_key_not_found instead of
        // at startup. The private key is OPTIONAL: a blank path is a legitimate verify-only
        // deployment (REG-9) where this instance only validates externally-issued tokens; but a
        // path that IS set must resolve, or LoginController would otherwise crash the whole context
        // at bean creation with a raw NoSuchFileException the operator has to decode.
        validateJwtKeyReadable("npdev.auth.jwt.public-key-path", jwtPublicKeyPath, true);
        validateJwtKeyReadable("npdev.auth.jwt.private-key-path", jwtPrivateKeyPath, false);
    }

    private void validateJwtKeyReadable(String propertyName, String path, boolean required) {
        String normalized = normalize(path);
        if (normalized == null) {
            if (required) {
                throw configError(propertyName + " is required when npdev.auth.mode=jwt", AUTH_ANCHOR);
            }
            // Optional (verify-only) key intentionally omitted - nothing to check.
            return;
        }
        boolean readable;
        try {
            if (normalized.startsWith("classpath:")) {
                Resource resource = resourceLoader.getResource(normalized);
                readable = resource.exists() && resource.isReadable();
            } else {
                String bare = normalized.startsWith("file:") ? normalized.substring("file:".length()) : normalized;
                Path keyPath = Path.of(bare);
                readable = Files.exists(keyPath) && Files.isReadable(keyPath);
            }
        } catch (Exception ex) {
            throw configError(propertyName + " could not be resolved: " + ex.getMessage(), AUTH_ANCHOR, ex);
        }
        if (!readable) {
            throw configError(propertyName + "='" + normalized + "' does not point at a readable key file", AUTH_ANCHOR);
        }
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
