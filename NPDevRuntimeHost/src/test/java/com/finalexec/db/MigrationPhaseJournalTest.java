package com.finalexec.db;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 (REAL_LIFT_PLAN_2026-09-03, B11 "real lift"): direct unit tests for {@link MigrationPhaseJournal}
 * against real H2 -- isolated from {@link ConversionHookRunner}, so a failure here points straight at
 * the journal's own read/write contract.
 */
class MigrationPhaseJournalTest {

    @Test
    void aPhaseNeverRecordedIsNotCompleted() throws SQLException {
        try (Connection connection = freshConnection()) {
            MigrationPhaseJournal.PhaseKey key = new MigrationPhaseJournal.PhaseKey("mig-1", "hook-a", 0);
            assertFalse(MigrationPhaseJournal.isCompleted(connection, key, "hash-1"));
        }
    }

    @Test
    void startedButNotCompletedIsNotCompleted() throws SQLException {
        try (Connection connection = freshConnection()) {
            MigrationPhaseJournal.PhaseKey key = new MigrationPhaseJournal.PhaseKey("mig-1", "hook-a", 0);
            MigrationPhaseJournal.recordStarted(connection, key, "DDL", "hash-1");
            assertFalse(MigrationPhaseJournal.isCompleted(connection, key, "hash-1"),
                    "STARTED with no COMPLETED must read as not-completed -- exactly the crash window this "
                            + "table exists to make resumable");
        }
    }

    @Test
    void startedThenCompletedIsCompletedWithTheSameHash() throws SQLException {
        try (Connection connection = freshConnection()) {
            MigrationPhaseJournal.PhaseKey key = new MigrationPhaseJournal.PhaseKey("mig-1", "hook-a", 0);
            MigrationPhaseJournal.recordStarted(connection, key, "DDL", "hash-1");
            MigrationPhaseJournal.recordCompleted(connection, key);
            assertTrue(MigrationPhaseJournal.isCompleted(connection, key, "hash-1"));
        }
    }

    @Test
    void aDifferentStatementHashIsNotCompletedEvenIfThePhaseKeyMatches() throws SQLException {
        // The author edited convert.sql between boots -- the OLD statement's completion must not be
        // read as covering the NEW statement's effect.
        try (Connection connection = freshConnection()) {
            MigrationPhaseJournal.PhaseKey key = new MigrationPhaseJournal.PhaseKey("mig-1", "hook-a", 0);
            MigrationPhaseJournal.recordStarted(connection, key, "DDL", "hash-old");
            MigrationPhaseJournal.recordCompleted(connection, key);
            assertFalse(MigrationPhaseJournal.isCompleted(connection, key, "hash-new"));
        }
    }

    @Test
    void differentMigrationIdsForDifferentFromToPairs() {
        String a = MigrationPhaseJournal.migrationId("from-1", "to-1");
        String b = MigrationPhaseJournal.migrationId("from-1", "to-2");
        String c = MigrationPhaseJournal.migrationId("from-1", "to-1");
        assertFalse(a.equals(b));
        assertEquals(a, c, "the same from/to pair must be stable across calls (a resumed boot recomputes it)");
    }

    @Test
    void retryingAStartedPhaseOverwritesItsOwnPriorRowRatherThanAccumulating() throws SQLException {
        try (Connection connection = freshConnection()) {
            MigrationPhaseJournal.PhaseKey key = new MigrationPhaseJournal.PhaseKey("mig-1", "hook-a", 0);
            MigrationPhaseJournal.recordStarted(connection, key, "DML", "hash-1");
            MigrationPhaseJournal.recordStarted(connection, key, "DML", "hash-1"); // simulates a retried phase
            MigrationPhaseJournal.recordCompleted(connection, key);
            assertTrue(MigrationPhaseJournal.isCompleted(connection, key, "hash-1"));
        }
    }

    private static Connection freshConnection() throws SQLException {
        String url = "jdbc:h2:mem:" + MigrationPhaseJournalTest.class.getSimpleName() + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1";
        return DriverManager.getConnection(url);
    }
}
