package com.npdev.dsl.v1.compiled;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Shared SQL identifier and bond-shape naming for compiled models.
 * Generator DDL and runtime SQL must use this class instead of mirrored logic.
 */
public final class SqlIdentifierSupport {
    public static final int POSTGRES_IDENTIFIER_LIMIT = 63;
    private static final int HASH_HEX_LENGTH = 8;
    private static final int HASH_SUFFIX_LENGTH = 1 + HASH_HEX_LENGTH;
    private static final int LONG_IDENTIFIER_PREFIX_LENGTH = POSTGRES_IDENTIFIER_LIMIT - HASH_SUFFIX_LENGTH;

    private SqlIdentifierSupport() {
    }

    public static String toSnake(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim().replace("::", "_");
        StringBuilder out = new StringBuilder(trimmed.length() + 8);
        char previous = '\0';
        for (int index = 0; index < trimmed.length(); index++) {
            char current = trimmed.charAt(index);
            if (Character.isUpperCase(current)
                    && index > 0
                    && (Character.isLowerCase(previous) || Character.isDigit(previous))) {
                out.append('_');
            }
            if (Character.isLetterOrDigit(current)) {
                out.append(Character.toLowerCase(current));
            } else {
                out.append('_');
            }
            previous = current;
        }
        return out.toString()
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
    }

    public static String toSnakePlural(String value) {
        String base = toSnake(value);
        if (base.isBlank() || base.endsWith("s")) {
            return base;
        }
        return base + "s";
    }

    public static String safeSqlIdentifier(String rawName) {
        String normalized = toSnake(rawName);
        if (normalized.length() <= POSTGRES_IDENTIFIER_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, LONG_IDENTIFIER_PREFIX_LENGTH) + "_" + shortHash(rawName);
    }

    public static String tableName(CompiledConcept entity) {
        if (entity == null) {
            return "";
        }
        return tableName(entity.getName(), entity.getTableName());
    }

    /**
     * Resolve a concept's physical table name from its authoring name and an optional explicit
     * table-name override -- the same convention {@link #tableName(CompiledConcept)} applies,
     * factored out so LNCH-1 P2's concept-rename logic can derive the OLD table name (old concept
     * name + the CURRENT concept's explicit override, if any) without re-deriving the
     * toSnakePlural/safeSqlIdentifier convention by hand. When {@code explicitOverride} is
     * null/blank, the table name is derived from {@code name} via {@link #toSnakePlural(String)};
     * otherwise the override is used verbatim (through {@link #safeSqlIdentifier(String)}).
     */
    public static String tableName(String name, String explicitOverride) {
        String table = explicitOverride;
        if (table == null || table.isBlank()) {
            table = toSnakePlural(name);
        }
        return safeSqlIdentifier(table);
    }

    public static String columnName(CompiledField field) {
        return field == null ? "" : safeSqlIdentifier(field.getName());
    }

    public static String junctionTableName(CompiledConcept sourceEntity, CompiledField sourceField) {
        return junctionTableName(tableName(sourceEntity), sourceField == null ? "" : sourceField.getName());
    }

    public static String junctionTableName(String sourceTable, String sourceFieldName) {
        return safeSqlIdentifier((sourceTable == null ? "" : sourceTable) + "_" + toSnake(sourceFieldName));
    }

    public static String sourceJunctionColumn(CompiledField sourceIdField) {
        return "source_" + columnName(sourceIdField);
    }

    public static String targetJunctionColumn(CompiledField targetAnchorField) {
        return "target_" + columnName(targetAnchorField);
    }

    private static String shortHash(String rawName) {
        String value = rawName == null ? "" : rawName;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, HASH_HEX_LENGTH).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for SQL identifier hashing", exception);
        }
    }
}
