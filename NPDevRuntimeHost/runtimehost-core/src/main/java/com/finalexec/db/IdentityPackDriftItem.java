package com.finalexec.db;

import com.finalexec.db.schemastate.SafetyClass;
import com.finalexec.db.schemastate.SchemaDiffItem;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;

/**
 * REG-39 layer 3: makes a stale built-in-pack copy visible in the pre-deploy Impact Report, not only
 * at boot. Mirrors {@code StartupValidator#validateIdentityPackFreshness} exactly (same concept and
 * field names -- {@code identity::User} / {@code tokenVersion}) but returns a synthetic
 * {@link SchemaDiffItem} instead of failing fast: this runs from the read-only {@code -ImpactOnly} CLI
 * and ControlPanel surfaces, which must never throw.
 *
 * <p>Classified {@link SafetyClass#NEEDS_HOOK} (an operator must act -- regenerate the app -- there is
 * no automatic backfill), which {@link ImpactReport}'s existing verdict mapping already turns into
 * {@code NEEDS_ATTENTION} with zero changes to that mapping.</p>
 */
final class IdentityPackDriftItem {

    private static final String IDENTITY_USER_CONCEPT = "identity::User";
    private static final String TOKEN_VERSION_FIELD = "tokenVersion";
    private static final String IDENTITY_USERS_TABLE = "identity_users";

    private IdentityPackDriftItem() {
    }

    /** {@code null} when {@code compiledModel} is unavailable, this app doesn't use the identity pack
     * at all, or its copy is current. */
    static SchemaDiffItem detectOrNull(CompiledModel compiledModel) {
        if (compiledModel == null) {
            return null;
        }
        CompiledConcept identityUser = null;
        for (CompiledConcept concept : compiledModel.getConcepts()) {
            if (IDENTITY_USER_CONCEPT.equalsIgnoreCase(concept.getName())) {
                identityUser = concept;
                break;
            }
        }
        if (identityUser == null) {
            return null;
        }
        boolean hasTokenVersion = identityUser.getFields().stream()
                .anyMatch(field -> TOKEN_VERSION_FIELD.equalsIgnoreCase(field.getName()));
        if (hasTokenVersion) {
            return null;
        }
        return SchemaDiffItem.of(
                "STALE_IDENTITY_PACK:" + IDENTITY_USERS_TABLE + ":" + TOKEN_VERSION_FIELD,
                IDENTITY_USERS_TABLE, TOKEN_VERSION_FIELD,
                SafetyClass.NEEDS_HOOK, "missing", "present");
    }
}
