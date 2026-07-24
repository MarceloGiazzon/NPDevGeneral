package com.finalexec.db;

import com.finalexec.db.schemastate.SafetyClass;
import com.finalexec.db.schemastate.SchemaDiffItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic (no-DB) unit test for the two {@link ImpactReport} renderers (P6.2), against a fixed
 * probed fixture: {@link ImpactReportText} (aligned table, {@code !!} destructive prefix, summary footer,
 * token only when destructive) and {@link ImpactReportJson} (schema-shaped, strict escaping, token only
 * when destructive).
 */
class ImpactReportRenderersTest {

    private static ImpactReport fixture() {
        return ImpactReport.ofProbedItems(List.of(
                new ImpactReport.Item(SchemaDiffItem.of("ADD_REQUIRED_COLUMN:widgets:status", "widgets", "status",
                        SafetyClass.NEEDS_BACKFILL, null, "VARCHAR(10)"), 3L, ""),
                new ImpactReport.Item(SchemaDiffItem.of("DROP_COLUMN:widgets:legacy_flag:BOOLEAN", "widgets",
                        "legacy_flag", SafetyClass.DESTRUCTIVE_DROP_COLUMN, "BOOLEAN", null), 2L, "")));
    }

    @Test
    void textRendersAlignedTableWithDestructiveMarkerAndFooter() {
        String text = ImpactReportText.render(fixture(), "sha256:old", "sha256:new", "TOKEN123");
        assertTrue(text.contains("verdict:     DESTRUCTIVE"), text);
        assertTrue(text.contains("!!"), "destructive item must be flagged: " + text);
        assertTrue(text.contains("legacy_flag"), text);
        assertTrue(text.contains("0 safe / 1 attention / 1 destructive"), text);
        assertTrue(text.contains("acknowledgment token: TOKEN123"), text);
    }

    @Test
    void textOmitsTokenWhenNotDestructive() {
        ImpactReport safe = ImpactReport.ofProbedItems(List.of(new ImpactReport.Item(
                SchemaDiffItem.of("ADD_COLUMN:widgets:note", "widgets", "note", SafetyClass.SAFE_ADDITIVE, null,
                        "VARCHAR(20)"), 0L, "")));
        String text = ImpactReportText.render(safe, "a", "b", "SHOULD_NOT_APPEAR");
        assertEquals(ImpactReport.Verdict.SAFE, safe.verdict());
        assertFalse(text.contains("SHOULD_NOT_APPEAR"), "no token line unless destructive: " + text);
    }

    @Test
    void jsonMatchesSchemaShapeAndEscapesStrings() {
        String json = ImpactReportJson.render(fixture(), "2026-07-24T00:00:00Z", "sha256:old", "sha256:new", "TOK\"EN");
        assertTrue(json.contains("\"verdict\": \"DESTRUCTIVE\""), json);
        assertTrue(json.contains("\"generatedAt\": \"2026-07-24T00:00:00Z\""), json);
        assertTrue(json.contains("\"itemKey\": \"DROP_COLUMN:widgets:legacy_flag:BOOLEAN\""), json);
        assertTrue(json.contains("\"rowsAffected\": 2"), json);
        assertTrue(json.contains("\"resolution\": \"UNRESOLVED\""), json);
        assertTrue(json.contains("\"proposedConversionSql\": null"), json);
        // token present (destructive) and correctly escaped
        assertTrue(json.contains("\"acknowledgmentToken\": \"TOK\\\"EN\""), json);
    }

    @Test
    void jsonOmitsTokenWhenNotDestructiveAndIsStable() {
        ImpactReport safe = ImpactReport.ofProbedItems(List.of(new ImpactReport.Item(
                SchemaDiffItem.of("ADD_COLUMN:widgets:note", "widgets", "note", SafetyClass.SAFE_ADDITIVE, null,
                        "VARCHAR(20)"), 0L, "")));
        String first = ImpactReportJson.render(safe, "2026-07-24T00:00:00Z", "a", "b", "X");
        String second = ImpactReportJson.render(safe, "2026-07-24T00:00:00Z", "a", "b", "X");
        assertEquals(first, second, "same report + envelope must serialise byte-identically");
        assertFalse(first.contains("acknowledgmentToken"), "no token key unless destructive: " + first);
    }
}
