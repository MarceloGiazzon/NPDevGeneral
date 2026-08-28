package com.finalexec.controlpanel;

import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the Cold Clone Audit C5 fix: the SuperUserBootstrapper skip message's supported-engine list
 * must be derived from the dialect registry, not a hard-coded literal. The literal
 * "H2Local/H2Server/Postgres" was stale since 2026-08-09, when MySQL and SQL Server reached the
 * same support bar (ledger STOR-3) -- a user on a supported engine read "not supported" and went
 * looking for a workaround that was not needed.
 */
class SuperUserBootstrapperPhysicalEngineNamesTest {

    @Test
    void derivedListNamesEveryRegisteredPhysicalEngine() {
        String derived = SuperUserBootstrapper.physicalEngineNames();

        assertEquals(SqlDialects.all().size(),
                derived.split(", ").length,
                "one entry per registered dialect -- a dialect added to SqlDialects must appear here");
        for (SqlDialect dialect : SqlDialects.all()) {
            assertTrue(
                    derived.contains(displayNameFor(dialect)),
                    "derived engine list must name dialect '" + dialect.name() + "' the way a user "
                            + "reads it (" + displayNameFor(dialect) + "), got: " + derived);
        }
    }

    private static String displayNameFor(SqlDialect dialect) {
        return switch (dialect.name()) {
            case "h2" -> "H2Local";
            case "postgres" -> "Postgres";
            case "mysql" -> "MySQL";
            case "sqlserver" -> "SqlServer";
            default -> dialect.name();
        };
    }

    @Test
    void derivedListIncludesH2LocalAndH2ServerSpellingsUsersKnow() {
        String derived = SuperUserBootstrapper.physicalEngineNames();
        assertTrue(derived.contains("H2Local"));
        assertTrue(derived.contains("H2Server"));
    }

    @Test
    void derivedListIsSortedForAStableMessage() {
        String derived = SuperUserBootstrapper.physicalEngineNames();
        List<String> names = Arrays.asList(derived.split(", "));
        List<String> sorted = names.stream().sorted().toList();
        assertEquals(sorted, names, "message should not reorder between runs");
    }
}