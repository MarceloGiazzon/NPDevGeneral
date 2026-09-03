package com.finalexec.db;

import com.npdev.kernel.concepts.ExpressionBackfillRiskClassifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BOUNDARY_LIFT_PLAN_2026-09-02 package 3.1 (B2, REG-202), done-when #5: renders a
 * {@link ExpressionBackfillPreview.Item} list into the shape an operator sees at
 * {@code GET /api/admin/schema-migration/expression-backfill-preview} -- column, nullability
 * change, expression, rows affected, null results, ordered steps -- plus the risk tier and whether
 * this candidate is eligible to auto-apply on the next boot, so a REVIEWABLE/HIGH_RISK item's
 * continued need for an acknowledgment is never a surprise. Sibling of {@link ImpactReportJson} in
 * this same package, same "render, don't inline in the controller" split.
 */
public final class ExpressionBackfillPreviewJson {

    private ExpressionBackfillPreviewJson() {
    }

    public static List<Map<String, Object>> encode(List<ExpressionBackfillPreview.Item> items) {
        List<Map<String, Object>> encoded = new ArrayList<>();
        for (ExpressionBackfillPreview.Item item : items) {
            ExpressionBackfillRiskClassifier.Tier tier = ExpressionBackfillRiskClassifier.classify(item.expression());
            Map<String, Object> encodedItem = new LinkedHashMap<>();
            encodedItem.put("table", item.table());
            encodedItem.put("column", item.column());
            encodedItem.put("nullabilityChange", "NULLABLE -> NOT NULL");
            encodedItem.put("expression", item.expression());
            encodedItem.put("rowsAffected", item.rowsAffected());
            encodedItem.put("distinctValues", item.distinctValues());
            encodedItem.put("failedRowIds", item.failedRowIds());
            encodedItem.put("riskTier", tier.name());
            encodedItem.put("autoApplyEligible",
                    tier == ExpressionBackfillRiskClassifier.Tier.SAFE && item.failedRowIds().isEmpty());
            encodedItem.put("steps", List.of(
                    "ADD COLUMN " + item.column() + " (nullable) IF NOT EXISTS",
                    "UPDATE " + item.table() + " SET " + item.column() + " = <" + item.expression()
                            + "> WHERE " + item.column() + " IS NULL",
                    "ALTER COLUMN " + item.column() + " SET NOT NULL"));
            encoded.add(encodedItem);
        }
        return encoded;
    }
}
