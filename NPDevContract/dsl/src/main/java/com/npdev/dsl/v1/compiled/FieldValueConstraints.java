package com.npdev.dsl.v1.compiled;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The declared value constraints of a scalar field ({@code maxLength}, {@code min}/{@code max},
 * {@code precision}/{@code scale}), checked against one candidate value. REG-254/255/256: the DSL
 * accepted and documented these keys, the DDL honoured {@code maxLength}/{@code precision}/{@code scale}
 * in the column type, but the write path never rejected an offending value, so a 201-character title
 * or a {@code rows: -5} was stored as sent.
 *
 * <p>This is the ONE implementation: the generated CRUD surface
 * ({@code CrudSchemaValidationSupport}) and any other writer that already holds a
 * {@link CompiledField} (the flow {@code createConcept} path) call {@link #violations} so the rules
 * cannot drift apart. Pure and side-effect free; it never coerces or rounds, it only reports.
 *
 * <p>Each message names the field in single quotes ({@code field 'rows'}), the convention the
 * runtime's violation mapper reads the failing path from.
 */
public final class FieldValueConstraints {

    private static final Set<String> NUMERIC_TYPES = Set.of("int", "integer", "long", "decimal", "double", "float", "number", "short");
    /** Types whose string value is NOT free text, so a text length limit does not apply to them. */
    private static final Set<String> NON_TEXT_TYPES = Set.of(
            "enum", "date", "datetime", "reference", "uuid", "object", "array", "boolean", "file");
    /** The precision a decimal column really has when only its scale is declared (SqlTypeSupport). */
    private static final int DEFAULT_PRECISION = 19;
    /** The scale a decimal column really has when only its precision is declared (SqlTypeSupport). */
    private static final int DEFAULT_SCALE = 4;

    private FieldValueConstraints() {
    }

    /**
     * Every declared constraint {@code value} breaks for {@code field}; empty when it satisfies all
     * of them, when it is {@code null} (absence is a {@code required} concern), or when the field
     * declares none.
     */
    public static List<String> violations(String entityName, CompiledField field, Object value) {
        List<String> out = new ArrayList<>();
        if (field == null || value == null || field.getSchema() == null) {
            return out;
        }
        CompiledSchema schema = field.getSchema();
        if (schema.getDerivedExpression() != null && !schema.getDerivedExpression().isBlank()) {
            // A derived value is computed by the platform (in floating point), never supplied by the
            // caller: 3 * 12.34 may come out as 37.019999999999996. Not the caller's to be refused for.
            return out;
        }
        String type = field.getDslType() == null ? "" : field.getDslType().trim().toLowerCase(Locale.ROOT);
        String where = "Entity " + entityName + ": field '" + field.getName() + "'";

        if (value instanceof CharSequence text) {
            Integer maxLength = schema.getMaxLength();
            if (maxLength != null && !NON_TEXT_TYPES.contains(type) && !NUMERIC_TYPES.contains(type)
                    && text.length() > maxLength) {
                out.add(where + " exceeds maxLength " + maxLength + " (got " + text.length() + " characters)");
            }
            if (!NUMERIC_TYPES.contains(type)) {
                return out;
            }
        }

        if (!NUMERIC_TYPES.contains(type)) {
            return out;
        }
        BigDecimal number = toBigDecimal(value);
        if (number == null) {
            return out;
        }
        if (schema.getMin() != null && number.compareTo(BigDecimal.valueOf(schema.getMin())) < 0) {
            out.add(where + " is below min " + plain(schema.getMin()) + " (got " + number.toPlainString() + ")");
        }
        if (schema.getMax() != null && number.compareTo(BigDecimal.valueOf(schema.getMax())) > 0) {
            out.add(where + " is above max " + plain(schema.getMax()) + " (got " + number.toPlainString() + ")");
        }
        if ("decimal".equals(type)) {
            decimalViolations(where, schema, number, out);
        }
        return out;
    }

    private static void decimalViolations(String where, CompiledSchema schema, BigDecimal number, List<String> out) {
        Integer precision = schema.getPrecision();
        Integer scale = schema.getScale();
        if (precision == null && scale == null) {
            return;
        }
        BigDecimal significant = number.stripTrailingZeros();
        // Digits after the point that the value really needs (trailing zeros carry no information).
        int valueScale = Math.max(significant.scale(), 0);
        if (scale != null && valueScale > scale) {
            out.add(where + " has " + valueScale + " decimal places, more than its declared scale " + scale);
        }
        int columnPrecision = precision != null ? precision : DEFAULT_PRECISION;
        int columnScale = scale != null ? scale : DEFAULT_SCALE;
        int integerDigits = Math.max(significant.precision() - significant.scale(), 0);
        if (integerDigits > Math.max(columnPrecision - columnScale, 0)) {
            out.add(where + " has " + integerDigits + " integer digits, more than its declared precision "
                    + columnPrecision + " and scale " + columnScale + " allow ("
                    + Math.max(columnPrecision - columnScale, 0) + ")");
        }
    }

    private static BigDecimal toBigDecimal(Object value) {
        try {
            if (value instanceof BigDecimal decimal) {
                return decimal;
            }
            if (value instanceof Number || value instanceof CharSequence) {
                String text = value.toString().trim();
                return text.isEmpty() ? null : new BigDecimal(text);
            }
        } catch (NumberFormatException ignored) {
            // A non-numeric value is the type validator's business, not a range violation.
        }
        return null;
    }

    private static String plain(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
