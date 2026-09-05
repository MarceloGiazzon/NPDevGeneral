package com.npdev.samples.dslconformance.conversion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B1 (REAL_LIFT_PLAN_2026-09-03, B13): a real, minimal {@code conversions[].javaHook}, exercising
 * the same "arbitrary logic no declarative op can express" case the boundary write-up names --
 * combining two source columns with a numeric comparison to pick a label, something {@code case}
 * cannot do (it only matches literal equality on ONE source field).
 *
 * <p>Runs isolated in the plugin IPC pool, same as {@code plugin:java-source} mounts, dispatched by
 * the SAME {@code ManifestDrivenJavaSourcePluginHandler} (reused verbatim -- this method's shape,
 * one {@code Map} in and one {@code Map} out, is exactly what that handler already reflects into).
 * It never sees a {@code DataSource} or any other host capability -- {@code JavaMigrationHookRunner}
 * (host side) performs the actual row write itself, from this method's return value.
 */
public final class OrderSummaryHook {

    /**
     * {@code input.get("rows")} carries the primary key ({@code "id"}) plus every current column of
     * one batch of {@code ConversionDemoOrder} rows. Returns {@code {"rows": [...]}}, one entry per
     * row, each carrying {@code "id"} plus this hook's claimed column ({@code orderSummary}) -- the
     * platform writes back exactly these columns, for exactly these ids, never a column this method
     * did not name.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> summarize(Map<String, Object> input) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) input.get("rows");
        List<Map<String, Object>> writes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object priorityNumber = row.get("priorityNumber");
            Object regionLabel = row.get("regionLabel");
            int priority = priorityNumber instanceof Number number ? number.intValue() : Integer.MAX_VALUE;
            String region = regionLabel == null ? "Unknown" : regionLabel.toString();
            String urgency = priority <= 1 ? "URGENT" : "STANDARD";

            Map<String, Object> write = new LinkedHashMap<>();
            write.put("id", row.get("id"));
            write.put("orderSummary", urgency + "-" + region);
            writes.add(write);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", writes);
        return result;
    }
}
