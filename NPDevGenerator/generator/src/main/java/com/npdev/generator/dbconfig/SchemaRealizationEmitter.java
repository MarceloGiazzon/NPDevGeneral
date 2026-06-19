package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.dsl.v1.compiled.SqlTypeSupport;
import com.npdev.generator.bonds.BondModelSupport;
import com.npdev.generator.bonds.BondModelSupport.Bond;
import com.npdev.generator.bonds.BondModelSupport.Cardinality;
import com.npdev.kernel.dbschema.InternalColumnDefinition;
import com.npdev.kernel.dbschema.InternalColumnType;
import com.npdev.kernel.dbschema.InternalIndexDefinition;
import com.npdev.kernel.dbschema.InternalTableDefinition;
import com.npdev.kernel.dbschema.NpdevInternalTables;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SchemaRealizationEmitter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public void emit(CompiledModel model, Path outRoot, GeneratedDatabasePlan plan, Path modelSourcePath) throws Exception {
        if (outRoot == null || plan == null) {
            return;
        }
        Path resourcesRoot = outRoot.resolve("src").resolve("main").resolve("resources");
        Files.createDirectories(resourcesRoot);
        emitSchemaArtifacts(model, resourcesRoot, plan);
        emitManifest(model, resourcesRoot, plan, modelSourcePath);
        emitApplicationProperties(resourcesRoot, plan);
    }

    private static void emitSchemaArtifacts(CompiledModel model, Path resourcesRoot, GeneratedDatabasePlan plan) throws Exception {
        Path schemaDir = resourcesRoot.resolve("db").resolve("schema-realization");
        Files.createDirectories(schemaDir);
        if (!plan.jdbc()) {
            Files.writeString(
                    schemaDir.resolve("in-memory-logical-stores.json"),
                    OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(storeMetadata(model, plan)) + System.lineSeparator(),
                    StandardCharsets.UTF_8
            );
            return;
        }

        StringBuilder sql = new StringBuilder();
        sql.append("-- NPDev schema realization\n");
        sql.append("-- Engine: ").append(plan.engine().externalName()).append("\n");
        sql.append("-- Schema fingerprint: ").append(plan.schemaFingerprint()).append("\n\n");
        if (plan.createInternalTables()) {
            for (InternalTableDefinition table : NpdevInternalTables.all()) {
                appendTable(sql, table, plan.engine());
            }
        }
        if (plan.createBusinessTables()) {
            Map<String, CompiledConcept> conceptsByName = BondModelSupport.conceptsByName(model);
            for (CompiledConcept concept : model.getConcepts()) {
                appendBusinessTable(sql, concept, plan.engine(), conceptsByName);
            }
            appendBonds(sql, model, plan.engine(), conceptsByName);
        }
        Files.writeString(schemaDir.resolve("V1__npdev_schema_realization.sql"), sql.toString(), StandardCharsets.UTF_8);
    }

    private static void appendTable(StringBuilder sql, InternalTableDefinition table, DatabaseEngine engine) {
        sql.append("CREATE TABLE IF NOT EXISTS ").append(table.name()).append(" (\n");
        List<String> lines = new ArrayList<>();
        for (InternalColumnDefinition column : table.columns()) {
            StringBuilder line = new StringBuilder("  ")
                    .append(column.name())
                    .append(" ")
                    .append(renderInternalType(column.type(), engine));
            if (column.required()) {
                line.append(" NOT NULL");
            }
            if (!column.defaultExpression().isBlank()) {
                line.append(" DEFAULT ").append(column.defaultExpression());
            }
            lines.add(line.toString());
        }
        lines.add("  PRIMARY KEY (" + String.join(", ", table.primaryKey().columns()) + ")");
        sql.append(String.join(",\n", lines)).append("\n);\n\n");
        for (InternalIndexDefinition index : table.indexes()) {
            sql.append("CREATE ")
                    .append(index.unique() ? "UNIQUE " : "")
                    .append("INDEX IF NOT EXISTS ")
                    .append(index.name())
                    .append(" ON ")
                    .append(table.name())
                    .append(" (")
                    .append(String.join(", ", index.columns()))
                    .append(");\n");
        }
        sql.append("\n");
    }

    private static void appendBusinessTable(StringBuilder sql, CompiledConcept concept, DatabaseEngine engine,
            Map<String, CompiledConcept> conceptsByName) {
        String table = SqlIdentifierSupport.tableName(concept);
        List<String> lines = new ArrayList<>();
        String idColumn = null;
        for (CompiledField field : concept.getFields()) {
            Optional<Bond> bond = BondModelSupport.resolveBond(concept, field, conceptsByName);
            // A many-to-many bond has no scalar column; its set lives in a junction table (below).
            if (bond.isPresent() && bond.get().cardinality() == Cardinality.MANY_TO_MANY) {
                continue;
            }
            String column = SqlIdentifierSupport.columnName(field);
            // A bond column takes the bound anchor's SQL type (e.g. a natural-key via sku -> VARCHAR),
            // not the reference default (UUID), so the column matches both the FK target and the
            // generated entity field type (which Hibernate ddl-auto=validate checks at startup).
            String sqlType = bond.isPresent()
                    ? bond.get().effectiveSqlType()
                    : SqlTypeSupport.sqlType(field);
            StringBuilder line = new StringBuilder("  ")
                    .append(column)
                    .append(" ")
                    .append(renderType(sqlType, engine));
            if (field.isRequired() || field.isId()) {
                line.append(" NOT NULL");
            }
            lines.add(line.toString());
            if (field.isId()) {
                idColumn = column;
            }
        }
        if (idColumn == null || idColumn.isBlank()) {
            idColumn = "id";
            lines.add(0, "  id UUID NOT NULL");
        }
        lines.add("  version BIGINT NOT NULL DEFAULT 0");
        lines.add("  PRIMARY KEY (" + idColumn + ")");
        sql.append("CREATE TABLE IF NOT EXISTS ").append(table).append(" (\n");
        sql.append(String.join(",\n", lines)).append("\n);\n\n");
        for (CompiledField field : concept.getFields()) {
            if (!field.isUnique()) {
                continue;
            }
            String column = SqlIdentifierSupport.columnName(field);
            if (isConnectableAnchor(field) && !field.isId()) {
                String constraint = truncate("uq_" + table + "_" + column);
                String constraintSql = "ALTER TABLE " + table
                        + " ADD CONSTRAINT " + constraint
                        + " UNIQUE (" + column + ")";
                sql.append(addConstraintIfMissing(engine, table, constraint, constraintSql));
            } else {
                sql.append("CREATE UNIQUE INDEX IF NOT EXISTS ")
                        .append(truncate("ux_" + table + "_" + column))
                        .append(" ON ")
                        .append(table)
                        .append(" (")
                        .append(column)
                        .append(");\n");
            }
        }
        sql.append("\n");
    }

    private static boolean isConnectableAnchor(CompiledField field) {
        return field != null && "anchor".equalsIgnoreCase(field.getConnectable());
    }

    /**
     * Emits referential integrity for bonds, mirroring {@code FlywayEmitter}: junction tables for
     * many-to-many bonds, and foreign keys (with the authored ON DELETE policy and, for natural-key
     * anchors, ON UPDATE CASCADE) for scalar bonds. Emitted after all tables and their unique
     * indexes exist so every referenced anchor column is already present.
     */
    private static void appendBonds(StringBuilder sql, CompiledModel model, DatabaseEngine engine,
            Map<String, CompiledConcept> conceptsByName) {
        List<Bond> bonds = BondModelSupport.allBonds(model);
        if (bonds.isEmpty()) {
            return;
        }

        // Junction tables (N:M bonds).
        for (Bond bond : bonds) {
            if (bond.cardinality() != Cardinality.MANY_TO_MANY) {
                continue;
            }
            CompiledField sourceId = BondModelSupport.idField(bond.sourceConcept());
            String junctionTable = bond.junctionTable();
            String sourceColumn = SqlIdentifierSupport.sourceJunctionColumn(sourceId);
            String targetColumn = SqlIdentifierSupport.targetJunctionColumn(bond.anchorField());
            sql.append("CREATE TABLE IF NOT EXISTS ").append(junctionTable).append(" (\n")
                    .append("  ").append(sourceColumn).append(" ").append(renderType(SqlTypeSupport.sqlType(sourceId), engine)).append(" NOT NULL,\n")
                    .append("  ").append(targetColumn).append(" ").append(renderType(bond.effectiveSqlType(), engine)).append(" NOT NULL,\n")
                    .append("  PRIMARY KEY (").append(sourceColumn).append(", ").append(targetColumn).append(")\n")
                    .append(");\n");
            sql.append("CREATE INDEX IF NOT EXISTS ")
                    .append(SqlIdentifierSupport.safeSqlIdentifier("idx_" + junctionTable + "_" + sourceColumn))
                    .append(" ON ").append(junctionTable).append(" (").append(sourceColumn).append(");\n");
            sql.append("CREATE INDEX IF NOT EXISTS ")
                    .append(SqlIdentifierSupport.safeSqlIdentifier("idx_" + junctionTable + "_" + targetColumn))
                    .append(" ON ").append(junctionTable).append(" (").append(targetColumn).append(");\n");
            // Source-side FK is always CASCADE: a junction row is membership owned by its source.
            String sourceConstraint = SqlIdentifierSupport.safeSqlIdentifier("fk_" + junctionTable + "_" + sourceColumn);
            String sourceConstraintSql = "ALTER TABLE " + junctionTable
                    + " ADD CONSTRAINT " + sourceConstraint
                    + " FOREIGN KEY (" + sourceColumn + ")"
                    + " REFERENCES " + bond.sourceTable() + " (" + SqlIdentifierSupport.columnName(sourceId) + ")"
                    + " ON DELETE CASCADE";
            sql.append(addConstraintIfMissing(engine, junctionTable, sourceConstraint, sourceConstraintSql));
            String targetConstraint = SqlIdentifierSupport.safeSqlIdentifier("fk_" + junctionTable + "_" + targetColumn);
            String targetConstraintSql = "ALTER TABLE " + junctionTable
                    + " ADD CONSTRAINT " + targetConstraint
                    + " FOREIGN KEY (" + targetColumn + ")"
                    + " REFERENCES " + bond.targetTable() + " (" + bond.anchorColumn() + ")"
                    + bond.onUpdateSqlClause()
                    + " ON DELETE " + bond.onDeleteSql();
            sql.append(addConstraintIfMissing(engine, junctionTable, targetConstraint, targetConstraintSql)).append("\n");
        }

        // Foreign keys for scalar (N:1 / 1:1) bonds.
        for (Bond bond : bonds) {
            if (bond.cardinality() == Cardinality.MANY_TO_MANY) {
                continue;
            }
            String constraint = SqlIdentifierSupport.safeSqlIdentifier("fk_" + bond.sourceTable() + "_" + bond.sourceColumn());
            String constraintSql = "ALTER TABLE " + bond.sourceTable()
                    + " ADD CONSTRAINT " + constraint
                    + " FOREIGN KEY (" + bond.sourceColumn() + ")"
                    + " REFERENCES " + bond.targetTable() + " (" + bond.anchorColumn() + ")"
                    + bond.onUpdateSqlClause()
                    + " ON DELETE " + bond.onDeleteSql();
            sql.append(addConstraintIfMissing(engine, bond.sourceTable(), constraint, constraintSql));
        }
        sql.append("\n");
    }

    private static String addConstraintIfMissing(
            DatabaseEngine engine,
            String tableName,
            String constraintName,
            String addConstraintSql
    ) {
        String statement = addConstraintSql.endsWith(";") ? addConstraintSql : addConstraintSql + ";";
        if (engine != DatabaseEngine.POSTGRES) {
            return statement + "\n";
        }
        // INFORMATION_SCHEMA.TABLE_CONSTRAINTS is standard SQL available in both PostgreSQL
        // and H2 PostgreSQL-compatibility mode. pg_constraint/pg_class/pg_namespace are
        // PostgreSQL-only system catalogs and must not be used even in the Postgres-only path,
        // to keep both emitters byte-consistent and avoid drift when switching engines.
        return """
                DO $$
                BEGIN
                  IF NOT EXISTS (
                    SELECT 1
                    FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                    WHERE CONSTRAINT_NAME = '%s'
                      AND TABLE_NAME = '%s'
                      AND TABLE_SCHEMA = current_schema()
                  ) THEN
                    %s
                  END IF;
                END $$;
                """.formatted(
                sqlLiteral(constraintName),
                sqlLiteral(tableName),
                statement
        );
    }

    private static String sqlLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private static void emitManifest(CompiledModel model, Path resourcesRoot, GeneratedDatabasePlan plan, Path modelSourcePath) throws Exception {
        List<String> internalTables = plan.createInternalTables()
                ? NpdevInternalTables.all().stream().map(InternalTableDefinition::name).toList()
                : List.of();
        List<String> businessTables = plan.createBusinessTables()
                ? model.getConcepts().stream().map(SqlIdentifierSupport::tableName).toList()
                : List.of();

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaRealizationVersion", "1");
        manifest.put("engine", plan.engine().externalName());
        manifest.put("storageMode", plan.storageMode());
        manifest.put("physicalDatabase", plan.jdbc());
        manifest.put("database", databaseIdentity(plan));
        manifest.put("schemaFingerprint", plan.schemaFingerprint());
        manifest.put("schemaLifecycle", Map.of(
                "strategy", plan.schemaLifecycle().strategy().externalName(),
                "allowDestructiveRecreate", plan.schemaLifecycle().allowDestructiveRecreate(),
                "scope", plan.schemaLifecycle().scope(),
                "destructiveRecreateConfirmation", plan.schemaLifecycle().destructiveRecreateConfirmation()
        ));
        manifest.put("internalTables", internalTables);
        manifest.put("businessTables", businessTables);
        manifest.put("sourceOfTruth", Map.of(
                "internal", resolveInternalSchemaSourcePath(plan.definitionPath()).toString(),
                "business", modelSourcePath == null ? "" : modelSourcePath.toAbsolutePath().normalize().toString(),
                "database", plan.definitionPath().toString()
        ));
        manifest.put("fingerprintInputs", plan.fingerprintInputs());

        Path manifestPath = resourcesRoot.resolve("npdev").resolve("db").resolve("schema-realization-manifest.json");
        Files.createDirectories(manifestPath.getParent());
        Files.writeString(
                manifestPath,
                OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + System.lineSeparator(),
                StandardCharsets.UTF_8
        );
    }

    private static void emitApplicationProperties(Path resourcesRoot, GeneratedDatabasePlan plan) throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("npdev.database.engine=").append(plan.engine().externalName()).append("\n");
        out.append("npdev.database.requested-name=").append(plan.requestedDatabaseName()).append("\n");
        out.append("npdev.database.resolved-name=").append(plan.resolvedDatabaseName()).append("\n");
        out.append("npdev.database.name-source=").append(plan.databaseNameSource()).append("\n");
        out.append("npdev.database.instance-id=").append(plan.databaseInstanceId()).append("\n");
        out.append("npdev.database.data-root=").append(plan.resolvedDataRoot()).append("\n");
        out.append("npdev.storage.mode=").append(plan.storageMode()).append("\n");
        out.append("npdev.schema.fingerprint=").append(plan.schemaFingerprint()).append("\n");
        out.append("npdev.schema.lifecycle.strategy=").append(plan.schemaLifecycle().strategy().externalName()).append("\n");
        out.append("npdev.schema.lifecycle.allow-destructive-recreate=").append(plan.schemaLifecycle().allowDestructiveRecreate()).append("\n");
        out.append("npdev.schema.lifecycle.scope=").append(plan.schemaLifecycle().scope()).append("\n");
        out.append("npdev.schema.lifecycle.destructive-recreate-confirmation=")
                .append(plan.schemaLifecycle().destructiveRecreateConfirmation()).append("\n");
        if (plan.jdbc()) {
            out.append("spring.datasource.url=").append(plan.jdbcUrl()).append("\n");
            out.append("spring.datasource.driver-class-name=").append(plan.driverClassName()).append("\n");
            out.append("spring.datasource.username=").append(plan.username()).append("\n");
            out.append("spring.datasource.password=").append(plan.password()).append("\n");
            out.append("spring.flyway.enabled=true\n");
            out.append("spring.flyway.locations=classpath:db/schema-realization\n");
            out.append("spring.jpa.hibernate.ddl-auto=validate\n");
        } else {
            out.append("spring.autoconfigure.exclude=")
                    .append("org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,")
                    .append("org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,")
                    .append("org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,")
                    .append("org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration\n");
            out.append("spring.flyway.enabled=false\n");
            out.append("spring.jpa.hibernate.ddl-auto=none\n");
        }
        Files.writeString(resourcesRoot.resolve("application-npdev-db.properties"), out.toString(), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> databaseIdentity(GeneratedDatabasePlan plan) {
        Map<String, Object> database = new LinkedHashMap<>();
        database.put("requestedDatabaseName", plan.requestedDatabaseName());
        database.put("resolvedDatabaseName", plan.resolvedDatabaseName());
        database.put("databaseNameSource", plan.databaseNameSource());
        database.put("resolvedDataRoot", plan.resolvedDataRoot());
        database.put("databaseInstanceId", plan.databaseInstanceId());
        database.put("containerName", plan.containerName());
        database.put("host", plan.host());
        database.put("hostPort", plan.hostPort());
        database.put("jdbcUrl", plan.jdbcUrl());
        database.put("dbeaver", Map.of(
                "host", plan.dbeaverHost(),
                "port", plan.dbeaverPort(),
                "database", plan.dbeaverDatabase(),
                "username", plan.dbeaverUsername(),
                "ssl", "disabled"
        ));
        return database;
    }

    private static Map<String, Object> storeMetadata(CompiledModel model, GeneratedDatabasePlan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", plan.engine().externalName());
        out.put("storageMode", plan.storageMode());
        out.put("schemaFingerprint", plan.schemaFingerprint());
        out.put("internalStores", NpdevInternalTables.all().stream().map(InternalTableDefinition::name).toList());
        out.put("businessStores", model.getConcepts().stream().map(UserDatabaseDefinitionLoader::safeTable).toList());
        return out;
    }

    private static String renderType(String sqlType, DatabaseEngine engine) {
        if (sqlType == null || sqlType.isBlank()) {
            return "VARCHAR(255)";
        }
        String normalized = sqlType.trim();
        if (engine == DatabaseEngine.H2_LOCAL || engine == DatabaseEngine.H2_SERVER) {
            if ("JSONB".equalsIgnoreCase(normalized) || "JSON".equalsIgnoreCase(normalized)) {
                return "JSON";
            }
            if ("TIMESTAMP WITH TIME ZONE".equalsIgnoreCase(normalized)) {
                return "TIMESTAMP WITH TIME ZONE";
            }
        }
        return normalized;
    }

    private static String renderInternalType(InternalColumnType type, DatabaseEngine engine) {
        if (type == null) {
            return "VARCHAR(255)";
        }
        return switch (type) {
            case TEXT, LARGE_TEXT, JSON_DOCUMENT -> "TEXT";
            case TIMESTAMP -> "TIMESTAMP";
            case INTEGER -> "INTEGER";
            case BIGINT -> "BIGINT";
        };
    }

    private static String truncate(String value) {
        return SqlIdentifierSupport.safeSqlIdentifier(value);
    }

    private static Path resolveInternalSchemaSourcePath(Path hint) {
        Path current = hint == null ? Path.of("").toAbsolutePath().normalize() : hint.toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve("NPDev_General")
                    .resolve("NPDevKernel")
                    .resolve("kernel")
                    .resolve("src")
                    .resolve("main")
                    .resolve("java")
                    .resolve("com")
                    .resolve("npdev")
                    .resolve("kernel")
                    .resolve("dbschema");
            if (Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
            candidate = current.resolve("NPDevKernel")
                    .resolve("kernel")
                    .resolve("src")
                    .resolve("main")
                    .resolve("java")
                    .resolve("com")
                    .resolve("npdev")
                    .resolve("kernel")
                    .resolve("dbschema");
            if (Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
            current = current.getParent();
        }
        return Path.of("NPDevKernel")
                .resolve("kernel")
                .resolve("src")
                .resolve("main")
                .resolve("java")
                .resolve("com")
                .resolve("npdev")
                .resolve("kernel")
                .resolve("dbschema")
                .toAbsolutePath()
                .normalize();
    }
}
