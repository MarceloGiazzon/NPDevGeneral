package com.npdev.generator.migration;

import org.junit.jupiter.api.Test;

class MigrationDiffEngineTest {
    @Test
    void oldMigrationAuthorityRemainsQuarantined() {
        MigrationAuthorityQuarantineAssertions.assertOldMigrationAuthorityAbsent();
    }
}
