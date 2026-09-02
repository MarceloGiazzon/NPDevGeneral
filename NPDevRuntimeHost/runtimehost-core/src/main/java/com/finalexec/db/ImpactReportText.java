package com.finalexec.db;

import com.finalexec.db.schemastate.ConstraintSurplusReport;
import com.finalexec.db.schemastate.Resolution;
import com.finalexec.db.schemastate.SchemaDiffItem;
import com.finalexec.db.schemastate.SurplusConstraint;
import com.npdev.dsl.v1.schemaevolution.RenameCandidateScorer;

import java.util.ArrayList;
import java.util.List;

/**
 * The human-facing rendering of an {@link ImpactReport} (schema-engine rebuild, P6.2): an aligned table,
 * one line per item, DESTRUCTIVE items prefixed {@code !!}, with a summary footer. This is the ONE text
 * that every surface prints — boot refusals, the {@code REPORT_ONLY} pre-deploy run, and operator logs —
 * so they converge rather than fork. Pure and deterministic (no DB, no clock): the caller supplies the
 * envelope metadata.
 */
public final class ImpactReportText {

    private ImpactReportText() {
    }

    /**
     * @param report   the probed report
     * @param fromFp   stored (from) fingerprint, or {@code null}
     * @param toFp     desired (to) fingerprint, or {@code null}
     * @param ackToken the acknowledgment token to display (only meaningful when verdict is DESTRUCTIVE),
     *                 or {@code null}
     */
    public static String render(ImpactReport report, String fromFp, String toFp, String ackToken) {
        return render(report, fromFp, toFp, ackToken, ConstraintSurplusReport.EMPTY, List.of());
    }

    /** @param surplus B3.2: the advisory FK/index surplus classification, rendered as its own section
     *                 after every other section — never affects {@code verdict}, never a drop proposal. */
    public static String render(ImpactReport report, String fromFp, String toFp, String ackToken,
            ConstraintSurplusReport surplus) {
        return render(report, fromFp, toFp, ackToken, surplus, List.of());
    }

    /** @param renameCandidates boundary lift plan 2026-09-02 package 2.2 (B1): ranked, scored rename
     *                          candidates from {@link RenameCandidateScorer}, computed by the caller
     *                          (this class has no database access) -- rendered in place of the old
     *                          same-table/same-type heuristic. Never applies anything; still only a
     *                          suggestion the boot refusal still fires alongside. */
    public static String render(ImpactReport report, String fromFp, String toFp, String ackToken,
            ConstraintSurplusReport surplus, List<RenameCandidateScorer.Candidate> renameCandidates) {
        StringBuilder out = new StringBuilder();
        out.append("NPDev schema impact report\n");
        out.append("  fingerprint: ").append(nullSafe(fromFp)).append(" -> ").append(nullSafe(toFp)).append('\n');
        out.append("  verdict:     ").append(report.verdict()).append('\n');

        if (report.items().isEmpty()) {
            out.append("  (no schema changes)\n");
            appendSurplusConstraints(out, surplus);
            return out.toString();
        }

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"", "SAFETY", "TABLE", "COLUMN", "CHANGE", "ROWS", "NOTE"});
        int safe = 0;
        int attention = 0;
        int destructive = 0;
        for (ImpactReport.Item item : report.items()) {
            SchemaDiffItem di = item.diffItem();
            boolean isDestructive = di.isDestructive();
            // SER-P7.4: a hook-claimed item renders "HOOK" here (its id is folded into the NOTE
            // column by ImpactReport) instead of the "!!" destructive/attention marker.
            String mark = di.resolution() == Resolution.HOOK_CLAIMED ? "HOOK" : (isDestructive ? "!!" : "");
            String change = change(di);
            String rowsAffected = item.rowsAffected() < 0 ? "?" : Long.toString(item.rowsAffected());
            rows.add(new String[] {mark, di.safetyClass().name(), nullSafe(di.table()),
                    di.column() == null ? "-" : di.column(), change, rowsAffected, nullSafe(item.probeNote())});
            switch (verdictBucket(item)) {
                case 2 -> destructive++;
                case 1 -> attention++;
                default -> safe++;
            }
        }

        int[] widths = columnWidths(rows, 6); // do not pad the trailing NOTE column
        for (String[] row : rows) {
            out.append("  ");
            for (int c = 0; c < row.length; c++) {
                out.append(c < widths.length ? pad(row[c], widths[c]) : row[c]);
                if (c < row.length - 1) {
                    out.append("  ");
                }
            }
            out.append('\n');
        }

        out.append("  summary: ").append(safe).append(" safe / ").append(attention).append(" attention / ")
                .append(destructive).append(" destructive\n");
        if (report.verdict() == ImpactReport.Verdict.DESTRUCTIVE && ackToken != null && !ackToken.isBlank()) {
            out.append("  acknowledgment token: ").append(ackToken).append('\n');
        }
        appendProposedConversions(out, report);
        appendPossibleRenames(out, renameCandidates);
        appendSurplusConstraints(out, surplus);
        return out.toString();
    }

    /** SER-P8.1: a ready-to-paste hook body (convert.sql + suggested verifySql) for every convertible
     *  DESTRUCTIVE_NARROW_TYPE item, or a "write a custom hook" note where no safe automatic conversion
     *  exists. NEVER auto-run -- see {@link ProposedConversionSql}'s class javadoc. */
    private static void appendProposedConversions(StringBuilder out, ImpactReport report) {
        List<ImpactReport.Item> narrowing = report.items().stream()
                .filter(item -> item.diffItem().safetyClass() == com.finalexec.db.schemastate.SafetyClass.DESTRUCTIVE_NARROW_TYPE
                        && item.diffItem().resolution() == Resolution.UNRESOLVED)
                .toList();
        if (narrowing.isEmpty()) {
            return;
        }
        out.append("  proposed conversions (paste into a hook.json + convert.sql, review before trusting):\n");
        for (ImpactReport.Item item : narrowing) {
            SchemaDiffItem di = item.diffItem();
            out.append("    ").append(nullSafe(di.table())).append('.').append(nullSafe(di.column()))
                    .append(" (").append(nullSafe(di.before())).append(" -> ").append(nullSafe(di.after())).append("):\n");
            ProposedConversionSql.Proposal proposal = ProposedConversionSql.forNarrowing(di);
            if (proposal == null) {
                out.append("      no safe automatic conversion -- write a custom hook.\n");
                continue;
            }
            for (String line : proposal.sql().split("\n")) {
                out.append("      ").append(line).append('\n');
            }
            out.append("      verifySql: ").append(proposal.verifySql()).append('\n');
        }
    }

    /**
     * B1.1 (docs/ACCEPTED_BOUNDARIES.md B1): a diff cannot tell a rename from a drop-plus-add — that
     * information is genuinely absent from a pure shape comparison, and guessing it is exactly what B1
     * refuses to do. What this CAN do without inferring anything: point out every {@code table} where a
     * column with LIVE DATA is being dropped while another column is being added, ranked and scored by
     * {@link RenameCandidateScorer} (boundary lift plan 2026-09-02, package 2.2) with every contributing
     * signal shown, and print the exact {@code renamedFrom} declaration that turns the guess into a
     * fact. NPDev still refuses either way; a human still judges and declares. {@code renameCandidates}
     * is computed by the caller (this class has no database access) via
     * {@code RenameCandidateAnalysis.compute}, which already excludes a HOOK_CLAIMED drop (a hook
     * already resolves it deliberately) and a column with zero live rows (nothing at stake yet).
     *
     * <p>Only candidates scoring at least half of {@link RenameCandidateScorer#MAX_SCORE} are shown —
     * a noise floor for the boot log, not a correctness gate; {@code npdev migration diff
     * --suggest-renames} shows every pair the scorer produces, unfiltered, for an operator who wants
     * the full picture.
     */
    private static void appendPossibleRenames(StringBuilder out, List<RenameCandidateScorer.Candidate> renameCandidates) {
        List<RenameCandidateScorer.Candidate> shown = renameCandidates.stream()
                .filter(candidate -> candidate.score() >= RenameCandidateScorer.MAX_SCORE / 2)
                .toList();
        if (shown.isEmpty()) {
            return;
        }
        out.append("  possible rename(s) -- NPDev cannot tell a rename from a drop+add by shape alone, so it "
                + "still refuses; this is the fix, not a guess (docs/ACCEPTED_BOUNDARIES.md B1):\n");
        for (RenameCandidateScorer.Candidate candidate : shown) {
            out.append("    '").append(nullSafe(candidate.droppedColumn())).append("' -> '")
                    .append(nullSafe(candidate.addedColumn())).append("' on ").append(nullSafe(candidate.table()))
                    .append(" (score ").append(candidate.score()).append('/').append(RenameCandidateScorer.MAX_SCORE)
                    .append("):\n");
            for (RenameCandidateScorer.SignalResult signal : candidate.signals()) {
                out.append("        ").append(signal.signal()).append(": ").append(signal.points()).append('/')
                        .append(signal.maxPoints()).append(" -- ").append(signal.detail()).append('\n');
            }
            out.append("        If this is a RENAME, declare it and re-run:\n")
                    .append("          \"").append(nullSafe(candidate.addedColumn()))
                    .append("\": { ..., \"renamedFrom\": \"").append(nullSafe(candidate.droppedColumn()))
                    .append("\" }\n");
        }
        out.append("    If it is genuinely a drop plus an add, acknowledge the drop as shown above "
                + "(this hint never blocks or auto-applies anything).\n");
    }

    /** B3.2 (docs/ACCEPTED_BOUNDARIES.md B3): the surplus FK/index classification, rendered as its own
     *  advisory section. Never influences {@code verdict}; never proposes a drop -- see {@link
     *  ConstraintSurplusReport}'s own javadoc for why that is true by construction, not just by review. */
    private static void appendSurplusConstraints(StringBuilder out, ConstraintSurplusReport surplus) {
        if (surplus == null || surplus.isEmpty()) {
            return;
        }
        if (!surplus.abstentions().isEmpty()) {
            out.append("  surplus FK/index constraints: cannot classify -- ")
                    .append(String.join("; ", surplus.abstentions())).append('\n');
            return;
        }
        out.append("  surplus FK/index constraints this model does not declare (advisory only -- never "
                + "proposed for drop, docs/ACCEPTED_BOUNDARIES.md B3): ").append(surplus.surplus().size())
                .append(" found\n");
        for (SurplusConstraint sc : surplus.surplus()) {
            out.append("    [").append(sc.kind()).append("] ").append(sc.table()).append('.')
                    .append(String.join(",", sc.columns()));
            if (sc.liveName() != null) {
                out.append(" (").append(sc.liveName()).append(')');
            }
            if ("FOREIGN_KEY".equals(sc.kind()) && sc.referencedTable() != null) {
                out.append(" -> ").append(sc.referencedTable());
            }
            out.append('\n');
        }
    }

    private static int verdictBucket(ImpactReport.Item item) {
        if (item.diffItem().resolution() == Resolution.HOOK_CLAIMED) {
            return 0;
        }
        if (item.diffItem().isDestructive()) {
            return 2;
        }
        return switch (item.diffItem().safetyClass()) {
            case NEEDS_BACKFILL, NEEDS_HOOK -> 1;
            default -> 0;
        };
    }

    private static String change(SchemaDiffItem di) {
        String before = di.before();
        String after = di.after();
        if (before == null && after == null) {
            return "-";
        }
        return nullSafe(before) + " -> " + nullSafe(after);
    }

    private static int[] columnWidths(List<String[]> rows, int upTo) {
        int columns = rows.get(0).length;
        int[] widths = new int[Math.min(upTo, columns)];
        for (String[] row : rows) {
            for (int c = 0; c < widths.length; c++) {
                widths[c] = Math.max(widths[c], row[c] == null ? 0 : row[c].length());
            }
        }
        return widths;
    }

    private static String pad(String value, int width) {
        String safe = value == null ? "" : value;
        if (safe.length() >= width) {
            return safe;
        }
        return safe + " ".repeat(width - safe.length());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
