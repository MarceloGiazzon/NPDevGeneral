package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeAuthPropertiesEmitterTest {

    @Test
    void noneDisablesAuth() {
        String properties = RuntimeAuthPropertiesEmitter.properties("none");
        assertTrue(properties.contains("npdev.auth.enabled=false"), properties);
        assertFalse(properties.contains("npdev.auth.mode="), properties);
    }

    @Test
    void apiKeyEnablesApikeyMode() {
        String properties = RuntimeAuthPropertiesEmitter.properties("apiKey");
        assertTrue(properties.contains("npdev.auth.enabled=true"), properties);
        assertTrue(properties.contains("npdev.auth.mode=apikey"), properties);
    }

    @Test
    void jwtEnablesJwtMode() {
        String properties = RuntimeAuthPropertiesEmitter.properties("jwt");
        assertTrue(properties.contains("npdev.auth.enabled=true"), properties);
        assertTrue(properties.contains("npdev.auth.mode=jwt"), properties);
    }

    @Test
    void unknownModeDefaultsToApikey() {
        String properties = RuntimeAuthPropertiesEmitter.properties("weird");
        assertTrue(properties.contains("npdev.auth.enabled=true"), properties);
        assertTrue(properties.contains("npdev.auth.mode=apikey"), properties);
    }

    // ---- REG-211: credential concept detection ----

    @Test
    void jwtWithoutCredentialConceptThrows() {
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(
                "identity::User", userConcept()
        ));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                RuntimeAuthPropertiesEmitter.properties("jwt", model));
        assertTrue(ex.getMessage().contains("credential-bearing concept"), ex.getMessage());
    }

    @Test
    void jwtWithUsuarioConceptEmitsCredentialOverrides() {
        CompiledConcept usuario = new CompiledConcept(
                "Usuario", "Usuario", "",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("userId", "reference", "java.util.UUID", false, true, false,
                                List.of(), "identity::User"),
                        new CompiledField("senhaHash", "string", "String", false, true, false)
                )
        );
        CompiledModel model = modelWith(usuario);
        String properties = RuntimeAuthPropertiesEmitter.properties("jwt", model);

        assertTrue(properties.contains("npdev.auth.login.credential-table=usuarios"), properties);
        assertTrue(properties.contains("npdev.auth.login.credential-user-id-column=user_id"), properties);
        assertTrue(properties.contains("npdev.auth.login.credential-password-column=senha_hash"), properties);
    }

    @Test
    void jwtWithCredentialConceptEmitsGenericOverrides() {
        CompiledConcept credential = new CompiledConcept(
                "Credential", "Credential", "",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("userId", "reference", "java.util.UUID", false, true, false,
                                List.of(), "identity::User"),
                        new CompiledField("passwordHash", "string", "String", false, true, false)
                )
        );
        CompiledModel model = modelWith(credential);
        String properties = RuntimeAuthPropertiesEmitter.properties("jwt", model);

        assertTrue(properties.contains("npdev.auth.login.credential-table=credentials"), properties);
        assertTrue(properties.contains("npdev.auth.login.credential-user-id-column=user_id"), properties);
        assertTrue(properties.contains("npdev.auth.login.credential-password-column=password_hash"), properties);
    }

    @Test
    void jwtWithConceptHavingUnqualifiedUserReferenceEmitsOverrides() {
        CompiledConcept usuario = new CompiledConcept(
                "Usuario", "Usuario", "",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("userId", "reference", "java.util.UUID", false, true, false,
                                List.of(), "User"),
                        new CompiledField("senha", "string", "String", false, true, false)
                )
        );
        CompiledModel model = modelWith(usuario);
        String properties = RuntimeAuthPropertiesEmitter.properties("jwt", model);

        assertTrue(properties.contains("npdev.auth.login.credential-table=usuarios"), properties);
        assertTrue(properties.contains("npdev.auth.login.credential-password-column=senha"), properties);
    }

    @Test
    void apiKeyModeSkipsCredentialCheck() {
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of());
        String properties = RuntimeAuthPropertiesEmitter.properties("apiKey", model);
        assertTrue(properties.contains("npdev.auth.mode=apikey"), properties);
        assertFalse(properties.contains("credential-table"), properties);
    }

    @Test
    void noneModeSkipsCredentialCheck() {
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of());
        String properties = RuntimeAuthPropertiesEmitter.properties("none", model);
        assertFalse(properties.contains("credential-table"), properties);
    }

    // ---- findCredentialConcept unit tests ----

    @Test
    void findCredentialConceptReturnsNullForNullModel() {
        assertNull(RuntimeAuthPropertiesEmitter.findCredentialConcept(null));
    }

    @Test
    void findCredentialConceptReturnsNullForEmptyModel() {
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of());
        assertNull(RuntimeAuthPropertiesEmitter.findCredentialConcept(model));
    }

    @Test
    void findCredentialConceptReturnsNullWhenNoUserReference() {
        CompiledConcept concept = new CompiledConcept(
                "Foo", "Foo", "",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("senhaHash", "string", "String", false, true, false)
                )
        );
        CompiledModel model = modelWith(concept);
        assertNull(RuntimeAuthPropertiesEmitter.findCredentialConcept(model));
    }

    @Test
    void findCredentialConceptReturnsNullWhenNoHashField() {
        CompiledConcept concept = new CompiledConcept(
                "Foo", "Foo", "",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("userId", "reference", "java.util.UUID", false, true, false,
                                List.of(), "identity::User")
                )
        );
        CompiledModel model = modelWith(concept);
        assertNull(RuntimeAuthPropertiesEmitter.findCredentialConcept(model));
    }

    @Test
    void findCredentialConceptFindsFirstMatchingConcept() {
        CompiledConcept usuario = new CompiledConcept(
                "Usuario", "Usuario", "",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("userId", "reference", "java.util.UUID", false, true, false,
                                List.of(), "identity::User"),
                        new CompiledField("senhaHash", "string", "String", false, true, false)
                )
        );
        CompiledModel model = modelWith(usuario);
        RuntimeAuthPropertiesEmitter.CredentialMapping result =
                RuntimeAuthPropertiesEmitter.findCredentialConcept(model);
        assertNotNull(result);
        assertEquals("usuarios", result.table());
        assertEquals("user_id", result.userIdColumn());
        assertEquals("senha_hash", result.passwordColumn());
    }

    @Test
    void findCredentialConceptDetectsHashFieldBySuffix() {
        for (String hashFieldName : List.of("senhaHash", "passwordHash", "senha", "password")) {
            CompiledConcept concept = new CompiledConcept(
                    "Credential", "Credential", "",
                    List.of(
                            new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                            new CompiledField("userId", "reference", "java.util.UUID", false, true, false,
                                    List.of(), "identity::User"),
                            new CompiledField(hashFieldName, "string", "String", false, true, false)
                    )
            );
            CompiledModel model = modelWith(concept);
            assertNotNull(RuntimeAuthPropertiesEmitter.findCredentialConcept(model),
                    "should match hash field: " + hashFieldName);
        }
    }

    /**
     * REG-211 regression (live WmsOffice): the identity pack's {@code PasswordResetToken} concept
     * (userId reference + {@code tokenHash} string) is NOT a login credential, but its field ended
     * in the old bare "hash" suffix and won the detector's first-match race over the app's own
     * {@code Usuario} concept -- shipping {@code credential-table=identity_v1_password_reset_tokens}
     * and breaking bootstrap-admin (500s) and login (401s) on jwt-mode apps composing the identity
     * pack. A reset-token hash, like an api-key hash, must not be mistaken for a password hash.
     */
    @Test
    void findCredentialConceptIgnoresResetTokenHashField() {
        CompiledConcept resetToken = new CompiledConcept(
                "identity::PasswordResetToken", "identity::PasswordResetToken", "",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("userId", "reference", "java.util.UUID", false, true, false,
                                List.of(), "identity::User"),
                        new CompiledField("tokenHash", "string", "String", false, true, false)
                )
        );
        assertNull(RuntimeAuthPropertiesEmitter.findCredentialConcept(modelWith(resetToken)),
                "a password-reset-token table can never be the login credential table");
    }

    @Test
    void jwtWithIdentityPackAndUsuarioPrefersUsuario() {
        CompiledConcept resetToken = new CompiledConcept(
                "identity::PasswordResetToken", "identity::PasswordResetToken", "",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("userId", "reference", "java.util.UUID", false, true, false,
                                List.of(), "identity::User"),
                        new CompiledField("tokenHash", "string", "String", false, true, false)
                )
        );
        CompiledConcept usuario = new CompiledConcept(
                "Usuario", "Usuario", "",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("userId", "reference", "java.util.UUID", false, true, false,
                                List.of(), "identity::User"),
                        new CompiledField("senhaHash", "string", "String", false, true, false)
                )
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(
                "identity::PasswordResetToken", resetToken,
                "Usuario", usuario
        ));
        String properties = RuntimeAuthPropertiesEmitter.properties("jwt", model);
        assertTrue(properties.contains("npdev.auth.login.credential-table=usuarios"), properties);
        assertTrue(properties.contains("npdev.auth.login.credential-password-column=senha_hash"), properties);
        assertFalse(properties.contains("password_reset_tokens"), properties);
    }

    @Test
    void findCredentialConceptDetectsUserReferenceTargetVariants() {
        for (String target : List.of("User", "identity::User")) {
            CompiledConcept concept = new CompiledConcept(
                    "Usuario", "Usuario", "",
                    List.of(
                            new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                            new CompiledField("userId", "reference", "java.util.UUID", false, true, false,
                                    List.of(), target),
                            new CompiledField("senhaHash", "string", "String", false, true, false)
                    )
            );
            CompiledModel model = modelWith(concept);
            assertNotNull(RuntimeAuthPropertiesEmitter.findCredentialConcept(model),
                    "should match reference target: " + target);
        }
    }

    // ---- helpers ----

    private static CompiledConcept userConcept() {
        return new CompiledConcept(
                "identity::User", "identity::User", "",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("username", "string", "String", false, true, false)
                )
        );
    }

    private static CompiledModel modelWith(CompiledConcept concept) {
        return new CompiledModel("test", "1.0.0", "1.0.0", Map.of(concept.getName(), concept));
    }
}
