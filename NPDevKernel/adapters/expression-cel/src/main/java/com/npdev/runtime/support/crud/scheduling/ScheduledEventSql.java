package com.npdev.runtime.support.crud.scheduling;

import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.SCHEDULE_TABLE;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): the SQL text for the
 * {@code npdev_scheduled_event} table's select/claim/mark/insert operations.
 */
public final class ScheduledEventSql {
    private ScheduledEventSql() {
    }

    /** Uses the process's configured engine; the overload exists for the conformance suite. */
    public static String selectDue(boolean forceDue) {
        return selectDue(forceDue, SqlDialects.active());
    }

    public static String selectDue(boolean forceDue, SqlDialect dialect) {
        return dialect.limited("SELECT id, schedule_key, orchestration_name, action_index, source_event_name, source_event_id, "
                + "trigger_correlation_id, event_name, due_at, status, attempt_count, created_at, updated_at, "
                + "processed_at, payload "
                + "FROM " + SCHEDULE_TABLE + " "
                + "WHERE status = ? "
                + (forceDue ? "" : "AND due_at <= ? ")
                + "ORDER BY due_at ASC, created_at ASC ").stripTrailing();
    }

    public static String claim() {
        return "UPDATE " + SCHEDULE_TABLE + " "
                + "SET status = ?, updated_at = ? "
                + "WHERE id = ? AND status = ?";
    }

    public static String markProcessed() {
        return "UPDATE " + SCHEDULE_TABLE + " "
                + "SET status = ?, attempt_count = attempt_count + 1, "
                + "processed_at = ?, updated_at = ? "
                + "WHERE id = ?";
    }

    public static String markFailed() {
        return "UPDATE " + SCHEDULE_TABLE + " "
                + "SET status = ?, attempt_count = attempt_count + 1, updated_at = ? "
                + "WHERE id = ?";
    }

    public static String insert() {
        return "INSERT INTO " + SCHEDULE_TABLE + " ("
                + "id, schedule_key, orchestration_name, action_index, source_event_name, source_event_id, "
                + "trigger_correlation_id, event_name, due_at, payload, status, attempt_count, created_at, updated_at"
                + ") VALUES ("
                + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?"
                + ")";
    }
}
