package com.finalexec.auth.mfa;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;

/**
 * SEC-10: resolves the composed physical table of {@code identity::UserMfa}. Null means "MFA is not
 * available in this app" (identity pack older than 1.3.0, e.g. a private pack copy) -- every MFA code
 * path treats null as that, never as an error, so such apps keep signing in exactly as before.
 */
public final class MfaTables {

    public static final String MFA_CONCEPT = "identity::UserMfa";

    private MfaTables() {
    }

    public static String userMfaTable(CompiledModel model) {
        if (model == null) {
            return null;
        }
        return model.findConcept(MFA_CONCEPT)
                .map(CompiledConcept::getTableName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(null);
    }
}
