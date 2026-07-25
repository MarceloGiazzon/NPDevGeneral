package com.finalexec.db;

import com.finalexec.db.schemastate.Resolution;
import com.finalexec.db.schemastate.SchemaDiffItem;

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
        StringBuilder out = new StringBuilder();
        out.append("NPDev schema impact report\n");
        out.append("  fingerprint: ").append(nullSafe(fromFp)).append(" -> ").append(nullSafe(toFp)).append('\n');
        out.append("  verdict:     ").append(report.verdict()).append('\n');

        if (report.items().isEmpty()) {
            out.append("  (no schema changes)\n");
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
