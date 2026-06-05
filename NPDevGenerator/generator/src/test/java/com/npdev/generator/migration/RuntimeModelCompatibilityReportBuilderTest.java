package com.npdev.generator.migration;

import org.junit.jupiter.api.Test;

class RuntimeModelCompatibilityReportBuilderTest {
    @Test
    void oldMigrationAuthorityRemainsQuarantined() {
        MigrationAuthorityQuarantineAssertions.assertOldMigrationAuthorityAbsent();
    }
}
