package com.npdev.generator.migration;

import org.junit.jupiter.api.Test;

class StatefulMigrationPlannerTest {
    @Test
    void oldMigrationAuthorityRemainsQuarantined() {
        MigrationAuthorityQuarantineAssertions.assertOldMigrationAuthorityAbsent();
    }
}
