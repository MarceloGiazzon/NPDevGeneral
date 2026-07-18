package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.dsl.v1.compiled.SqlTypeSupport;
import com.npdev.kernel.dbschema.InternalColumnDefinition;
import com.npdev.kernel.dbschema.InternalTableDefinition;
import com.npdev.kernel.dbschema.NpdevInternalTables;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UserDatabaseDefinitionLoader {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter INSTANCE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    public GeneratedDatabasePlan load(Path definitionPath, CompiledModel model) throws Exception {
        if (definitionPath == null) {
            throw new IllegalArgumentException("--dbDefinitionPath is required");
        }
        Path normalizedPath = definitionPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedPath)) {
            throw new IllegalArgumentException("DB definition file not found: " + normalizedPath);
        }

        JsonNode root = OBJECT_MAPPER.readTree(normalizedPath.toFile());
        JsonNode database = root.path("database");
        JsonNode lifecycle = root.path("schemaLifecycle");
        DatabaseEngine engine = DatabaseEngine.parse(text(database, "engine"));
        SchemaLifecyclePolicy policy = new SchemaLifecyclePolicy(
                SchemaLifecycleStrategy.parse(text(lifecycle, "strategy")),
                bool(lifecycle, "allowDestructiveRecreate", false),
                text(lifecycle, "destructiveRecreateConfirmation"),
                text(lifecycle, "scope")
        );

        UserDatabaseDefinition definition = new UserDatabaseDefinition(
                engine,
                text(database, "host"),
                integer(database, "port", 0),
                text(database, "databaseName"),
                text(database, "schemaName"),
                text(database, "jdbcUrl"),
                text(database, "h2FilePath"),
                text(database, "username"),
                rawText(database, "password"),
                bool(database, "createInternalTables", true),
                bool(database, "createBusinessTables", true),
                policy
        );
        validate(definition);

        String appId = resolveAppId(normalizedPath);
        Path workspaceRoot = resolveWorkspaceRoot(normalizedPath);
        Path appDatabaseRoot = workspaceRoot.resolve("Build").resolve("databases").resolve(appId);
        DatabaseIdentity identity = resolveIdentity(definition, appId, appDatabaseRoot);
        String jdbcUrl = jdbcUrl(definition, identity);
        String driver = switch (engine) {
            case POSTGRES -> "org.postgresql.Driver";
            case H2_LOCAL, H2_SERVER -> "org.h2.Driver";
            case IN_MEMORY -> "";
        };
        List<String> fingerprintInputs = fingerprintInputs(definition, model);
        String schemaFingerprint = "sha256:" + sha256(String.join("\n", fingerprintInputs));
        return new GeneratedDatabasePlan(
                appId,
                engine,
                engine.storageMode(),
                engine.jdbc(),
                identity.requestedDatabaseName(),
                identity.resolvedDatabaseName(),
                identity.databaseNameSource(),
                identity.resolvedDataRoot(),
                identity.databaseInstanceId(),
                identity.containerName(),
                identity.host(),
                identity.hostPort(),
                identity.containerPort(),
                jdbcUrl,
                driver,
                definition.username(),
                definition.password(),
                identity.dbeaverHost(),
                identity.dbeaverPort(),
                identity.dbeaverDatabase(),
                definition.username(),
                definition.createInternalTables(),
                definition.createBusinessTables(),
                policy,
                schemaFingerprint,
                normalizedPath,
                fingerprintInputs
        );
    }

    private static void validate(UserDatabaseDefinition definition) {
        DatabaseEngine engine = definition.engine();
        if (definition.schemaLifecycle().strategy() == SchemaLifecycleStrategy.DROP_AND_RECREATE_ON_STRUCTURE_CHANGE
                && !definition.schemaLifecycle().destructiveConfirmedFor(engine)) {
            throw new IllegalArgumentException("Destructive schema recreation requires exact confirmation and NPDev-owned scope for " + engine.externalName());
        }
        if (engine == DatabaseEngine.IN_MEMORY) {
            return;
        }
        if (definition.username().isBlank()) {
            throw new IllegalArgumentException("database.username is required for " + engine.externalName());
        }
        if (engine == DatabaseEngine.POSTGRES) {
            require(definition.host(), "database.host");
            if (definition.port() <= 0) {
                throw new IllegalArgumentException("database.port is required for Postgres");
            }
        } else if (engine == DatabaseEngine.H2_SERVER && definition.jdbcUrl().isBlank()
                && (definition.host().isBlank() || definition.port() <= 0)) {
            throw new IllegalArgumentException("database.jdbcUrl or database.host/database.port is required for H2Server");
        }
    }

    private static DatabaseIdentity resolveIdentity(UserDatabaseDefinition definition, String appId, Path appDatabaseRoot) {
        DatabaseEngine engine = definition.engine();
        if (engine == DatabaseEngine.IN_MEMORY) {
            return new DatabaseIdentity(
                    "",
                    "",
                    "none",
                    "",
                    "",
                    "",
                    "",
                    0,
                    0,
                    "",
                    0,
                    ""
            );
        }

        String requested = definition.databaseName();
        String source = requested.isBlank() ? "generated" : "explicit";
        String resolvedName = requested.isBlank() ? uniqueDatabaseName(appId) : requested;
        String instanceId = requested.isBlank() ? resolvedName : appId;
        Path dataRootPath = requested.isBlank()
                ? appDatabaseRoot.resolve(resolvedName)
                : appDatabaseRoot;
        String dataRoot = dataRootPath.toAbsolutePath().normalize().toString().replace('\\', '/');
        String containerName = engine == DatabaseEngine.POSTGRES ? "npdev-" + slug(instanceId) : "";
        String host = resolveHost(definition);
        int hostPort = resolveHostPort(definition);
        int containerPort = engine == DatabaseEngine.POSTGRES ? 5432 : (engine == DatabaseEngine.H2_SERVER ? 9092 : 0);
        String dbeaverHost = engine == DatabaseEngine.H2_LOCAL ? "" : host;
        int dbeaverPort = engine == DatabaseEngine.H2_LOCAL ? 0 : hostPort;
        String dbeaverDatabase = switch (engine) {
            case POSTGRES -> resolvedName;
            case H2_LOCAL -> dataRoot + "/" + resolvedName;
            case H2_SERVER -> dataRoot + "/" + resolvedName;
            case IN_MEMORY -> "";
        };
        return new DatabaseIdentity(
                requested,
                resolvedName,
                source,
                dataRoot,
                instanceId,
                containerName,
                host,
                hostPort,
                containerPort,
                dbeaverHost,
                dbeaverPort,
                dbeaverDatabase
        );
    }

    private static String jdbcUrl(UserDatabaseDefinition definition, DatabaseIdentity identity) {
        return switch (definition.engine()) {
            case IN_MEMORY -> "";
            case POSTGRES -> "jdbc:postgresql://" + identity.host() + ":" + identity.hostPort()
                    + "/" + identity.resolvedDatabaseName();
            case H2_LOCAL -> "jdbc:h2:file:" + identity.resolvedDataRoot() + "/" + identity.resolvedDatabaseName()
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
            case H2_SERVER -> "jdbc:h2:tcp://" + identity.host() + ":" + identity.hostPort()
                    + "/" + identity.resolvedDataRoot() + "/" + identity.resolvedDatabaseName()
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        };
    }

    private static String resolveHost(UserDatabaseDefinition definition) {
        if (!definition.host().isBlank()) {
            return definition.host();
        }
        if (definition.engine() == DatabaseEngine.H2_SERVER && !definition.jdbcUrl().isBlank()) {
            Matcher matcher = Pattern.compile("^jdbc:h2:tcp://([^/:;]+)(?::(\\d+))?/.*").matcher(definition.jdbcUrl());
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        return "localhost";
    }

    private static int resolveHostPort(UserDatabaseDefinition definition) {
        if (definition.port() > 0) {
            return definition.port();
        }
        if (definition.engine() == DatabaseEngine.H2_SERVER && !definition.jdbcUrl().isBlank()) {
            Matcher matcher = Pattern.compile("^jdbc:h2:tcp://[^/:;]+:(\\d+)/.*").matcher(definition.jdbcUrl());
            if (matcher.matches()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return switch (definition.engine()) {
            case POSTGRES -> 5432;
            case H2_SERVER -> 9092;
            default -> 0;
        };
    }

    private static String resolveAppId(Path definitionPath) {
        Path definitionDir = definitionPath.getParent();
        Path appDir = definitionDir == null ? null : definitionDir.getParent();
        Path manifest = definitionDir == null ? null : definitionDir.resolve("manifest.json");
        if (manifest != null && Files.isRegularFile(manifest)) {
            try {
                String id = text(OBJECT_MAPPER.readTree(manifest.toFile()), "id");
                if (!id.isBlank()) {
                    return slug(id);
                }
            } catch (Exception ignored) {
            }
        }
        if (appDir != null && appDir.getFileName() != null) {
            return slug(appDir.getFileName().toString());
        }
        return "npdev-app";
    }

    private static Path resolveWorkspaceRoot(Path definitionPath) {
        Path current = definitionPath.toAbsolutePath().normalize();
        while (current != null) {
            if (current.getFileName() != null && "AppGen".equalsIgnoreCase(current.getFileName().toString())) {
                Path parent = current.getParent();
                if (parent != null) {
                    return parent;
                }
            }
            current = current.getParent();
        }
        current = definitionPath.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("Build")) && Files.isDirectory(current.resolve("NPDev_General"))) {
                return current;
            }
            current = current.getParent();
        }
        return Path.of("D:/WorkSpace/NPDev").toAbsolutePath().normalize();
    }

    private static String uniqueDatabaseName(String appId) {
        byte[] bytes = new byte[2];
        RANDOM.nextBytes(bytes);
        return slug(appId).replace('-', '_') + "_" + INSTANCE_FORMAT.format(LocalDateTime.now()) + "_"
                + HexFormat.of().formatHex(bytes);
    }

    /**
     * LNCH-1 P6 (task 6.1): the exact production fingerprint computation, extracted so
     * {@code com.npdev.generator.schemaevolution.MigrationPlanEmitter} can compute the "previous"
     * model's fingerprint (needed for {@code fromFingerprint}) via the SAME algorithm {@link #load}
     * uses for the "current" model's fingerprint -- never a hand-re-derived approximation. Callers
     * needing the CURRENT model's fingerprint should prefer the value {@link #load} already
     * computed ({@link GeneratedDatabasePlan#schemaFingerprint()}) over calling this a second time
     * with equivalent inputs; this method exists for computing the fingerprint of a DIFFERENT
     * (typically: previous) compiled model against the same database definition.
     */
    public static String computeSchemaFingerprint(UserDatabaseDefinition definition, CompiledModel model) {
        return "sha256:" + sha256(String.join("\n", fingerprintInputs(definition, model)));
    }

    private static List<String> fingerprintInputs(UserDatabaseDefinition definition, CompiledModel model) {
        List<String> inputs = new ArrayList<>();
        inputs.add("engine=" + definition.engine().externalName());
        inputs.add("storageMode=" + definition.engine().storageMode());
        inputs.add("lifecycle=" + definition.schemaLifecycle().strategy().externalName());
        inputs.add("scope=" + definition.schemaLifecycle().scope());
        if (definition.createInternalTables()) {
            for (InternalTableDefinition table : NpdevInternalTables.all()) {
                inputs.add("internal.table=" + table.name());
                for (InternalColumnDefinition column : table.columns()) {
                    inputs.add("internal.column=" + table.name() + "." + column.name() + ":" + column.type().name()
                            + ":required=" + column.required() + ":default=" + column.defaultExpression());
                }
            }
        }
        if (definition.createBusinessTables() && model != null) {
            for (CompiledConcept concept : model.getConcepts()) {
                inputs.add("business.table=" + safeTable(concept));
                for (CompiledField field : concept.getFields()) {
                    inputs.add("business.column=" + safeTable(concept) + "." + SqlIdentifierSupport.columnName(field) + ":"
                            + mapType(field) + ":required=" + field.isRequired() + ":unique=" + field.isUnique());
                }
            }
        }
        return List.copyOf(inputs);
    }

    static String mapType(CompiledField field) {
        return SqlTypeSupport.sqlType(field);
    }

    static String safeTable(CompiledConcept concept) {
        return SqlIdentifierSupport.tableName(concept);
    }

    static String toSnake(String value) {
        return SqlIdentifierSupport.toSnake(value);
    }

    private static String slug(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "npdev-app" : normalized;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to compute schema fingerprint", exception);
        }
    }

    private static String text(JsonNode node, String field) {
        String value = rawText(node, field);
        return value == null ? "" : value.trim();
    }

    private static String rawText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }

    private static int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.canConvertToInt() ? value.asInt() : fallback;
    }

    private static void require(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private record DatabaseIdentity(
            String requestedDatabaseName,
            String resolvedDatabaseName,
            String databaseNameSource,
            String resolvedDataRoot,
            String databaseInstanceId,
            String containerName,
            String host,
            int hostPort,
            int containerPort,
            String dbeaverHost,
            int dbeaverPort,
            String dbeaverDatabase
    ) {
    }
}
