package com.npdev.generator.migration;

import org.junit.jupiter.api.Test;

class StatefulFlywayPostgresMigrationProofTest {
    @Test
    void oldMigrationAuthorityRemainsQuarantined() {
        MigrationAuthorityQuarantineAssertions.assertOldMigrationAuthorityAbsent();
    }
}
