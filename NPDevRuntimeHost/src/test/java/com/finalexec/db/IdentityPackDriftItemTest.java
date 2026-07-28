package com.finalexec.db;

import com.finalexec.db.schemastate.SafetyClass;
import com.finalexec.db.schemastate.SchemaDiffItem;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** REG-39 layer 3: {@link IdentityPackDriftItem}'s pure detection logic, mirroring {@code
 *  StartupValidatorTest}'s fixtures for the identical {@code identity::User}/{@code tokenVersion} check. */
class IdentityPackDriftItemTest {

    @Test
    void nullModelIsSkipped() {
        assertNull(IdentityPackDriftItem.detectOrNull(null));
    }

    @Test
    void modelWithNoIdentityPackIsSkipped() {
        assertNull(IdentityPackDriftItem.detectOrNull(new CompiledModel("test", "1.0.0", "1.0", Map.of())));
    }

    @Test
    void freshIdentityPackCopyIsSkipped() {
        assertNull(IdentityPackDriftItem.detectOrNull(modelWithIdentityUser(true)));
    }

    @Test
    void staleIdentityPackCopyProducesANeedsHookItemNamingIdentityUsersTokenVersion() {
        SchemaDiffItem item = IdentityPackDriftItem.detectOrNull(modelWithIdentityUser(false));

        assertEquals("identity_users", item.table());
        assertEquals("tokenVersion", item.column());
        assertEquals(SafetyClass.NEEDS_HOOK, item.safetyClass());
    }

    private static CompiledModel modelWithIdentityUser(boolean includeTokenVersion) {
        List<CompiledField> fields = includeTokenVersion
                ? List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("username", "string", "java.lang.String", false, true, true),
                        new CompiledField("tokenVersion", "integer", "int", false, false, false))
                : List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("username", "string", "java.lang.String", false, true, true));
        CompiledConcept identityUser = new CompiledConcept("identity::User", "IdentityUser", "identity_users", fields);
        return new CompiledModel("test", "1.0.0", "1.0", Map.of("identity::User", identityUser));
    }
}
