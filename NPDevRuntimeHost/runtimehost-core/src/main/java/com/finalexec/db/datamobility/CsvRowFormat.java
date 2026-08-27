package com.finalexec.db.datamobility;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC4180-shaped CSV read/write for one table's rows, kept in a single class so the format the
 * exporter writes and the format the importer parses can never quietly drift apart -- the same
 * discipline {@code SqlDialect}'s header comment argues for a dialect-bound question.
 *
 * <p>Every value round-trips as a string; {@link DataExporter}/{@link DataImporter} are
 * responsible for converting to/from the column's real JDBC type using the accompanying
 * schema manifest, not this class -- CSV itself carries no type information.
 */
final class CsvRowFormat {

    private CsvRowFormat() {
    }

    static void writeHeader(Writer out, List<String> columnNames) throws IOException {
        writeRow(out, columnNames);
    }

    /** {@code null} is written as an entirely empty, UNQUOTED field -- distinct from an empty
     *  string, which is written as {@code ""}. */
    static void writeRow(Writer out, List<String> values) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.write(',');
            }
            String value = values.get(i);
            if (value != null) {
                out.write(quote(value));
            }
        }
        out.write("\r\n");
    }

    static String quote(String value) {
        boolean needsQuoting = value.isEmpty()
                || value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuoting) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /**
     * @return the parsed row, with {@code null} for an unquoted empty field and {@code ""} for a
     *         quoted empty field -- the exact inverse of {@link #writeRow}; {@code null} at
     *         end-of-stream
     */
    static List<String> readRow(BufferedReader in) throws IOException {
        int first = in.read();
        if (first == -1) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldWasQuoted = false;
        int c = first;
        while (true) {
            if (c == -1) {
                fields.add(finish(current, fieldWasQuoted));
                break;
            }
            if (inQuotes) {
                if (c == '"') {
                    int next = in.read();
                    if (next == '"') {
                        current.append('"');
                    } else {
                        inQuotes = false;
                        if (next == ',' ) {
                            fields.add(finish(current, fieldWasQuoted));
                            current.setLength(0);
                            fieldWasQuoted = false;
                        } else if (next == '\r' || next == '\n' || next == -1) {
                            if (next == '\r') {
                                in.mark(1);
                                int peek = in.read();
                                if (peek != '\n') {
                                    in.reset();
                                }
                            }
                            fields.add(finish(current, fieldWasQuoted));
                            return fields;
                        } else {
                            current.append((char) next);
                        }
                    }
                } else {
                    current.append((char) c);
                }
            } else {
                if (c == '"' && current.length() == 0) {
                    inQuotes = true;
                    fieldWasQuoted = true;
                } else if (c == ',') {
                    fields.add(finish(current, fieldWasQuoted));
                    current.setLength(0);
                    fieldWasQuoted = false;
                } else if (c == '\r' || c == '\n') {
                    if (c == '\r') {
                        in.mark(1);
                        int peek = in.read();
                        if (peek != '\n') {
                            in.reset();
                        }
                    }
                    fields.add(finish(current, fieldWasQuoted));
                    return fields;
                } else {
                    current.append((char) c);
                }
            }
            c = in.read();
        }
        return fields;
    }

    private static String finish(StringBuilder current, boolean wasQuoted) {
        return (current.length() == 0 && !wasQuoted) ? null : current.toString();
    }
}
