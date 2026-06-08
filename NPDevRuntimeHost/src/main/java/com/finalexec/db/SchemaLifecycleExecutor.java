package com.finalexec.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Component
public final class SchemaLifecycleExecutor implements FlywayMigrationStrategy {
    private static final String METADATA_TABLE = "npdev_schema_metadata";
    private static final String FINGERPRINT_KEY = "schemaFingerprint";
    private static final String SCHEMA_REALIZATION_LOCATION = "classpath:db/schema-realization";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public void migrate(Flyway flyway) {
        Configuration configuration = flyway.getConfiguration();
        DataSource dataSource = configuration.getDataSource();
        if (dataSource == null) {
            flyway.migrate();
            return;
        }
        SchemaManifest manifest = loadManifest();
        if (manifest == null || !manifest.physicalDatabase()) {
            flyway.migrate();
            return;
        }
        DestructiveRecreation recreation = beforeMigrate(dataSource, manifest);
        if (recreation.performed()) {
            clearSchemaRealizationHistory(dataSource);
        }
        flyway.migrate();
        afterMigrate(dataSource, manifest);
    }

    private DestructiveRecreation beforeMigrate(DataSource dataSource, SchemaManifest manifest) {
        String stored = readFingerprint(dataSource);
        if (stored == null || stored.isBlank()) {
            System.out.println("NPDev schema lifecycle: no stored schema fingerprint found; initializing schema realization.");
            return DestructiveRecreation.none();
        }
        if (stored.equals(manifest.schemaFingerprint())) {
            System.out.println("NPDev schema lifecycle: stored schema fingerprint matches generated schema fingerprint; no destructive recreation required.");
            return DestructiveRecreation.none();
        }
        if (!manifest.destructiveAllowed()) {
            throw new IllegalStateException("Schema fingerprint changed from " + stored + " to "
                    + manifest.schemaFingerprint() + " but destructive recreation is not explicitly allowed.");
        }
        List<String> tables = new ArrayList<>();
        tables.addAll(manifest.businessTables());
        tables.addAll(manifest.internalTables());
        Collections.reverse(tables);
        List<String> dropped = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (String table : tables) {
                if (table == null || table.isBlank()) {
                    continue;
                }
                String safeTable = safeIdentifier(table);
                try (PreparedStatement statement = connection.prepareStatement("DROP TABLE IF EXISTS " + safeTable)) {
                    statement.executeUpdate();
                    dropped.add(safeTable);
                }
            }
            System.out.println("NPDev destructive schema recreation dropped manifest-listed NPDev-owned tables: " + dropped);
            System.out.println("NPDev destructive schema recreation stored fingerprint: " + stored);
            System.out.println("NPDev destructive schema recreation generated fingerprint: " + manifest.schemaFingerprint());
            return new DestructiveRecreation(true, List.copyOf(dropped));
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed destructive schema recreation", exception);
        }
    }

    private void clearSchemaRealizationHistory(DataSource dataSource) {
        List<String> scripts = schemaRealizationScriptNames();
        if (scripts.isEmpty()) {
            throw new IllegalStateException("No schema-realization SQL files found after destructive recreation.");
        }
        try (Connection connection = dataSource.getConnection()) {
            for (String script : scripts) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM flyway_schema_history WHERE script = ?"
                )) {
                    statement.setString(1, script);
                    statement.executeUpdate();
                }
            }
            System.out.println("NPDev destructive schema recreation cleared Flyway history for schema-realization scripts: " + scripts);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed preparing schema realization reapply after destructive recreation", exception);
        }
    }

    private List<String> schemaRealizationScriptNames() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(SCHEMA_REALIZATION_LOCATION + "/*.sql");
            List<String> scripts = new ArrayList<>();
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename != null && !filename.isBlank()) {
                    scripts.add(filename);
                }
            }
            Collections.sort(scripts);
            return scripts;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed listing schema-realization SQL files", exception);
        }
    }

    private static String safeIdentifier(String identifier) {
        String value = identifier == null ? "" : identifier.trim();
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalStateException("Unsafe table identifier in schema realization manifest: " + identifier);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private void afterMigrate(DataSource dataSource, SchemaManifest manifest) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS " + METADATA_TABLE
                            + " (metadata_key TEXT PRIMARY KEY, metadata_value TEXT NOT NULL, updated_at_ms BIGINT NOT NULL)"
            )) {
                statement.executeUpdate();
            }
            int updated;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + METADATA_TABLE + " SET metadata_value = ?, updated_at_ms = ? WHERE metadata_key = ?"
            )) {
                statement.setString(1, manifest.schemaFingerprint());
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, FINGERPRINT_KEY);
                updated = statement.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO " + METADATA_TABLE + " (metadata_key, metadata_value, updated_at_ms) VALUES (?, ?, ?)"
                )) {
                    statement.setString(1, FINGERPRINT_KEY);
                    statement.setString(2, manifest.schemaFingerprint());
                    statement.setLong(3, System.currentTimeMillis());
                    statement.executeUpdate();
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed storing schema fingerprint", exception);
        }
    }

    private static String readFingerprint(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT metadata_value FROM " + METADATA_TABLE + " WHERE metadata_key = ?"
             )) {
            statement.setString(1, FINGERPRINT_KEY);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        } catch (SQLException exception) {
            return null;
        }
    }

    public static SchemaManifest loadManifest() {
        try {
            ClassPathResource resource = new ClassPathResource("npdev/db/schema-realization-manifest.json");
            if (!resource.exists()) {
                return null;
            }
            JsonNode root = OBJECT_MAPPER.readTree(resource.getInputStream());
            JsonNode lifecycle = root.path("schemaLifecycle");
            return new SchemaManifest(
                    root.path("engine").asText(""),
                    root.path("storageMode").asText(""),
                    root.path("physicalDatabase").asBoolean(false),
                    root.path("schemaFingerprint").asText(""),
                    strings(root.path("internalTables")),
                    strings(root.path("businessTables")),
                    lifecycle.path("allowDestructiveRecreate").asBoolean(false),
                    lifecycle.path("strategy").asText(""),
                    lifecycle.path("scope").asText(""),
                    lifecycle.path("destructiveRecreateConfirmation").asText("")
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Failed loading schema realization manifest", exception);
        }
    }

    private static List<String> strings(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : array) {
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    public record SchemaManifest(
            String engine,
            String storageMode,
            boolean physicalDatabase,
            String schemaFingerprint,
            List<String> internalTables,
            List<String> businessTables,
            boolean allowDestructiveRecreate,
            String strategy,
            String scope,
            String destructiveRecreateConfirmation
    ) {
        boolean destructiveAllowed() {
            return "DropAndRecreateOnStructureChange".equals(strategy)
                    && allowDestructiveRecreate
                    && "NpdevOwnedTablesOnly".equals(scope)
                    && "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED".equals(destructiveRecreateConfirmation);
        }
    }

    private record DestructiveRecreation(boolean performed, List<String> droppedTables) {
        static DestructiveRecreation none() {
            return new DestructiveRecreation(false, List.of());
        }
    }
}
