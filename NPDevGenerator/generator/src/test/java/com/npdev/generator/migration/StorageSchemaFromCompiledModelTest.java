package com.npdev.generator.migration;

import org.junit.jupiter.api.Test;

class StorageSchemaFromCompiledModelTest {
    @Test
    void oldMigrationAuthorityRemainsQuarantined() {
        MigrationAuthorityQuarantineAssertions.assertOldMigrationAuthorityAbsent();
    }
}
