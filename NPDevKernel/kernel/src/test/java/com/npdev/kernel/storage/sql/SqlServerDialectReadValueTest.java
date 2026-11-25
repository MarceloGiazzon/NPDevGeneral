package com.npdev.kernel.storage.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * REG-360 (e), the SQL Server half: a {@code DATETIMEOFFSET} column keeps the offset a row was written with
 * (measured here, with the real mssql-jdbc class and no container), so a list could spell the same kind of
 * instant as {@code ...Z} for one row and {@code ...-03:00} for another -- the defect H2Dialect had.
 * {@link SqlServerDialect#readValue} now respells it in UTC; the instant never changes.
 */
@DisplayName("SqlServerDialect.readValue -- a datetime is always read back in UTC")
class SqlServerDialectReadValueTest {

    private static final Timestamp INSTANT = Timestamp.from(OffsetDateTime.parse("2026-11-25T15:33:11.080680Z").toInstant());

    private static Object driverValue(int minutesOffset) {
        // The real driver class, built through its own public factory: no connection, no container.
        return microsoft.sql.DateTimeOffset.valueOf(INSTANT, minutesOffset);
    }

    @Test
    @DisplayName("the driver keeps the offset it was given (the premise of the defect)")
    void theDriverKeepsTheWrittenOffset() throws Exception {
        OffsetDateTime raw = ((microsoft.sql.DateTimeOffset) driverValue(-180)).getOffsetDateTime();

        assertEquals(ZoneOffset.ofHours(-3), raw.getOffset(), "DATETIMEOFFSET hands back the offset it stores");
        assertEquals(INSTANT.toInstant(), raw.toInstant());
    }

    @Test
    @DisplayName("a DATETIMEOFFSET written with any offset reads back in UTC, same instant")
    void aDateTimeOffsetReadsBackInUtc() {
        for (int minutes : new int[] {-180, 0, 330}) {
            Object read = SqlServerDialect.INSTANCE.readValue(driverValue(minutes));

            OffsetDateTime value = (OffsetDateTime) read;
            assertEquals(ZoneOffset.UTC, value.getOffset(), "offset " + minutes + " min");
            assertEquals(INSTANT.toInstant(), value.toInstant(), "only the spelling may change, offset " + minutes + " min");
        }
    }

    @Test
    @DisplayName("the other shapes keep their behaviour")
    void otherShapesAreUnchanged() {
        assertNull(SqlServerDialect.INSTANCE.readValue(null));
        assertEquals("tok_ok", SqlServerDialect.INSTANCE.readValue("tok_ok"));
        assertEquals(LocalDateTime.parse("2026-11-25T15:33:11").atOffset(ZoneOffset.UTC),
                SqlServerDialect.INSTANCE.readValue(LocalDateTime.parse("2026-11-25T15:33:11")),
                "a zoneless DATETIME2 is still restored as UTC");
    }
}
