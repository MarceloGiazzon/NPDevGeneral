package com.npdev.generator.migration;

import org.junit.jupiter.api.Test;

// LNCH-1: the sanctioned successor to this quarantined package is
// com.npdev.generator.schemaevolution (fingerprint/classification-based schema evolution,
// see docs/LNCH1_SCHEMA_EVOLUTION_PLAN.md §2.2). The quarantine below still stands — the old
// model-diff + versioned-SQL migration authority (V5001-V5014) must not return in any form.
class StatefulMigrationPlannerTest {
    @Test
    void oldMigrationAuthorityRemainsQuarantined() {
        MigrationAuthorityQuarantineAssertions.assertOldMigrationAuthorityAbsent();
    }
}
