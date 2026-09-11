package com.npdev.samples.regensurvival;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Path A P5.4 golden regeneration-survival probe: a real, minimal {@code conversions[].javaHook},
 * mirroring dsl-conformance-max's OrderSummaryHook shape. check-golden-regeneration-survival.py hand-
 * edits the GENERATED copy of this file (under Output/App), never this source, between the two
 * generate+assemble passes it drives.
 */
public final class SurvivalHook {

    public Map<String, Object> summarize(Map<String, Object> input) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) input.get("rows");
        List<Map<String, Object>> writes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> write = new LinkedHashMap<>();
            write.put("id", row.get("id"));
            write.put("summary", "generated-summary");
            writes.add(write);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", writes);
        return result;
    }
}
