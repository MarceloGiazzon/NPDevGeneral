package com.finalexec.api;

import com.npdev.dsl.v1.compiled.CompiledDocument;
import com.npdev.dsl.v1.compiled.CompiledDocumentColumn;
import com.npdev.kernel.concepts.ConceptRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * REP-1: the layout of a {@code kind: list} document -- columns, optional grouping, per-group
 * subtotals and a grand total -- computed in Java over rows that ALREADY went through
 * {@code ConceptGateway.query}, and rendered to CSV or XHTML (for the PDF renderer) from that one
 * structure so the two outputs can never disagree.
 *
 * <p><b>Why the totals are computed here and never pushed to {@code ConceptGateway.aggregate}.</b>
 * {@code query()} applies the caller's row-level {@code access.read} rule and strips every hidden /
 * field-{@code access.read}-denied field from each record; {@code aggregate()} applies the row scope
 * (or refuses) but does NOT remove hidden fields, so a grouped sum over a field the caller may not
 * read would hand back a number derived from data they cannot see. Summing the already-filtered rows
 * means a subtotal can only ever contain what the viewer can see row by row.</p>
 *
 * <p><b>Hidden fields.</b> Field visibility is decided per record by the gateway (a field-level
 * {@code access.read} rule may allow it on some rows and not others), and a denied field is
 * <em>omitted</em> from the record, not nulled. So a declared column is printed only when at least one
 * returned row carries it; a column nobody here may read is dropped entirely (never printed blank,
 * never totalled), and a {@code groupBy} field that is not visible is ignored. A row that lacks a
 * visible column's value prints an empty cell and contributes nothing to that column's total.</p>
 *
 * <p>Do not name the generated runtime package in this file (see {@code CsvCells}): the build's
 * source-exclusion scan is a plain text match.</p>
 */
public final class ListReport {

    /** What one output line is. */
    public enum LineType { GROUP_HEADER, DETAIL, SUBTOTAL, GRAND_TOTAL }

    /** A printed column. {@code field} is empty for the synthetic label column (see {@link #build}). */
    public record Column(String field, String label, String total) {
        boolean synthetic() {
            return field.isEmpty();
        }
    }

    /**
     * One line of output. {@code cells} always has one entry per column except for
     * {@link LineType#GROUP_HEADER}, where {@code label} is the whole content. {@code level} is the
     * group nesting depth (0 = outermost; the grand total is level 0).
     */
    public record Line(LineType type, int level, String label, List<String> cells) {
    }

    /** Subtotal/total cells never need more precision than this. */
    private static final int AVG_SCALE = 4;
    /** A stored string that parses as an absurd decimal (1E+999999999) must not be summed. */
    private static final int MAX_ABS_SCALE = 30;
    private static final int MAX_PRECISION = 40;
    private static final String NONE_LABEL = "(none)";

    private final List<Column> columns;
    private final List<Line> lines;
    private final int rowCount;

    private ListReport(List<Column> columns, List<Line> lines, int rowCount) {
        this.columns = List.copyOf(columns);
        this.lines = List.copyOf(lines);
        this.rowCount = rowCount;
    }

    public List<Column> columns() {
        return columns;
    }

    public List<Line> lines() {
        return lines;
    }

    /** Number of detail rows (rows the viewer could see), not counting group/total lines. */
    public int rowCount() {
        return rowCount;
    }

    /**
     * Lays out {@code rows} per {@code document}. The rows MUST be the output of
     * {@code ConceptGateway.query} for the viewer -- this class trusts them to be already scoped and
     * already stripped of fields the viewer may not read.
     */
    public static ListReport build(CompiledDocument document, List<ConceptRecord> rows) {
        Set<String> presentKeys = new LinkedHashSet<>();
        for (ConceptRecord row : rows) {
            for (String key : row.data().keySet()) {
                presentKeys.add(key.toLowerCase(Locale.ROOT));
            }
        }
        boolean noRows = rows.isEmpty();

        List<Column> columns = new ArrayList<>();
        for (CompiledDocumentColumn declared : document.columns()) {
            if (declared.field() == null || declared.field().isBlank()) {
                continue;
            }
            if (!noRows && !presentKeys.contains(declared.field().toLowerCase(Locale.ROOT))) {
                continue; // nobody in this result may read it: omit the column, do not print it blank
            }
            String label = declared.label() == null || declared.label().isBlank() ? declared.field() : declared.label();
            columns.add(new Column(declared.field(), label, normalizeTotal(declared.total())));
        }

        List<String> groupFields = new ArrayList<>();
        for (String field : document.groupBy()) {
            if (field != null && !field.isBlank()
                    && (noRows || presentKeys.contains(field.toLowerCase(Locale.ROOT)))) {
                groupFields.add(field);
            }
        }

        boolean anyTotal = columns.stream().anyMatch(column -> column.total() != null);
        boolean wantSubtotals = document.subtotalsEnabled() && !groupFields.isEmpty() && anyTotal && !noRows;
        boolean wantGrandTotal = document.grandTotalEnabled() && anyTotal && !noRows;
        if ((wantSubtotals || wantGrandTotal) && columns.stream().noneMatch(column -> column.total() == null)) {
            // Every visible column carries a total (its plain label columns are hidden from this
            // viewer): a leading blank column carries the "Subtotal ..." / "Grand total" label.
            columns.add(0, new Column("", "", null));
        }

        List<Line> lines = new ArrayList<>();
        emit(rows, 0, groupFields, columns, document, wantSubtotals, lines);
        if (wantGrandTotal) {
            lines.add(totalLine(LineType.GRAND_TOTAL, 0, "Grand total", rows, columns));
        }
        return new ListReport(columns, lines, rows.size());
    }

    private static void emit(
            List<ConceptRecord> rows, int level, List<String> groupFields, List<Column> columns,
            CompiledDocument document, boolean wantSubtotals, List<Line> out) {
        if (level >= groupFields.size()) {
            for (ConceptRecord row : rows) {
                List<String> cells = new ArrayList<>(columns.size());
                for (Column column : columns) {
                    cells.add(column.synthetic() ? "" : text(valueOf(row, column.field())));
                }
                out.add(new Line(LineType.DETAIL, level, null, cells));
            }
            return;
        }
        String field = groupFields.get(level);
        Map<String, List<ConceptRecord>> groups = new LinkedHashMap<>();
        Map<String, Object> sampleValue = new LinkedHashMap<>();
        for (ConceptRecord row : rows) {
            Object value = valueOf(row, field);
            String key = text(value);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            sampleValue.putIfAbsent(key, value);
        }
        List<String> orderedKeys = new ArrayList<>(groups.keySet());
        orderedKeys.sort(groupKeyOrder(sampleValue));
        String fieldLabel = labelOf(document, field);
        for (String key : orderedKeys) {
            String shown = key.isEmpty() ? NONE_LABEL : key;
            List<ConceptRecord> groupRows = groups.get(key);
            out.add(new Line(LineType.GROUP_HEADER, level, fieldLabel + ": " + shown, List.of()));
            emit(groupRows, level + 1, groupFields, columns, document, wantSubtotals, out);
            if (wantSubtotals) {
                out.add(totalLine(LineType.SUBTOTAL, level, "Subtotal " + shown, groupRows, columns));
            }
        }
    }

    private static Line totalLine(LineType type, int level, String label, List<ConceptRecord> rows, List<Column> columns) {
        List<String> cells = new ArrayList<>(columns.size());
        boolean labelPlaced = false;
        for (Column column : columns) {
            if (column.total() != null) {
                cells.add(total(column, rows));
            } else if (!labelPlaced) {
                cells.add(label);
                labelPlaced = true;
            } else {
                cells.add("");
            }
        }
        return new Line(type, level, label, cells);
    }

    private static String total(Column column, List<ConceptRecord> rows) {
        long count = 0;
        long numericCount = 0;
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal min = null;
        BigDecimal max = null;
        for (ConceptRecord row : rows) {
            Object value = valueOf(row, column.field());
            if (value == null || (value instanceof String s && s.isBlank())) {
                continue;
            }
            count++;
            BigDecimal number = toDecimal(value);
            if (number == null) {
                continue;
            }
            numericCount++;
            sum = sum.add(number);
            min = min == null || number.compareTo(min) < 0 ? number : min;
            max = max == null || number.compareTo(max) > 0 ? number : max;
        }
        return switch (column.total()) {
            case "count" -> Long.toString(count);
            case "sum" -> numericCount == 0 ? "" : plain(sum);
            case "min" -> min == null ? "" : plain(min);
            case "max" -> max == null ? "" : plain(max);
            case "avg" -> numericCount == 0 ? ""
                    : plain(sum.divide(BigDecimal.valueOf(numericCount), AVG_SCALE, RoundingMode.HALF_UP));
            default -> "";
        };
    }

    // ------------------------------------------------------------------ output

    /** CSV of the whole layout, RFC 4180 + formula-injection defence via {@link CsvCells}. */
    public String toCsv() {
        StringBuilder csv = new StringBuilder();
        List<String> header = new ArrayList<>(columns.size());
        for (Column column : columns) {
            header.add(column.label());
        }
        csv.append(CsvCells.toCsvRow(header));
        for (Line line : lines) {
            csv.append(CsvCells.toCsvRow(cellsOf(line)));
        }
        return csv.toString();
    }

    private List<String> cellsOf(Line line) {
        if (line.type() != LineType.GROUP_HEADER) {
            return line.cells();
        }
        List<String> cells = new ArrayList<>(Math.max(1, columns.size()));
        cells.add(line.label());
        while (cells.size() < columns.size()) {
            cells.add("");
        }
        return cells;
    }

    /**
     * XHTML for the PDF renderer (it parses XML, so every value is escaped and no void element is
     * left open). Same look as the flat document's print layout.
     */
    public String toHtml(String title, String printedAt) {
        StringBuilder html = new StringBuilder();
        html.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE html>")
                .append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><style>")
                .append("body { font-family: Helvetica, Arial, sans-serif; color: #18202a; }")
                .append(".print-header { margin-bottom: 16px; border-bottom: 2px solid #18202a; padding-bottom: 8px; }")
                .append(".print-header h1 { margin: 0 0 4px 0; font-size: 20px; }")
                .append(".print-meta { font-size: 12px; color: #4a5568; }")
                .append("table.print-table { width: 100%; border-collapse: collapse; }")
                .append("table.print-table th, table.print-table td { border: 1px solid #18202a; padding: 6px 8px; text-align: left; font-size: 12px; }")
                .append("table.print-table th { background-color: #eef1f5; }")
                .append("table.print-table tr { page-break-inside: avoid; }")
                .append("table.print-table td.num { text-align: right; }")
                .append("table.print-table tr.group td { background-color: #f6f8fa; font-weight: bold; }")
                .append("table.print-table tr.subtotal td { background-color: #eef1f5; font-weight: bold; }")
                .append("table.print-table tr.grand-total td { background-color: #dde3ea; font-weight: bold; border-top: 2px solid #18202a; }")
                .append(".print-footer { margin-top: 12px; font-size: 12px; color: #4a5568; text-align: right; }")
                .append("</style></head><body>")
                .append("<div class=\"print-document\">")
                .append("<div class=\"print-header\"><h1>").append(escape(title)).append("</h1>")
                .append("<div class=\"print-meta\">Printed ").append(escape(printedAt)).append("</div></div>")
                .append("<table class=\"print-table\"><thead><tr>");
        for (Column column : columns) {
            html.append("<th>").append(escape(column.label())).append("</th>");
        }
        html.append("</tr></thead><tbody>");
        int span = Math.max(1, columns.size());
        if (lines.isEmpty()) {
            html.append("<tr><td colspan=\"").append(span).append("\">No records</td></tr>");
        }
        for (Line line : lines) {
            switch (line.type()) {
                case GROUP_HEADER -> html.append("<tr class=\"group\"><td colspan=\"").append(span)
                        .append("\" style=\"padding-left: ").append(8 + 14 * line.level()).append("px\">")
                        .append(escape(line.label())).append("</td></tr>");
                case DETAIL -> appendCells(html, "", line);
                case SUBTOTAL -> appendCells(html, " class=\"subtotal\"", line);
                case GRAND_TOTAL -> appendCells(html, " class=\"grand-total\"", line);
            }
        }
        html.append("</tbody></table>")
                .append("<div class=\"print-footer\">Total: ").append(rowCount).append(" record(s)</div>")
                .append("</div></body></html>");
        return html.toString();
    }

    private void appendCells(StringBuilder html, String trAttributes, Line line) {
        html.append("<tr").append(trAttributes).append(">");
        for (int index = 0; index < columns.size(); index++) {
            boolean numeric = columns.get(index).total() != null;
            html.append(numeric ? "<td class=\"num\">" : "<td>")
                    .append(escape(index < line.cells().size() ? line.cells().get(index) : ""))
                    .append("</td>");
        }
        html.append("</tr>");
    }

    // ----------------------------------------------------------------- helpers

    private static String normalizeTotal(String total) {
        return total == null || total.isBlank() ? null : total.toLowerCase(Locale.ROOT);
    }

    private static String labelOf(CompiledDocument document, String field) {
        for (CompiledDocumentColumn column : document.columns()) {
            if (field.equalsIgnoreCase(column.field()) && column.label() != null && !column.label().isBlank()) {
                return column.label();
            }
        }
        return field;
    }

    /** Case-tolerant read of one field from a returned record; {@code null} when absent or null. */
    private static Object valueOf(ConceptRecord row, String field) {
        Map<String, Object> data = row.data();
        if (data.containsKey(field)) {
            return data.get(field);
        }
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(field)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number) {
            BigDecimal number = toDecimal(value);
            if (number != null) {
                return plain(number);
            }
        }
        return String.valueOf(value);
    }

    private static BigDecimal toDecimal(Object value) {
        BigDecimal number;
        try {
            if (value instanceof BigDecimal big) {
                number = big;
            } else if (value instanceof Double || value instanceof Float) {
                double d = ((Number) value).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    return null;
                }
                number = BigDecimal.valueOf(d);
            } else if (value instanceof Number n) {
                number = new BigDecimal(n.toString());
            } else if (value instanceof String s && !s.isBlank()) {
                number = new BigDecimal(s.trim());
            } else {
                return null;
            }
        } catch (NumberFormatException notNumeric) {
            return null;
        }
        if (Math.abs(number.scale()) > MAX_ABS_SCALE || number.precision() > MAX_PRECISION) {
            return null;
        }
        return number;
    }

    private static String plain(BigDecimal number) {
        BigDecimal stripped = number.signum() == 0 ? BigDecimal.ZERO : number.stripTrailingZeros();
        return stripped.toPlainString();
    }

    /** Numeric order when both group keys are numbers, else case-insensitive text; empty (none) last. */
    private static Comparator<String> groupKeyOrder(Map<String, Object> sampleValue) {
        return (a, b) -> {
            if (a.isEmpty() || b.isEmpty()) {
                return a.isEmpty() == b.isEmpty() ? 0 : (a.isEmpty() ? 1 : -1);
            }
            BigDecimal numberA = toDecimal(sampleValue.get(a));
            BigDecimal numberB = toDecimal(sampleValue.get(b));
            if (numberA != null && numberB != null) {
                return numberA.compareTo(numberB);
            }
            int byText = a.compareToIgnoreCase(b);
            return byText != 0 ? byText : a.compareTo(b);
        };
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
