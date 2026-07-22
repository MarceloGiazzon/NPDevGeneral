package com.npdev.generator.migration;

import org.junit.jupiter.api.Test;

class ModelDiffPreviewBuilderTest {
    @Test
    void oldMigrationAuthorityRemainsQuarantined() {
        MigrationAuthorityQuarantineAssertions.assertOldMigrationAuthorityAbsent();
    }
}
