package com.npdev.generator.migration;

import org.junit.jupiter.api.Test;

class IncrementalMigrationHarnessTest {
    @Test
    void oldMigrationAuthorityRemainsQuarantined() {
        MigrationAuthorityQuarantineAssertions.assertOldMigrationAuthorityAbsent();
    }
}
