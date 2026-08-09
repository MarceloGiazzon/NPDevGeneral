package com.finalexec.db;

import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;
import com.npdev.kernel.concepts.ValueExpressionEvaluator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Move 9 B1 (docs/ACCEPTED_BOUNDARIES.md B2): a dry-run preview of what an expression-default
 * backfill WOULD do against the live database -- rows affected, distinct resulting values, and rows
 * where evaluation produces no value (a referenced field absent from that row; applying anyway would
 * violate the NOT NULL constraint {@link BackfillPass} is about to add). Never writes anything.
 *
 * <p>Uses the SAME {@link ValueExpressionEvaluator} a new row's {@code defaultExpression}/
 * {@code derivedExpression} already evaluates at write time (kernel) -- one evaluator, applied here
 * against every EXISTING row instead of one new row. Flat sibling of {@link BackfillPass} in this
 * same package, reusing its {@link BackfillPass#backfillItemsFromDiff} derivation so "which columns
 * are pending a backfill" has one source of truth.
 */
public final class ExpressionBackfillPreview {

    private ExpressionBackfillPreview() {
    }

    /** One column pending an expression-default backfill. {@code distinctValues} is capped at 20 for
     * a sane preview payload; {@code rowsAffected}/{@code failedRowIds} are always exact. */
    public record Item(String table, String column, String expression, long rowsAffected,
                List<String> distinctValues, List<String> failedRowIds) {

        boolean hasFailures() {
            return !failedRowIds.isEmpty();
        }
    }

    /** Read-only: evaluates every pending expression-default column against its table's currently
     * affected rows (NULL for an existing column, every row for a not-yet-added one). Writes nothing. */
    public static List<Item> preview(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        List<Item> items = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (BackfillPass.BackfillItem candidate : BackfillPass.backfillItemsFromDiff(dataSource, manifest)) {
                if (!candidate.refusal()) {
                    continue; // has a literal default -- BackfillPass's own literal path handles it
                }
                String expression = manifest.businessTableColumnDefaultExpressions()
                        .getOrDefault(candidate.table(), Map.of()).get(candidate.column());
                if (expression == null || expression.isBlank()) {
                    continue; // "no default at all" refusal -- an expression preview has nothing to show
                }
                items.add(evaluate(connection, candidate.table(), candidate.column(), expression));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed previewing expression-default backfill(s)", exception);
        }
        return items;
    }

    /** One row's evaluated expression-default value ({@code null} means evaluation failed for this
     * row -- a referenced field absent from it). {@code rawId} is that row's raw {@code id} column
     * value (e.g. a real {@code java.util.UUID} on H2, not a re-parsed string) -- rebind it via
     * {@code setObject} as-is for a reliable {@code WHERE id = ?}, the same coercion-avoidance
     * {@code JdbcBusinessConceptStore.coerceId} exists for elsewhere in this package. */
    record RowValue(Object rawId, Object value) {
        String displayId() {
            return String.valueOf(rawId);
        }
    }

    /** Scans every row {@code column} would backfill (NULL rows if it already exists live; every row
     * if it does not) and evaluates {@code expression} against each -- the ONE row-scanning pass both
     * {@link #preview} (dry-run summary) and {@code BackfillPass}'s apply step (fresh re-evaluation
     * right before writing, never trusting a stale preview) share. */
    static List<RowValue> evaluateRows(Connection connection, String table, String column, String expression) throws SQLException {
        String safeTable = SchemaLifecycleExecutor.quotedIdentifier(table);
        boolean columnExistsLive = SchemaLifecycleExecutor.readActualColumns(connection.getMetaData(), table).stream()
                .anyMatch(column::equalsIgnoreCase);
        String sql = "SELECT * FROM " + safeTable
                + (columnExistsLive ? " WHERE " + SchemaLifecycleExecutor.quotedIdentifier(column) + " IS NULL" : "");
        List<RowValue> results = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData metadata = resultSet.getMetaData();
            long ordinal = 0;
            while (resultSet.next()) {
                ordinal++;
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    String label = metadata.getColumnLabel(i);
                    Object value = resultSet.getObject(i);
                    row.put(label, value);
                    // A defaultExpression's "$field" references the MODEL field name (camelCase), not
                    // the db column name (snake_case) -- also key the row by the derived camelCase
                    // name so ValueExpressionEvaluator's $-lookup resolves the same way it does for a
                    // new row's data map (which is always model-cased).
                    String camelCase = toCamelCase(label);
                    if (!camelCase.equals(label)) {
                        row.put(camelCase, value);
                    }
                }
                Object result = ValueExpressionEvaluator.evaluate(expression, row);
                Object rawId = row.containsKey("id") ? row.get("id") : ("row#" + ordinal);
                results.add(new RowValue(rawId, result));
            }
        }
        return results;
    }

    /** Package-private (Move 9 B1): {@code BackfillPass} calls this directly for a candidate it
     * already found via its own {@link BackfillPass#backfillItemsFromDiff} scan, rather than
     * triggering {@link #preview}'s independent re-scan a second time in the same boot. */
    static Item evaluate(Connection connection, String table, String column, String expression) throws SQLException {
        List<RowValue> rows = evaluateRows(connection, table, column, expression);
        Set<String> distinctValues = new LinkedHashSet<>();
        List<String> failedRowIds = new ArrayList<>();
        for (RowValue row : rows) {
            if (row.value() == null) {
                failedRowIds.add(row.displayId());
            } else if (distinctValues.size() < 20) {
                distinctValues.add(String.valueOf(row.value()));
            }
        }
        return new Item(table, column, expression, rows.size(), List.copyOf(distinctValues), failedRowIds);
    }

    /**
     * Derives the model field name from a raw JDBC column label -- always lowercases FIRST, not
     * only when an underscore is present: H2 (by default) reports an unquoted single-word column
     * like {@code quantity} back as {@code "QUANTITY"}, which has no underscore to split on, so a
     * lowercase-only-when-there's-an-underscore check would leave it unchanged and every {@code
     * $quantity} reference would silently fail to resolve. Safe for an already-lowercase label from
     * another engine too: {@code toCamelCase("quantity")} still returns {@code "quantity"}.
     */
    private static String toCamelCase(String columnLabel) {
        if (columnLabel == null) {
            return null;
        }
        String[] parts = columnLabel.toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                out.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            }
        }
        return out.toString();
    }

    /** The stable string form of one item, for {@link DestructiveAckToken#compute} -- reused verbatim
     * by both the preview endpoint (which computes the token to show an operator) and BackfillPass's
     * own apply-time re-derivation (which must compute the byte-identical token to verify a submitted
     * acknowledgment against). */
    static String stableString(Item item) {
        return "EXPRESSION_BACKFILL:" + item.table() + "." + item.column() + ":" + item.expression();
    }

    public static String expectedToken(String schemaFingerprint, List<Item> items) {
        return DestructiveAckToken.compute(schemaFingerprint, items.stream().map(ExpressionBackfillPreview::stableString).toList());
    }
}
