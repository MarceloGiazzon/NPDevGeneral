package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.generator.output.GeneratedSourceWriter;

import java.util.Set;

/**
 * Emits {@code application-npdev-auth.properties}, translating the resolved {@code auth.mode} setting
 * into the runtime Spring properties consumed by the RuntimeHost
 * ({@code npdev.auth.enabled} / {@code npdev.auth.mode}).
 *
 * <p>The file is loaded via {@code spring.config.import} in the RuntimeHost profiles (the same
 * mechanism as the generated {@code application-npdev-db.properties}). It is only emitted when the
 * model explicitly personalizes {@code auth.mode}; default-config apps emit nothing and keep the
 * RuntimeHost profile defaults, so existing behaviour is unchanged.</p>
 *
 * <p>REG-211: when {@code auth.mode=jwt}, the model MUST contain a credential-bearing concept
 * (one with a {@code reference} field targeting a User concept and a {@code string} field whose
 * name suggests a password hash). If none is found, emission fails fast with a clear message
 * rather than shipping an app whose own bootstrap 500s. When one IS found, the emitter writes
 * {@code npdev.auth.login.credential-table} / {@code credential-user-id-column} /
 * {@code credential-password-column} overrides so the RuntimeHost auth stack points at the
 * correct physical table instead of falling back to WmsOffice-specific defaults.</p>
 */
public final class RuntimeAuthPropertiesEmitter {

    public static final String RELATIVE_PATH = "src/main/resources/application-npdev-auth.properties";

    /**
     * Field-name suffixes that suggest a PASSWORD hash column (lowercased). Deliberately excludes
     * the bare "hash" suffix: a reset-token column like {@code tokenHash} (identity pack's
     * PasswordResetToken) ends in "hash" but is NOT a login credential -- including it let the
     * detector mispoint {@code npdev.auth.login.credential-*} at the password-reset-token table
     * for any jwt-mode app composing the identity pack (the WmsOffice failure that shipped
     * bootstrap-admin 500s and login 401s despite a correct `usuarios` credential concept).
     */
    private static final Set<String> HASH_FIELD_SUFFIXES = Set.of("senhahash", "passwordhash", "senha", "password");

    private final GeneratedSourceWriter writer;

    public RuntimeAuthPropertiesEmitter(GeneratedSourceWriter writer) {
        this.writer = writer;
    }

    public void emit(String authMode) {
        writer.writeRelative(RELATIVE_PATH, properties(authMode));
    }

    /**
     * REG-211: full emission path that also inspects the compiled model for a credential-bearing
     * concept when {@code auth.mode=jwt}, emitting credential-table overrides when one is found
     * and failing fast when none exists.
     */
    public void emit(String authMode, CompiledModel model) {
        writer.writeRelative(RELATIVE_PATH, properties(authMode, model));
    }

    /** Maps the resolved {@code auth.mode} value to RuntimeHost Spring properties. */
    static String properties(String authMode) {
        return properties(authMode, null);
    }

    /** REG-211: credential-aware variant. */
    static String properties(String authMode, CompiledModel model) {
        String mode = authMode == null ? "" : authMode.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("# Generated from the resolved NPDev auth.mode setting.\n");
        sb.append("# Loaded via spring.config.import in the RuntimeHost profiles.\n");
        if ("none".equalsIgnoreCase(mode)) {
            sb.append("npdev.auth.enabled=false\n");
            return sb.toString();
        }
        String runtimeMode = "jwt".equalsIgnoreCase(mode) ? "jwt" : "apikey";
        sb.append("npdev.auth.enabled=true\n");
        sb.append("npdev.auth.mode=").append(runtimeMode).append("\n");

        if ("jwt".equalsIgnoreCase(mode) && model != null) {
            CredentialMapping credential = findCredentialConcept(model);
            if (credential == null) {
                throw new IllegalArgumentException(
                        "jwt-mode requires a credential-bearing concept in the model -- "
                                + "a concept with a reference field targeting a User concept "
                                + "and a string hash field (e.g. senhaHash / passwordHash). "
                                + "None found among the model's " + model.getConcepts().size()
                                + " concept(s). Add a credential concept (like Usuario or Credential) "
                                + "or switch auth.mode to apiKey.");
            }
            sb.append("npdev.auth.login.credential-table=").append(credential.table).append("\n");
            sb.append("npdev.auth.login.credential-user-id-column=").append(credential.userIdColumn).append("\n");
            sb.append("npdev.auth.login.credential-password-column=").append(credential.passwordColumn).append("\n");
        }
        return sb.toString();
    }

    /**
     * Scans the model's concepts for a credential bearer: one that carries both a
     * {@code reference} field whose target ends with {@code User} and a {@code string} field
     * whose lowercased name ends with one of {@link #HASH_FIELD_SUFFIXES}.
     *
     * @return a {@link CredentialMapping} with the resolved physical table and column names,
     *         or {@code null} if no credential concept is found
     */
    static CredentialMapping findCredentialConcept(CompiledModel model) {
        if (model == null) {
            return null;
        }
        for (CompiledConcept concept : model.getConcepts()) {
            CompiledField userIdField = null;
            CompiledField hashField = null;
            for (CompiledField field : concept.getFields()) {
                if (isUserReference(field)) {
                    userIdField = field;
                } else if (isPasswordHashField(field)) {
                    hashField = field;
                }
            }
            if (userIdField != null && hashField != null) {
                String table = SqlIdentifierSupport.tableName(concept);
                String userIdColumn = SqlIdentifierSupport.columnName(userIdField);
                String passwordColumn = SqlIdentifierSupport.columnName(hashField);
                return new CredentialMapping(table, userIdColumn, passwordColumn);
            }
        }
        return null;
    }

    private static boolean isUserReference(CompiledField field) {
        if (!"reference".equals(field.getDslType())) {
            return false;
        }
        String target = field.getReferenceTarget();
        if (target == null || target.isBlank()) {
            return false;
        }
        return target.equals("User") || target.endsWith("::User");
    }

    private static boolean isPasswordHashField(CompiledField field) {
        if (!"string".equals(field.getDslType())) {
            return false;
        }
        String name = field.getName();
        if (name == null || name.isBlank()) {
            return false;
        }
        String lower = name.toLowerCase();
        for (String suffix : HASH_FIELD_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** Resolved physical names for the credential table and its two required columns. */
    record CredentialMapping(String table, String userIdColumn, String passwordColumn) {
    }
}
