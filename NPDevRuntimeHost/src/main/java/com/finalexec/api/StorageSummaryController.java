package com.finalexec.api;

import com.finalexec.db.SchemaLifecycleExecutor;
import com.finalexec.db.SchemaLifecycleExecutor.SchemaManifest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class StorageSummaryController {
    private final ObjectProvider<DataSource> dataSource;
    private final String engine;
    private final String storageMode;

    public StorageSummaryController(
            ObjectProvider<DataSource> dataSource,
            @Value("${npdev.database.engine:}") String engine,
            @Value("${npdev.storage.mode:}") String storageMode
    ) {
        this.dataSource = dataSource;
        this.engine = engine;
        this.storageMode = storageMode;
    }

    @GetMapping("/api/admin/storage/summary")
    public Map<String, Object> summary() {
        SchemaManifest manifest = SchemaLifecycleExecutor.loadManifest();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", blank(engine) ? manifestValue(manifest, "engine") : engine);
        out.put("storageMode", blank(storageMode) ? manifestValue(manifest, "storageMode") : storageMode);
        out.put("schemaFingerprint", manifest == null ? "" : manifest.schemaFingerprint());
        out.put("physicalDatabase", manifest != null && manifest.physicalDatabase());
        if (manifest == null) {
            out.put("internalTables", List.of());
            out.put("businessTables", List.of());
            return out;
        }
        if (manifest.physicalDatabase()) {
            out.put("internalTables", tableSummaries(manifest.internalTables()));
            out.put("businessTables", tableSummaries(manifest.businessTables()));
        } else {
            out.put("internalStores", storeSummaries(manifest.internalTables()));
            out.put("businessStores", storeSummaries(manifest.businessTables()));
        }
        return out;
    }

    private List<Map<String, Object>> tableSummaries(List<String> tables) {
        DataSource ds = dataSource.getIfAvailable();
        if (ds == null) {
            return tables.stream().map(table -> status(table, false, false)).toList();
        }
        try (Connection connection = ds.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            return tables.stream().map(table -> {
                boolean exists = exists(metaData, table);
                return status(table, exists, exists && accessible(connection, table));
            }).toList();
        } catch (Exception exception) {
            return tables.stream().map(table -> status(table, false, false)).toList();
        }
    }

    private static List<Map<String, Object>> storeSummaries(List<String> stores) {
        return stores.stream().map(store -> status(store, true, true)).toList();
    }

    private static Map<String, Object> status(String name, boolean exists, boolean accessible) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("exists", exists);
        out.put("accessible", accessible);
        return out;
    }

    private static boolean exists(DatabaseMetaData metaData, String table) {
        try (ResultSet resultSet = metaData.getTables(null, null, table, null)) {
            if (resultSet.next()) {
                return true;
            }
        } catch (Exception ignored) {
        }
        try (ResultSet resultSet = metaData.getTables(null, null, table.toUpperCase(), null)) {
            return resultSet.next();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean accessible(Connection connection, String table) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM " + table + " LIMIT 1")) {
            statement.executeQuery();
            return true;
        } catch (Exception ignored) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table)) {
                statement.executeQuery();
                return true;
            } catch (Exception ignoredAgain) {
                return false;
            }
        }
    }

    private static String manifestValue(SchemaManifest manifest, String field) {
        if (manifest == null) {
            return "";
        }
        return "engine".equals(field) ? manifest.engine() : manifest.storageMode();
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
