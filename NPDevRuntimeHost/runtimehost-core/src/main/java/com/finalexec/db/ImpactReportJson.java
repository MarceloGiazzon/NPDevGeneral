package com.finalexec.db;

import com.finalexec.db.schemastate.ConstraintSurplusReport;
import com.finalexec.db.schemastate.SchemaDiffItem;
import com.finalexec.db.schemastate.SurplusConstraint;
import com.npdev.dsl.v1.schemaevolution.RenameCandidateScorer;

import java.util.List;

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
        return render(report, generatedAt, fromFp, toFp, ackToken, ConstraintSurplusReport.EMPTY, List.of());
    }

    /** @param surplus B3.2: the advisory FK/index surplus classification. Emitted as {@code
     *                 surplusConstraints} only when non-empty; never affects {@code verdict}. */
    public static String render(ImpactReport report, String generatedAt, String fromFp, String toFp, String ackToken,
            ConstraintSurplusReport surplus) {
        return render(report, generatedAt, fromFp, toFp, ackToken, surplus, List.of());
    }

    /** @param renameCandidates boundary lift plan 2026-09-02 package 2.2 (B1): ranked, scored rename
     *                          candidates from {@link RenameCandidateScorer}. Emitted as {@code
     *                          renameCandidates} only when non-empty; never applies anything, never
     *                          affects {@code verdict}. */
    public static String render(ImpactReport report, String generatedAt, String fromFp, String toFp, String ackToken,
            ConstraintSurplusReport surplus, List<RenameCandidateScorer.Candidate> renameCandidates) {
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
        out.append(report.items().isEmpty() ? "]" : "\n  ]");
        boolean hasRenameCandidates = renameCandidates != null && !renameCandidates.isEmpty();
        boolean hasSurplus = surplus != null && !surplus.isEmpty();
        out.append(hasRenameCandidates || hasSurplus ? ",\n" : "\n");
        appendRenameCandidates(out, renameCandidates);
        if (hasRenameCandidates && hasSurplus) {
            out.append(",\n");
        }
        appendSurplusConstraints(out, surplus);
        out.append("}\n");
        return out.toString();
    }

    /** Boundary lift plan 2026-09-02, package 2.2 (B1): emitted as {@code renameCandidates} only when
     *  non-empty, mirroring {@code surplusConstraints}' own presence rule -- an ordinary converged
     *  app's JSON stays byte-identical to before this shipped. */
    private static void appendRenameCandidates(StringBuilder out, List<RenameCandidateScorer.Candidate> renameCandidates) {
        if (renameCandidates == null || renameCandidates.isEmpty()) {
            return;
        }
        out.append("  \"renameCandidates\": [");
        for (int i = 0; i < renameCandidates.size(); i++) {
            RenameCandidateScorer.Candidate candidate = renameCandidates.get(i);
            out.append(i == 0 ? "\n" : ",\n");
            out.append("    {\n");
            out.append("      \"table\": ").append(str(candidate.table())).append(",\n");
            out.append("      \"droppedColumn\": ").append(str(candidate.droppedColumn())).append(",\n");
            out.append("      \"addedColumn\": ").append(str(candidate.addedColumn())).append(",\n");
            out.append("      \"score\": ").append(candidate.score()).append(",\n");
            out.append("      \"maxScore\": ").append(RenameCandidateScorer.MAX_SCORE).append(",\n");
            out.append("      \"signals\": [");
            List<RenameCandidateScorer.SignalResult> signals = candidate.signals();
            for (int s = 0; s < signals.size(); s++) {
                RenameCandidateScorer.SignalResult signal = signals.get(s);
                out.append(s == 0 ? "\n" : ",\n");
                out.append("        {\n");
                out.append("          \"signal\": ").append(str(signal.signal())).append(",\n");
                out.append("          \"points\": ").append(signal.points()).append(",\n");
                out.append("          \"maxPoints\": ").append(signal.maxPoints()).append(",\n");
                out.append("          \"detail\": ").append(str(signal.detail())).append('\n');
                out.append("        }");
            }
            out.append(signals.isEmpty() ? "]\n" : "\n      ]\n");
            out.append("    }");
        }
        out.append("\n  ]\n");
    }

    /** B3.2: {@code surplusConstraints}, conforming to {@code impact-report.schema.json}'s own
     *  optional property of the same name. Omitted entirely (not even an empty object) when there is
     *  nothing to report, so an ordinary converged app's JSON is byte-identical to before this shipped. */
    private static void appendSurplusConstraints(StringBuilder out, ConstraintSurplusReport surplus) {
        if (surplus == null || surplus.isEmpty()) {
            return;
        }
        out.append("  \"surplusConstraints\": {\n");
        String abstained = surplus.abstentions().isEmpty() ? null : String.join("; ", surplus.abstentions());
        out.append("    \"abstained\": ").append(str(abstained)).append(",\n");
        out.append("    \"items\": [");
        List<SurplusConstraint> items = surplus.surplus();
        for (int i = 0; i < items.size(); i++) {
            SurplusConstraint sc = items.get(i);
            out.append(i == 0 ? "\n" : ",\n");
            out.append("      {\n");
            out.append("        \"table\": ").append(str(sc.table())).append(",\n");
            out.append("        \"kind\": ").append(str(sc.kind())).append(",\n");
            out.append("        \"liveName\": ").append(str(sc.liveName())).append(",\n");
            out.append("        \"columns\": [");
            for (int c = 0; c < sc.columns().size(); c++) {
                out.append(c == 0 ? "" : ", ").append(str(sc.columns().get(c)));
            }
            out.append("],\n");
            out.append("        \"unique\": ").append(sc.unique()).append(",\n");
            out.append("        \"referencedTable\": ").append(str(sc.referencedTable())).append('\n');
            out.append("      }");
        }
        out.append(items.isEmpty() ? "]\n" : "\n    ]\n");
        out.append("  }\n");
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
