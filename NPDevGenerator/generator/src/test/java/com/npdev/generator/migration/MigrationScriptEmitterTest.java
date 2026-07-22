package com.npdev.generator.migration;

import org.junit.jupiter.api.Test;

class MigrationScriptEmitterTest {
    @Test
    void oldMigrationAuthorityRemainsQuarantined() {
        MigrationAuthorityQuarantineAssertions.assertOldMigrationAuthorityAbsent();
    }
}
