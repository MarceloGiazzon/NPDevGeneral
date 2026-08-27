package com.finalexec.db.datamobility;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes and re-parses one canonical, self-consistent INSERT-statement grammar for the "SQL Insert
 * Statements" export format -- kept in a single class for the same reason as {@link CsvRowFormat}:
 * the writer and the reader must never drift apart.
 *
 * <p><b>Scope, stated plainly:</b> this reads statements in EXACTLY the shape it writes --
 * {@code INSERT INTO <table> (<col>, <col>, ...) VALUES (<literal>, <literal>, ...);}, one
 * statement per line, values written {@code NULL} / bare number / bare {@code TRUE}/{@code FALSE}
 * / single-quoted string ({@code ''}-escaped). It is NOT a general SQL parser and does not accept
 * an arbitrary hand-written or third-party SQL dump -- an import from such a file should fail
 * loudly with a clear "not in the expected grammar" message rather than silently misreading it.
 * Values are re-hydrated into JDBC-bindable Java objects (String/Long/Double/Boolean/null) by
 * {@link DataImporter}, never re-executed as raw SQL text against the target -- this is what keeps
 * booleans/dates portable across engines that spell them differently (SQL Server has no
 * {@code TRUE}/{@code FALSE} literal, for one).
 */
final class SqlInsertRowFormat {

    private SqlInsertRowFormat() {
    }

    static String literalOf(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        if (value instanceof Number n) {
            return n.toString();
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }

    static String insertStatement(String tableName, List<String> columnNames, List<Object> values) {
        StringBuilder sb = new StringBuilder("INSERT INTO ");
        sb.append(tableName).append(" (");
        for (int i = 0; i < columnNames.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(columnNames.get(i));
        }
        sb.append(") VALUES (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(literalOf(values.get(i)));
        }
        sb.append(");");
        return sb.toString();
    }

    /** One parsed statement: the table name and the raw literal text of each value, in column order. */
    record ParsedInsert(String tableName, List<String> columnNames, List<String> rawLiterals) {
    }

    /**
     * @return the next parsed INSERT statement, or {@code null} at end-of-stream
     * @throws IllegalArgumentException if a non-blank line isn't in the expected grammar
     */
    static ParsedInsert readStatement(BufferedReader in) throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            return parseLine(trimmed);
        }
        return null;
    }

    private static ParsedInsert parseLine(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("INSERT INTO ")) {
            throw new IllegalArgumentException("Not a recognized INSERT statement: " + line);
        }
        int columnsOpen = line.indexOf('(');
        int columnsClose = line.indexOf(')', columnsOpen);
        int valuesKeyword = upper.indexOf("VALUES", columnsClose);
        int valuesOpen = line.indexOf('(', valuesKeyword);
        int valuesClose = line.lastIndexOf(')');
        if (columnsOpen < 0 || columnsClose < 0 || valuesKeyword < 0 || valuesOpen < 0 || valuesClose < 0) {
            throw new IllegalArgumentException("Not a recognized INSERT statement: " + line);
        }
        String tableName = line.substring("INSERT INTO ".length(), columnsOpen).trim();
        List<String> columnNames = splitTopLevel(line.substring(columnsOpen + 1, columnsClose));
        List<String> rawLiterals = splitTopLevel(line.substring(valuesOpen + 1, valuesClose));
        for (int i = 0; i < columnNames.size(); i++) {
            columnNames.set(i, columnNames.get(i).trim());
        }
        return new ParsedInsert(tableName, columnNames, rawLiterals);
    }

    /** Splits a comma-separated list at the top level only -- commas inside a quoted string literal
     *  don't split. */
    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                current.append(c);
                if (c == '\'') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        current.append('\'');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                }
            } else if (c == '\'') {
                inQuotes = true;
                current.append(c);
            } else if (c == ',') {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString().trim());
        return parts;
    }

    /** Re-hydrates one raw literal (from {@link ParsedInsert#rawLiterals()}) into a JDBC-bindable
     *  Java object, given the column's portable category from the schema manifest. */
    static Object valueOf(String rawLiteral, String portableCategory) {
        if ("NULL".equals(rawLiteral)) {
            return null;
        }
        if (rawLiteral.length() >= 2 && rawLiteral.charAt(0) == '\'' && rawLiteral.charAt(rawLiteral.length() - 1) == '\'') {
            return rawLiteral.substring(1, rawLiteral.length() - 1).replace("''", "'");
        }
        if ("BOOLEAN".equalsIgnoreCase(portableCategory)) {
            return "TRUE".equalsIgnoreCase(rawLiteral);
        }
        if ("DECIMAL".equalsIgnoreCase(portableCategory)) {
            return new java.math.BigDecimal(rawLiteral);
        }
        if ("LONG".equalsIgnoreCase(portableCategory)) {
            return Long.parseLong(rawLiteral);
        }
        if ("INT".equalsIgnoreCase(portableCategory)) {
            return Integer.parseInt(rawLiteral);
        }
        // Unquoted and not a recognized numeric/boolean category -- return the raw text rather
        // than guessing further.
        return rawLiteral;
    }
}
