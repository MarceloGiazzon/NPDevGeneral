package com.npdev.dsl.v1.compiled;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    /**
     * ADR-0011 D4 (B20, v1; S8 Wave 4, v2): resolves which string a context-qualified concept/member
     * name ({@code contextName::Name}) actually mangles through to a physical SQL identifier --
     * D4 v1's default strips a prefix matching one of this model's OWN declared, non-isolating
     * contexts (the qualifier is invisible to the physical schema); Wave 4's v2 opt-in
     * ({@code physicallyIsolate: true} on that context) keeps the qualifier, mangled by
     * {@link #toSnake}'s existing {@code "::" -> "_"} replacement -- the SAME mangling a
     * pack-qualified name already gets, not a new deriver. A prefix that does not match ANY
     * declared context (a pack-qualified name, or simply unrecognized) is untouched either way.
     *
     * <p>Shared by {@code ModelCompiler#tableNameSource} (the actual compiled table name) AND the
     * table-name-collision validator ({@code ConceptValidation#validateTableNameCollisions}) --
     * both MUST make this exact same decision, or the validator could miss a real collision (or
     * flag a false one) the compiler resolves differently. One deriver, two callers, not two
     * deriver copies that can drift.
     *
     * @param contextPhysicallyIsolateByName every context this model declares, name -> its
     *                                       {@code physicallyIsolate} flag
     */
    public static String contextAwareIdentifierSource(
            String qualifiedName, Map<String, Boolean> contextPhysicallyIsolateByName) {
        if (qualifiedName == null) {
            return null;
        }
        int split = qualifiedName.indexOf("::");
        if (split <= 0) {
            return qualifiedName;
        }
        String prefix = qualifiedName.substring(0, split);
        Boolean physicallyIsolate = contextPhysicallyIsolateByName.get(prefix);
        if (physicallyIsolate == null || physicallyIsolate) {
            // Not a declared context (pack-qualified / unrecognized), or a declared context that
            // opted into v2 physical isolation -- either way, keep the qualifier as-is.
            return qualifiedName;
        }
        // v1 default: the context qualifier is invisible to the physical schema.
        return qualifiedName.substring(split + 2);
    }

    /**
     * PK-2: like {@link #contextAwareIdentifierSource}, but for a PACK-derived concept, replaces
     * whatever qualifier currently prefixes {@code qualifiedName} (the importing app's alias --
     * {@code auth::User}, say) with the pack's own physical qualifier ({@code identity_v1::User}),
     * derived from the pack's real {@code pack} id + major version, not the local alias. Two apps
     * importing the same pack under different aliases must produce the identical physical table
     * name; only the derivation this method feeds does that -- {@link #contextAwareIdentifierSource}
     * alone cannot, since a pack alias is never a registered context and so survives untouched.
     *
     * <p>When {@code physicalQualifier} is null (a non-pack concept), delegates unchanged to
     * {@link #contextAwareIdentifierSource} -- the {@code physicallyIsolate} bounded-context
     * mechanism is completely unaffected by this method's existence.
     */
    public static String physicalTableNameSource(
            String qualifiedName,
            String physicalQualifier,
            Map<String, Boolean> contextPhysicallyIsolateByName
    ) {
        if (physicalQualifier == null || physicalQualifier.isBlank()) {
            return contextAwareIdentifierSource(qualifiedName, contextPhysicallyIsolateByName);
        }
        if (qualifiedName == null) {
            return null;
        }
        int split = qualifiedName.indexOf("::");
        String bareName = split <= 0 ? qualifiedName : qualifiedName.substring(split + 2);
        return physicalQualifier + "::" + bareName;
    }

    /**
     * PK-2: recomputes a concept's table name via the PRE-PK-2 derivation (alias-preserving,
     * ignoring any pack physical qualifier) -- deliberately ignoring {@code entity.getTableName()},
     * which after PK-2 already reflects the pack-and-version-aware physical name. Two independent
     * uses: (1) REST routes ({@code ControllerEmitter}/generated UI) stay decoupled from a pack's
     * physical table name on purpose, so a version bump never silently breaks a client's bookmarked
     * URL; (2) {@code SchemaRealizationEmitter} diffs this against the real (new) table name to
     * detect and declare an automatic rename on regeneration.
     */
    public static String aliasPreservingTableName(CompiledConcept entity, List<CompiledContext> contexts) {
        if (entity == null) {
            return "";
        }
        Map<String, Boolean> contextPhysicallyIsolateByName = new HashMap<>();
        if (contexts != null) {
            for (CompiledContext context : contexts) {
                contextPhysicallyIsolateByName.put(context.name(), context.physicallyIsolate());
            }
        }
        String source = contextAwareIdentifierSource(entity.getName(), contextPhysicallyIsolateByName);
        return tableName(source, null);
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
