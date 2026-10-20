package com.npdev.runtime.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.OBJECT_MAPPER;

/**
 * CARVE-3: the audit field-diff describers, moved byte-faithfully out of GeneratedCrudRuntimeSupport.
 * Secrets reaching audit rows was a real bug here -- the redaction contract (a redacted field's old and
 * new value are both replaced by REDACTED_AUDIT_VALUE) is exercised by
 * GeneratedCrudRuntimeSupportKernelPortsTest.auditCrudMutation_recordsStructuredChangesAndRedactsSensitiveFields.
 */
final class CrudAuditDiffSupport {
    private CrudAuditDiffSupport() {
    }

    /**
     * R5.1: a compact, deterministic "field: before -> after" list for every field that differs
     * between two record snapshots. A create has {@code before == null}, so every field in
     * {@code after} shows as a change from "null"; a delete has {@code after == null}, so every
     * field in {@code before} shows as a change to "null". Values are stringified, not
     * type-preserved -- this produces an audit trail entry, not a re-playable payload. Fields are
     * sorted alphabetically so output is deterministic regardless of the entity's declared field
     * order.
     */
    static String describeFieldDiff(Map<String, Object> before, Map<String, Object> after,
                                            Set<String> redacted) {
        if (before == null && after == null) {
            return "";
        }
        List<String> fields = new ArrayList<>();
        if (before != null) {
            fields.addAll(before.keySet());
        }
        if (after != null) {
            for (String key : after.keySet()) {
                if (!fields.contains(key)) {
                    fields.add(key);
                }
            }
        }
        fields.sort(Comparator.naturalOrder());
        StringBuilder builder = new StringBuilder();
        for (String field : fields) {
            Object oldValue = before == null ? null : before.get(field);
            Object newValue = after == null ? null : after.get(field);
            String oldText = oldValue == null ? "null" : String.valueOf(oldValue);
            String newText = newValue == null ? "null" : String.valueOf(newValue);
            if (oldText.equals(newText)) {
                continue;
            }
            if (redacted.contains(field)) {
                oldText = REDACTED_AUDIT_VALUE;
                newText = REDACTED_AUDIT_VALUE;
            }
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(field).append(": ").append(oldText).append(" -> ").append(newText);
        }
        return builder.toString();
    }

    private static final String REDACTED_AUDIT_VALUE = "***";

    /**
     * UI-4: JSON array of {@code {field, before, after}} for every field that differs between two
     * snapshots, sorted by field name, with the same redaction and stringification as
     * {@link #describeFieldDiff}. Empty string when nothing changed or serialization fails.
     */
    static String describeFieldChangesJson(Map<String, Object> before, Map<String, Object> after,
                                                   Set<String> redacted) {
        if (before == null && after == null) {
            return "";
        }
        List<String> fields = new ArrayList<>();
        if (before != null) {
            fields.addAll(before.keySet());
        }
        if (after != null) {
            for (String key : after.keySet()) {
                if (!fields.contains(key)) {
                    fields.add(key);
                }
            }
        }
        fields.sort(Comparator.naturalOrder());
        List<Map<String, String>> changes = new ArrayList<>();
        for (String field : fields) {
            Object oldValue = before == null ? null : before.get(field);
            Object newValue = after == null ? null : after.get(field);
            String oldText = oldValue == null ? "null" : String.valueOf(oldValue);
            String newText = newValue == null ? "null" : String.valueOf(newValue);
            if (oldText.equals(newText)) {
                continue;
            }
            Map<String, String> change = new LinkedHashMap<>();
            change.put("field", field);
            change.put("before", redacted.contains(field) ? REDACTED_AUDIT_VALUE : oldText);
            change.put("after", redacted.contains(field) ? REDACTED_AUDIT_VALUE : newText);
            changes.add(change);
        }
        if (changes.isEmpty()) {
            return "";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(changes);
        } catch (Exception e) {
            return "";
        }
    }
}
