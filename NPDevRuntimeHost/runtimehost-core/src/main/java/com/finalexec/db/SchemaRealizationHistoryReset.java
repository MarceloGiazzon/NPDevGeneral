package com.finalexec.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;
import com.npdev.dsl.v1.schemaevolution.RenameResolution;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.DropTable;
import com.npdev.dsl.v1.schemaevolution.SqlTypeNormalization;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.schemaevolution.TypeChangeMatrix;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import com.finalexec.config.ModelHolder;
import com.finalexec.npdev.service.PluginExecutionPolicyEvaluator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.finalexec.npdev.service.SupportDiagnosticsService;
import com.finalexec.npdev.service.pluginipc.PluginIpcChildProcessPool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import com.npdev.kernel.storage.sql.SqlDialects;
import com.finalexec.boundary.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.finalexec.db.SchemaLifecycleExecutor.*;

/**
 * CARVE-2 (2026-10-20): clearing Flyway's record of the schema-realization scripts after a destructive
 * recreation / ephemeral wipe, moved verbatim out of {@link SchemaLifecycleExecutor} -- pure
 * mechanical split, no logic change. The executor keeps same-named wrappers.
 */
final class SchemaRealizationHistoryReset {
    /**
     * STOR-16: clear Flyway's record of the schema-realization scripts so it re-applies them over
     * the tables the ephemeral wipe just dropped -- tolerating the two states an ephemeral app
     * legitimately reaches and the acknowledged path never does.
     *
     * <p>{@link #clearSchemaRealizationHistory} treats both as errors, correctly: it runs only
     * after an ACKNOWLEDGED destruction, which by construction happens on an app that has booted
     * before and whose realization scripts are on the classpath. Reaching it with no history table
     * or no scripts would mean something is badly wrong. An ephemeral app wipes on EVERY boot,
     * including the first, where neither exists yet -- both cases were found by the RuntimeHost
     * gate throwing, one per boot state, not by reading the code.
     *
     * <p>Loosening the shared method to cover this would have traded a real invariant for a
     * convenience, so this is a sibling rather than a flag.
     */
    void clearSchemaRealizationHistoryForEphemeral(DataSource dataSource) {
        List<String> scripts = schemaRealizationScriptNames();
        if (scripts.isEmpty()) {
            // Nothing to re-apply, so nothing to un-record. Not an error here.
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            if (readActualColumns(connection.getMetaData(), "flyway_schema_history").isEmpty()) {
                // Flyway has never run against this database -- an ephemeral app's first boot. It
                // will create its history and apply everything from scratch a moment from now.
                return;
            }
            for (String script : scripts) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + flywayHistoryTableRef(connection) + " WHERE "
                                + flywayHistoryScriptColumnRef(connection) + " = ?"
                )) {
                    statement.setString(1, script);
                    statement.executeUpdate();
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Failed preparing schema realization reapply after ephemeral recreation", exception);
        }
    }

    void clearSchemaRealizationHistory(DataSource dataSource) {
        List<String> scripts = schemaRealizationScriptNames();
        if (scripts.isEmpty()) {
            throw new IllegalStateException("No schema-realization SQL files found after destructive recreation.");
        }
        try (Connection connection = dataSource.getConnection()) {
            for (String script : scripts) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + flywayHistoryTableRef(connection) + " WHERE "
                                + flywayHistoryScriptColumnRef(connection) + " = ?"
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

    /**
     * Flyway always creates its own history table -- AND every one of its columns, including
     * {@code script} -- using identifiers QUOTED with the target database's native quote character,
     * in their exact declared case; it never case-folds, regardless of what the engine would
     * otherwise do to an unquoted reference. H2's own default folding is UPPER (see
     * {@link #readActualColumns(DatabaseMetaData, String)}'s own lower-then-upper fallback for the
     * identical gotcha on business-table columns), so an unquoted {@code flyway_schema_history} or
     * {@code script} in raw SQL here silently resolves to {@code FLYWAY_SCHEMA_HISTORY}/{@code
     * SCRIPT} and misses the real, lowercase-quoted table/column -- invisible until a real generated
     * app (non-empty {@link #schemaRealizationScriptNames()}) actually reaches this code with a
     * database that already has Flyway history (QUAL-47). Quoting with
     * {@link DatabaseMetaData#getIdentifierQuoteString()} matches Flyway's own quoting on every
     * supported engine (H2/Postgres/SQL Server use {@code "}, MySQL uses {@code `}).
     */
    private static String flywayHistoryTableRef(Connection connection) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString();
        return quote + "flyway_schema_history" + quote;
    }

    private static String flywayHistoryScriptColumnRef(Connection connection) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString();
        return quote + "script" + quote;
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
    private static final String SCHEMA_REALIZATION_LOCATION = "classpath:db/schema-realization";
}
