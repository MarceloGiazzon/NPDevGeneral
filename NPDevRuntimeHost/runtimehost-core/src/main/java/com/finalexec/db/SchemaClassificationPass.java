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
 * CARVE-2 (2026-10-20): the fingerprint-mismatch classification (SAFE_ADDITIVE / RENAME_DETECTED /
 * TYPE_CHANGE_DETECTED / DESTRUCTIVE), moved verbatim out of {@link SchemaLifecycleExecutor} -- pure
 * mechanical split, no logic change. The executor keeps {@code classify} as a thin wrapper so every
 * existing caller and test is unchanged.
 */
final class SchemaClassificationPass {
    private SchemaClassificationPass() {
    }

    /**
     * Classifies a fingerprint mismatch by inspecting every already-existing business table against
     * the manifest's expected columns:
     * <ul>
     *   <li>{@code SAFE_ADDITIVE} -- no column removed; every added column is one
     *       {@code R__npdev_schema_additive_columns.sql} can apply (a non-bond field). Unchanged from
     *       the original boolean check.</li>
     *   <li>{@code RENAME_DETECTED} -- every extra/missing column pair is explained by a field's
     *       declared {@code renamedFrom}: the live database still has the OLD column name, the model
     *       now declares the NEW one. Not auto-applied as an in-place rename (out of scope -- see the
     *       class-level note on {@link com.finalexec.db.SchemaLifecycleExecutor}); this only makes the
     *       boot log and the eventual destructive recreate correctly say "rename" instead of looking
     *       like an unrelated column swap.</li>
     *   <li>{@code TYPE_CHANGE_DETECTED} -- column names match exactly, but at least one shared
     *       column's live SQL type differs from what the model now declares.</li>
     *   <li>{@code DESTRUCTIVE} -- anything else (the original "return false" case).</li>
     * </ul>
     * New tables and unreachable databases are not safe-additive evidence either way and fall through
     * to the existing destructive-recreate-or-throw behavior (matches the original boolean check).
     */
    static SchemaChangeClassification classify(DataSource dataSource, SchemaManifest manifest) {
        if (manifest.businessTableColumns().isEmpty()) {
            return SchemaChangeClassification.DESTRUCTIVE;
        }
        SchemaChangeClassification worst = SchemaChangeClassification.SAFE_ADDITIVE;
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            // SER-P4.8: the COLUMN-level classification is now ClassificationReducer over the live
            // SchemaDiff -- proven equivalent to the former per-table name/type/rename loop across every
            // H2 + Postgres proof-matrix scenario (the classify self-check asserted this default-on until
            // this switch made it tautological). The empty-manifest guard (above) and the LNCH-1-B7
            // dropped-concept escalation (below) are NOT column-level and remain exactly as they were.
            worst = ClassificationReducer.reduce(
                    new com.finalexec.db.schemastate.SchemaDiffEngine().diff(
                            DesiredSchemaFactory.fromManifest(manifest),
                            ShadowParityProbe.scopeToOwnedBusinessTables(
                                    new com.finalexec.db.schemastate.CurrentSchemaReader().read(dataSource), manifest)),
                    DesiredSchemaFactory.fromManifest(manifest));
            // LNCH-1-B7: the loop above iterates ONLY manifest-declared tables, so a table the new
            // model no longer declares (a dropped CONCEPT) is invisible to it -- the boot classified
            // SAFE_ADDITIVE and the table survived forever, even though -PlanOnly had told the
            // operator it would be dropped and had demanded an acknowledgment token for it. Escalate
            // so the destructive path (SchemaDeltaReport + the token check) is actually entered.
            //
            // Scoped deliberately: only orphans we can PROVE NPDev owns -- i.e. tables a previous
            // successful boot recorded in ownedBusinessTables. A table someone created by hand in the
            // same schema is never in that set, so it can never be swept into the destructive path.
            // When no ownership has ever been recorded (legacy app on its first boot with this build)
            // readOwnedBusinessTables returns null and we keep the pre-B7 behaviour exactly.
            if (!droppedConceptTables(metadata, dataSource, manifest).isEmpty()) {
                return SchemaChangeClassification.DESTRUCTIVE;
            }
            return worst;
        } catch (SQLException exception) {
            return SchemaChangeClassification.DESTRUCTIVE;
        }
    }

    /**
     * LNCH-1-B7. Live tables that a previous successful boot recorded as NPDev-owned business tables
     * but which the CURRENT manifest no longer declares -- i.e. genuinely dropped concepts. Empty
     * when ownership was never recorded (see {@link #readOwnedBusinessTables}), which preserves the
     * pre-B7 behaviour for legacy apps and for unit tests that seed only a fingerprint.
     *
     * <p>The old side of a declared table rename is excluded for the same reason
     * {@code SchemaDeltaReport#itemizeTableLevelDiff} excludes it: a rename is not a drop.
     */
    static Set<String> droppedConceptTables(
            DatabaseMetaData metadata, DataSource dataSource, SchemaManifest manifest) throws SQLException {
        Set<String> owned = readOwnedBusinessTables(dataSource);
        if (owned == null || owned.isEmpty()) {
            // B8:no_ownership_history (2026-08-25 W2.3, docs/ACCEPTED_BOUNDARIES.md): no ownership
            // evidence recorded -- cannot prove any live table is NPDev-owned, so skipping drop of
            // unowned tables. This is a soft skip, not a boot refusal (the boot continues normally),
            // so the code is carried on the log line itself, not a thrown BoundaryBootException --
            // there is nothing to refuse here, only an action silently not taken.
            System.out.println("NPDev schema lifecycle: WARNING [B8:no_ownership_history] no ownership history "
                    + "recorded -- skipping drop of unowned tables (no ownership evidence to act on). "
                    + "NPDev records that it owns a table before it will destroy one; a concept added and "
                    + "removed again without ever booting this database has no such record. If you meant "
                    + "one of the removed concepts' tables to be dropped: boot once WITH it back in the "
                    + "model (so this database records ownership), then remove it and boot again.");
            return Set.of();
        }
        Set<String> stillDeclared = new LinkedHashSet<>();
        for (String table : manifest.businessTableColumns().keySet()) {
            stillDeclared.add(table.toLowerCase(Locale.ROOT));
        }
        Set<String> renameOldNames = new LinkedHashSet<>();
        for (String oldName : manifest.businessTableRenames().values()) {
            if (oldName != null) {
                renameOldNames.add(oldName.toLowerCase(Locale.ROOT));
            }
        }
        Set<String> liveTables = readActualTableNames(metadata);
        Set<String> dropped = new LinkedHashSet<>();
        for (String table : owned) {
            if (liveTables.contains(table) && !stillDeclared.contains(table) && !renameOldNames.contains(table)) {
                dropped.add(table);
            }
        }
        return dropped;
    }
}
