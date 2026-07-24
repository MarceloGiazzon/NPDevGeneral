package com.finalexec.db;

import com.finalexec.db.schemastate.DesiredColumn;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.DesiredTable;
import com.finalexec.db.schemastate.DesiredUniqueConstraint;
import com.npdev.dsl.v1.schemaevolution.SqlTypeNormalization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a {@link DesiredSchema} (the model's intended shape) from a {@code SchemaManifest} + the
 * per-column {@code ColumnFacts} projection (schema-engine rebuild, Phase 2). Lives in
 * {@code com.finalexec.db} rather than {@code ...db.schemastate} because {@code ColumnFacts} and
 * {@code SchemaLifecycleExecutor.columnFactsFor} are package-private (and Java sub-packages get no
 * package access) — this is the sanctioned bridge that consumes {@code ColumnFacts} exactly as the
 * executor's class-header directive requires (never re-deriving column semantics).
 *
 * <p>Pure function, no DB, wired nowhere in Phase 2. Names are lower-cased to match {@code CurrentSchema}.
 *
 * <p>P0.2 asymmetry: the manifest carries no explicit FK or index lists (bonds/indexes are derived at
 * generation), so {@link DesiredSchema} models columns/types/nullability/defaults/uniques/renames only.
 * {@code SchemaDiffEngine} therefore does not diff FKs/indexes yet (it would phantom-report every live
 * FK); that is deferred until the desired side can express them (see the plan's P5.2).
 */
public final class DesiredSchemaFactory {

    private DesiredSchemaFactory() {
    }

    public static DesiredSchema fromManifest(SchemaLifecycleExecutor.SchemaManifest manifest) {
        Map<String, DesiredTable> tables = new LinkedHashMap<>();
        Map<String, String> tableRenames = manifest.businessTableRenames();
        Map<String, List<SchemaLifecycleExecutor.UniqueConstraintDecl>> uniquesByTable =
                manifest.businessTableUniqueConstraints();

        for (String table : manifest.businessTables()) {
            Map<String, SchemaLifecycleExecutor.ColumnFacts> facts =
                    SchemaLifecycleExecutor.columnFactsFor(manifest, table);
            List<String> columnNames = manifest.businessTableColumns().getOrDefault(table, List.of());

            Map<String, DesiredColumn> columns = new LinkedHashMap<>();
            for (String rawColumn : columnNames) {
                columns.put(lower(rawColumn), toDesiredColumn(rawColumn, facts.get(rawColumn)));
            }

            List<DesiredUniqueConstraint> uniques = new ArrayList<>();
            for (SchemaLifecycleExecutor.UniqueConstraintDecl decl : uniquesByTable.getOrDefault(table, List.of())) {
                uniques.add(new DesiredUniqueConstraint(lowerAll(decl.columns())));
            }

            String renamedFrom = tableRenames.get(table);
            tables.put(lower(table), new DesiredTable(
                    lower(table), Map.copyOf(columns), List.copyOf(uniques), lower(renamedFrom)));
        }
        return new DesiredSchema(Map.copyOf(tables));
    }

    private static DesiredColumn toDesiredColumn(String rawColumn, SchemaLifecycleExecutor.ColumnFacts facts) {
        if (facts == null) {
            // A column with no facts (shouldn't happen for a well-formed manifest) is treated as a
            // plain nullable column so the diff still sees it rather than dropping it silently.
            return new DesiredColumn(lower(rawColumn), null, true, null, false, false, false, null);
        }
        // ColumnFacts javadoc: a required column that is NOT additive-eligible is a required bond/FK
        // (the only reason a required column fails additive eligibility). Nullable bonds are additive-
        // eligible and are not flagged here — a known best-effort limit (Phase 2 does not diff FKs).
        boolean bond = facts.requiredByModel() && !facts.additiveEligible();
        String declaredType = facts.declaredType();
        // Platform columns (id/version/row_version/tenant_id) are ALWAYS NOT NULL (§6), independent of
        // whether they appear in the model's required set — so nullable only when a non-platform,
        // non-required model field.
        boolean nullable = !facts.requiredByModel() && !facts.platformManaged();
        return new DesiredColumn(
                lower(rawColumn),
                declaredType == null ? null : SqlTypeNormalization.normalize(declaredType),
                nullable,
                facts.literalDefaultJson(),
                facts.platformManaged(),
                facts.requiredByModel(),
                bond,
                lower(facts.renamedFrom()));
    }

    private static List<String> lowerAll(List<String> values) {
        List<String> out = new ArrayList<>(values.size());
        for (String value : values) {
            out.add(lower(value));
        }
        return List.copyOf(out);
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
