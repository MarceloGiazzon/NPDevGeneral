package com.finalexec.db;

import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * LIVE-1: adds the physical column for each new optional scalar field a {@code /model-reload}
 * brings to a concept that ALREADY EXISTS -- the existing-concept twin of
 * {@link NewConceptSchemaProvisioner} (which only ever creates a table for a brand-new concept).
 *
 * <p><b>Ordering contract, which is the whole point:</b> {@code MetadataHotSwapController} runs
 * this BEFORE {@code ModelHolder#swap}, so a column that cannot be added means no swap, a clear
 * error, and the old model keeps serving. The ALTERs run while holding {@link MigrationMutex} (the
 * same cross-instance lock a boot-time migration takes), so a second instance migrating at the same
 * moment cannot interleave with them. A failure part-way leaves the columns already added in place;
 * that is harmless because every one is nullable and {@code guardedAddColumn} makes a retry a no-op.
 *
 * <p><b>Dialect.</b> The statement text comes from {@link SqlDialect#guardedAddColumn} (idempotent
 * on all four engines: native {@code IF NOT EXISTS} on H2/Postgres, a {@code COL_LENGTH} guard on
 * SQL Server, an information_schema-gated prepared statement on MySQL) and the type from
 * {@link SqlDialect#portableColumnType}, resolved from the connection actually in hand
 * ({@link SqlDialects#forConnection}), never from the configured default.
 *
 * <p><b>Identifiers.</b> The table and column names are re-validated here as plain identifiers
 * ({@code SchemaLifecycleExecutor#safeIdentifier}, the same gate every other DDL path in this
 * package uses) and then quoted by the dialect; the type must match {@code TypeWideningPass#safeSqlType}'s
 * allow-list. A name that could carry SQL never reaches a statement.
 *
 * <p>Out of scope here by design: anything {@link LiveFieldDiff} refuses (required, unique,
 * reference, JSON-typed fields, retypes, removals). Those never reach this class.
 */
public final class ExistingConceptColumnProvisioner {

    private ExistingConceptColumnProvisioner() {
    }

    /** {@code provisioned} lists {@code Concept.field} labels of the columns this call ensured. */
    public record Result(List<String> provisioned) {
    }

    /** Thrown when an ALTER failed; {@code alreadyApplied} names the columns added before it. */
    public static final class ProvisioningFailedException extends RuntimeException {
        private final String failedField;
        private final List<String> alreadyApplied;

        ProvisioningFailedException(String failedField, List<String> alreadyApplied, String message, Throwable cause) {
            super(message, cause);
            this.failedField = failedField;
            this.alreadyApplied = List.copyOf(alreadyApplied);
        }

        public String failedField() {
            return failedField;
        }

        public List<String> alreadyApplied() {
            return alreadyApplied;
        }
    }

    public static Result provision(DataSource dataSource, List<LiveFieldDiff.AddedField> added) {
        if (added == null || added.isEmpty()) {
            return new Result(List.of());
        }
        if (dataSource == null) {
            throw new IllegalArgumentException("a DataSource is required to add columns");
        }
        // Validate every name BEFORE the first statement so a bad one cannot leave a half-applied set.
        List<String[]> statements = new ArrayList<>();
        for (LiveFieldDiff.AddedField field : added) {
            String table = SchemaLifecycleExecutor.safeIdentifier(field.table());
            String column = SchemaLifecycleExecutor.safeIdentifier(field.column());
            String type = TypeWideningPass.safeSqlType(field.sqlType());
            statements.add(new String[] {field.label(), table, column, type});
        }

        MigrationMutex.Held held = MigrationMutex.acquire(dataSource);
        List<String> applied = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            SqlDialect dialect = SqlDialects.forConnection(connection);
            for (String[] statement : statements) {
                String label = statement[0];
                String table = statement[1];
                String column = statement[2];
                String alter = "ALTER TABLE " + dialect.identifier(table)
                        + " ADD COLUMN " + dialect.identifier(column)
                        + " " + dialect.portableColumnType(statement[3]);
                String guarded = dialect.guardedAddColumn(table, column, alter);
                try {
                    execute(connection, guarded);
                } catch (SQLException failure) {
                    throw new ProvisioningFailedException(label, applied,
                            "could not add column " + table + "." + column + " for " + label + ": "
                                    + failure.getMessage(), failure);
                }
                applied.add(label);
            }
        } catch (SQLException failure) {
            throw new ProvisioningFailedException("", applied,
                    "could not open a connection to add columns: " + failure.getMessage(), failure);
        } finally {
            MigrationMutex.release(held);
        }
        return new Result(List.copyOf(applied));
    }

    /** MySQL's guard is a multi-statement script (SET/PREPARE/EXECUTE/DEALLOCATE) a plain JDBC
     *  execute cannot run as one string; every other dialect's guard is a single batch. */
    private static void execute(Connection connection, String guarded) throws SQLException {
        if (guarded.contains("SET @npdev_ddl")) {
            for (String statementSql : ConversionHookRunner.splitStatements(guarded)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(statementSql);
                }
            }
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(guarded);
        }
    }
}
