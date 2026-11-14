package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.generator.output.GeneratedSourceWriter;

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
 * <p>FORM P0 (REG-339): when {@code auth.mode=jwt}, the password store is the identity pack's
 * {@code Credential} concept -- a {@code system} concept the platform owns and no generated route,
 * menu or tool exposes. The model composes the identity pack ({@code packs[]} entry for pack
 * {@code identity}); there is no longer any discovery by field NAME (the old REG-211 sniffing let an
 * app declare an ordinary, open {@code Credential} concept whose hashes any signed-in user could read).
 * The emitter writes {@code npdev.auth.login.credential-table} / {@code credential-user-id-column} /
 * {@code credential-password-column} from that concept's physical names.</p>
 */
public final class RuntimeAuthPropertiesEmitter {

    public static final String RELATIVE_PATH = "src/main/resources/application-npdev-auth.properties";

    /** The pack that owns the credential store, and the concept inside it. */
    static final String IDENTITY_PACK_ID = "identity";
    static final String CREDENTIAL_CONCEPT = "Credential";
    static final String CREDENTIAL_USER_FIELD = "userId";
    static final String CREDENTIAL_HASH_FIELD = "passwordHash";

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
                        "jwt auth needs the identity pack (packs[] entry 'identity'): the identity pack owns "
                                + "the password store (its system concept Credential). None found among the model's "
                                + model.getConcepts().size() + " concept(s). Add the identity pack to packs[], "
                                + "or switch auth.mode to apiKey.");
            }
            sb.append("npdev.auth.login.credential-table=").append(credential.table).append("\n");
            sb.append("npdev.auth.login.credential-user-id-column=").append(credential.userIdColumn).append("\n");
            sb.append("npdev.auth.login.credential-password-column=").append(credential.passwordColumn).append("\n");
        }
        return sb.toString();
    }

    /**
     * Finds the identity pack's {@code Credential} concept: a {@code system} concept attributed to pack
     * {@value #IDENTITY_PACK_ID} (never the app's local {@code as} alias) named {@value #CREDENTIAL_CONCEPT},
     * with the {@value #CREDENTIAL_USER_FIELD} and {@value #CREDENTIAL_HASH_FIELD} fields the pack declares.
     *
     * @return the resolved physical table and column names, or {@code null} if the model does not compose
     *         the identity pack
     */
    static CredentialMapping findCredentialConcept(CompiledModel model) {
        if (model == null) {
            return null;
        }
        for (CompiledConcept concept : model.getConcepts()) {
            if (!concept.isSystem() || concept.getOrigin() == null
                    || !IDENTITY_PACK_ID.equals(concept.getOrigin().packId())
                    || !CREDENTIAL_CONCEPT.equals(simpleName(concept.getName()))) {
                continue;
            }
            CompiledField userIdField = null;
            CompiledField hashField = null;
            for (CompiledField field : concept.getFields()) {
                if (CREDENTIAL_USER_FIELD.equals(field.getName())) {
                    userIdField = field;
                } else if (CREDENTIAL_HASH_FIELD.equals(field.getName())) {
                    hashField = field;
                }
            }
            if (userIdField != null && hashField != null) {
                return new CredentialMapping(
                        SqlIdentifierSupport.tableName(concept),
                        SqlIdentifierSupport.columnName(userIdField),
                        SqlIdentifierSupport.columnName(hashField));
            }
        }
        return null;
    }

    private static String simpleName(String name) {
        int i = name == null ? -1 : name.lastIndexOf("::");
        return i < 0 ? name : name.substring(i + 2);
    }

    /** Resolved physical names for the credential table and its two required columns. */
    record CredentialMapping(String table, String userIdColumn, String passwordColumn) {
    }
}
