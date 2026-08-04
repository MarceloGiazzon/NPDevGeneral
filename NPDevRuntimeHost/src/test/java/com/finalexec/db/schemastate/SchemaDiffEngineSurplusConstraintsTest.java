package com.finalexec.db.schemastate;

import com.finalexec.db.DesiredSchemaFactory;
import com.finalexec.db.SchemaLifecycleExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S8 Wave 2 (B3 FK/index surplus detection, roadmap deferred item #2): {@link SchemaDiffEngine#findSurplusConstraints}
 * orchestration — I2 (the reverse diff direction, regression-checked against the existing missing-only
 * behavior) and I3 (whole-schema abstention, RED-verified against a real generated app's manifest shape).
 */
class SchemaDiffEngineSurplusConstraintsTest {

    private final SchemaDiffEngine engine = new SchemaDiffEngine();

    @Test
    void surplusIndexIsReportedForeignWhileImplicitAndDeclaredAreNot_existingMissingOnlyDiffUnaffected() {
        // Extends SchemaDiffEngineTest#extraLiveIndexesAreNeverReported's own fixture with a genuinely
        // declared index alongside the implicit PK-backing one and a DBA-added performance index --
        // proves I2's whole DoD in one fixture: diff() stays empty (regression), and
        // findSurplusConstraints() classifies each live index correctly.
        DesiredTable orders = new DesiredTable("orders",
                Map.of("id", dCol("id")),
                List.of(), null, List.of(),
                List.of(new DesiredIndex(List.of("tenant_id"), false)));
        CurrentTable live = new CurrentTable("orders",
                Map.of("id", cCol("id")),
                List.of("id"), List.of(), List.of(),
                List.of(
                        new CurrentIndex("PRIMARY_KEY_5", List.of("id"), true),
                        new CurrentIndex("idx_orders_tenant", List.of("tenant_id"), false),
                        new CurrentIndex("a_dbas_own_perf_index", List.of("created_at"), false)));
        DesiredSchema desired = new DesiredSchema(Map.of("orders", orders));
        CurrentSchema current = new CurrentSchema(Map.of("orders", live));

        assertTrue(engine.diff(desired, current).isEmpty(),
                "regression: the existing missing-only diff must be completely unaffected by this method existing");

        ConstraintSurplusReport report = engine.findSurplusConstraints(desired, current);
        assertTrue(report.abstentions().isEmpty(), "a SAFE-TO-DIFF schema must not abstain");
        assertEquals(1, report.surplus().size(),
                "only the DBA performance index is genuine drift: " + report.surplus());
        SurplusConstraint found = report.surplus().get(0);
        assertEquals("a_dbas_own_perf_index", found.liveName());
        assertEquals(List.of("created_at"), found.columns());
    }

    /**
     * I3 RED-verification: gift-idea-tracker's REAL generated
     * {@code Build/generated-finalapps/gift-idea-tracker/.../schema-realization-manifest.json} genuinely
     * predates SER-G8 — confirmed by reading the raw file directly (not assumed): neither
     * {@code businessTableForeignKeys} nor {@code businessTableIndexes} is present. This fixture uses
     * that same table/column shape (table {@code gift_ideas}, its real 9 columns/types) built through
     * {@code SchemaManifest}'s own PRE-G8 20-arg convenience constructor (the one that predates those
     * two fields) — so {@link DesiredSchemaFactory#fromManifest} produces EXACTLY the same empty-FK/index
     * {@link DesiredSchema} a real load of that file would (both paths default an absent key to an empty
     * map identically; see {@code DesiredSchemaFactory}'s own corrected class javadoc).
     */
    @Test
    void preG8ManifestAbstainsWithZeroSurplusFindings_pointedAtGiftIdeaTrackersRealManifestShape() {
        SchemaLifecycleExecutor.SchemaManifest manifest = new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:gift-idea-tracker-pre-g8",
                List.of(), List.of("gift_ideas"),
                Map.of("gift_ideas", List.of("id", "idea", "occasion", "budget", "status", "person_ref",
                        "version", "row_version", "tenant_id")),
                Map.of("gift_ideas", List.of()),
                Map.of("gift_ideas", Map.of(
                        "id", "UUID", "idea", "VARCHAR(255)", "occasion", "VARCHAR(255)",
                        "budget", "INTEGER", "status", "VARCHAR(255)", "person_ref", "UUID",
                        "version", "BIGINT", "row_version", "BIGINT", "tenant_id", "VARCHAR(120)")),
                Map.of(), Map.of(),
                false, "KeepExistingIfCompatible", "NpdevOwnedTablesOnly", "", "",
                Map.of("gift_ideas", List.of()),
                Map.of(), Map.of(), Map.of());
        DesiredSchema desired = DesiredSchemaFactory.fromManifest(manifest);

        // A live database that (hypothetically) has accumulated real indexes -- exactly the scenario
        // that would phantom-report wholesale under a naive differ.
        CurrentTable liveGiftIdeas = new CurrentTable("gift_ideas",
                Map.of("id", cCol("id")),
                List.of("id"), List.of(), List.of(),
                List.of(new CurrentIndex("PRIMARY_KEY_1", List.of("id"), true),
                        new CurrentIndex("idx_gift_ideas_person_ref", List.of("person_ref"), false)));
        CurrentSchema current = new CurrentSchema(Map.of("gift_ideas", liveGiftIdeas));

        ConstraintSurplusReport report = engine.findSurplusConstraints(desired, current);

        assertTrue(report.surplus().isEmpty(),
                "a pre-SER-G8 desired schema must abstain, not phantom-report every live index: " + report.surplus());
        assertEquals(1, report.abstentions().size(), "exactly one whole-schema abstention notice");
        assertTrue(report.abstentions().get(0).toLowerCase(Locale.ROOT).contains("cannot classify"),
                report.abstentions().get(0));
    }

    private static DesiredColumn dCol(String name) {
        return new DesiredColumn(name, "UUID", false, null, true, true, false, true, null);
    }

    private static CurrentColumn cCol(String name) {
        return new CurrentColumn(name, "UUID", null, null, false, null);
    }
}
