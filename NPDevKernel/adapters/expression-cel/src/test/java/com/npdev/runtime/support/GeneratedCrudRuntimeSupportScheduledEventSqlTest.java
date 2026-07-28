package com.npdev.runtime.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npdev.runtime.support.crud.scheduling.ScheduledEventSql;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

final class GeneratedCrudRuntimeSupportScheduledEventSqlTest {
    @Test
    void scheduledEventSqlIsNotPostgresOnly() {
        List<String> statements = List.of(
                ScheduledEventSql.selectDue(false),
                ScheduledEventSql.selectDue(true),
                ScheduledEventSql.claim(),
                ScheduledEventSql.markProcessed(),
                ScheduledEventSql.markFailed(),
                ScheduledEventSql.insert()
        );

        String combined = String.join("\n", statements);
        String normalized = combined.toUpperCase(Locale.ROOT);

        assertTrue(normalized.contains("NPDEV_SCHEDULED_EVENT"),
                "Scheduled-event SQL must target npdev_scheduled_event");
        assertFalse(normalized.contains("JSONB"),
                "Scheduled-event SQL must not require Postgres JSONB");
        assertFalse(combined.contains("::jsonb"),
                "Scheduled-event SQL must not require Postgres ::jsonb casts");
        assertFalse(normalized.contains("NOW()"),
                "Scheduled-event SQL must bind timestamps instead of using NOW()");
        assertFalse(normalized.contains("TIMESTAMP WITH TIME ZONE"),
                "Scheduled-event SQL must not require Postgres timestamp syntax");
        assertFalse(normalized.contains("CAST(? AS JSONB)"),
                "Scheduled-event SQL must bind payload as a neutral parameter");
    }
}
