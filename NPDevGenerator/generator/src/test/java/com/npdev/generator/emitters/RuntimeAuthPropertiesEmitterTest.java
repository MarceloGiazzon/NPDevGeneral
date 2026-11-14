package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledOrigin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    // ---- FORM P0 (REG-339): the identity pack owns the credential store ----

    @Test
    void jwtWithoutIdentityPackThrows() {
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(
                "identity::User", userConcept()
        ));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                RuntimeAuthPropertiesEmitter.properties("jwt", model));
        assertTrue(ex.getMessage().contains("jwt auth needs the identity pack"), ex.getMessage());
    }

    @Test
    void jwtWithIdentityCredentialEmitsOverrides() {
        String properties = RuntimeAuthPropertiesEmitter.properties("jwt", modelWith(identityCredential("identity::Credential", true)));

        assertTrue(properties.contains("npdev.auth.login.credential-table=identity_credentials"), properties);
        assertTrue(properties.contains("npdev.auth.login.credential-user-id-column=user_id"), properties);
        assertTrue(properties.contains("npdev.auth.login.credential-password-column=password_hash"), properties);
    }

    @Test
    void jwtWorksWhateverAliasTheAppGivesTheIdentityPack() {
        // the concept key carries the app's local alias; the pack's real id is on the origin
        assertNotNull(RuntimeAuthPropertiesEmitter.findCredentialConcept(
                modelWith(identityCredential("auth::Credential", true))));
    }

    /** The REG-339 bug: an app-declared concept with a password-hash field is never the login store. */
    @Test
    void anAppConceptWithAPasswordHashFieldIsNeverTheCredentialStore() {
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
            assertNull(RuntimeAuthPropertiesEmitter.findCredentialConcept(modelWith(concept)),
                    "a plain app concept must not be picked by field name: " + hashFieldName);
        }
    }

    @Test
    void aCredentialLookalikeInThePackThatIsNotSystemIsIgnored() {
        assertNull(RuntimeAuthPropertiesEmitter.findCredentialConcept(
                modelWith(identityCredential("identity::Credential", false))));
    }

    /** REG-211 regression kept: the reset-token table can never be the login credential table. */
    @Test
    void theResetTokenTableIsNeverTheLoginCredentialTable() {
        CompiledConcept resetToken = new CompiledConcept(
                "identity::PasswordResetToken", "identity::PasswordResetToken", "",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("userId", "reference", "java.util.UUID", false, true, false,
                                List.of(), "identity::User"),
                        new CompiledField("tokenHash", "string", "String", false, true, false)
                ),
                List.of(), List.of(), null, null, null, null, List.of(), null, null, null,
                new CompiledOrigin("identity", "1.5.0", "", false), false, false, null, List.of(), List.of(), null, true
        );
        assertNull(RuntimeAuthPropertiesEmitter.findCredentialConcept(modelWith(resetToken)));
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

    @Test
    void findCredentialConceptReturnsNullForNullOrEmptyModel() {
        assertNull(RuntimeAuthPropertiesEmitter.findCredentialConcept(null));
        assertNull(RuntimeAuthPropertiesEmitter.findCredentialConcept(
                new CompiledModel("test", "1.0.0", "1.0.0", Map.of())));
    }

    // ---- helpers ----

    private static CompiledConcept identityCredential(String name, boolean system) {
        return new CompiledConcept(
                name, name, "",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("userId", "reference", "java.util.UUID", false, true, false,
                                List.of(), "identity::User"),
                        new CompiledField("passwordHash", "string", "String", false, true, false)
                ),
                List.of(), List.of(), null, null, null, null, List.of(), null, null, null,
                new CompiledOrigin("identity", "1.5.0", "", false), false, false, null, List.of(), List.of(), null, system
        );
    }

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
