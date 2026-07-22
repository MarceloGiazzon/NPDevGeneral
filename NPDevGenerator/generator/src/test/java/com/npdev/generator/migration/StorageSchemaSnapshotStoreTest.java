package com.npdev.generator.migration;

import org.junit.jupiter.api.Test;

class StorageSchemaSnapshotStoreTest {
    @Test
    void oldMigrationAuthorityRemainsQuarantined() {
        MigrationAuthorityQuarantineAssertions.assertOldMigrationAuthorityAbsent();
    }
}
