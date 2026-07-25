package com.finalexec.db;

import com.finalexec.db.schemastate.SchemaDiffItem;

/**
 * The machine-facing rendering of an {@link ImpactReport} (schema-engine rebuild, P6.2), conforming to
 * {@code NPDevContract/schemas/impact-report.schema.json}. Pure and deterministic (no DB, no clock): the
 * caller supplies {@code generatedAt} and the envelope fingerprints/token. Built by hand (no Jackson
 * dependency) with strict JSON string escaping so the same report always serialises byte-identically.
 */
public final class ImpactReportJson {

    private ImpactReportJson() {
    }

    /**
     * @param generatedAt ISO-8601 timestamp string (caller-supplied so the output is deterministic/testable)
     * @param fromFp      stored (from) fingerprint, or {@code null}
     * @param toFp        desired (to) fingerprint, or {@code null}
     * @param ackToken    emitted only when the verdict is DESTRUCTIVE; ignored otherwise
     */
    public static String render(ImpactReport report, String generatedAt, String fromFp, String toFp, String ackToken) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"generatedAt\": ").append(str(generatedAt)).append(",\n");
        out.append("  \"fingerprintFrom\": ").append(str(fromFp)).append(",\n");
        out.append("  \"fingerprintTo\": ").append(str(toFp)).append(",\n");
        out.append("  \"verdict\": ").append(str(report.verdict().name())).append(",\n");
        if (report.verdict() == ImpactReport.Verdict.DESTRUCTIVE && ackToken != null && !ackToken.isBlank()) {
            out.append("  \"acknowledgmentToken\": ").append(str(ackToken)).append(",\n");
        }
        out.append("  \"items\": [");
        for (int i = 0; i < report.items().size(); i++) {
            ImpactReport.Item item = report.items().get(i);
            SchemaDiffItem di = item.diffItem();
            out.append(i == 0 ? "\n" : ",\n");
            out.append("    {\n");
            out.append("      \"itemKey\": ").append(str(di.itemKey())).append(",\n");
            out.append("      \"table\": ").append(str(di.table())).append(",\n");
            out.append("      \"column\": ").append(str(di.column())).append(",\n");
            out.append("      \"safetyClass\": ").append(str(di.safetyClass().name())).append(",\n");
            out.append("      \"before\": ").append(str(di.before())).append(",\n");
            out.append("      \"after\": ").append(str(di.after())).append(",\n");
            out.append("      \"rowsAffected\": ").append(item.rowsAffected()).append(",\n");
            out.append("      \"probeNote\": ").append(str(item.probeNote())).append(",\n");
            out.append("      \"resolution\": ").append(str(di.resolution().name())).append(",\n");
            ProposedConversionSql.Proposal proposal = di.resolution() == com.finalexec.db.schemastate.Resolution.UNRESOLVED
                    ? ProposedConversionSql.forNarrowing(di) : null;
            out.append("      \"proposedConversionSql\": ").append(str(proposal == null ? null : proposal.sql())).append('\n');
            out.append("    }");
        }
        out.append(report.items().isEmpty() ? "]\n" : "\n  ]\n");
        out.append("}\n");
        return out.toString();
    }

    /** JSON string literal (or {@code null}) with strict escaping. */
    private static String str(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
