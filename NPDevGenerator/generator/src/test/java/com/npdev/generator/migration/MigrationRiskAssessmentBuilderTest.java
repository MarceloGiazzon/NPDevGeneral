package com.npdev.generator.migration;

import org.junit.jupiter.api.Test;

class MigrationRiskAssessmentBuilderTest {
    @Test
    void oldMigrationAuthorityRemainsQuarantined() {
        MigrationAuthorityQuarantineAssertions.assertOldMigrationAuthorityAbsent();
    }
}
