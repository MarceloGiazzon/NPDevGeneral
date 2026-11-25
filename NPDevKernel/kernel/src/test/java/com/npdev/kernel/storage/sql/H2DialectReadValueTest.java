package com.npdev.kernel.storage.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * REG-360 (e): H2's {@code TIMESTAMP WITH TIME ZONE} keeps the offset a row was written with, so the same
 * instant came back as {@code ...Z} for one row and {@code ...-03:00} for another in ONE list. The fix is
 * {@link H2Dialect#readValue}: spell every datetime in UTC. The instant must never change.
 */
@DisplayName("H2Dialect.readValue -- a datetime is always read back in UTC")
class H2DialectReadValueTest {

    private static final OffsetDateTime LOCAL = OffsetDateTime.parse("2026-11-25T12:33:11.080680-03:00");

    @Test
    @DisplayName("a non-UTC offset is respelled in UTC and the instant is unchanged")
    void anOffsetIsRespelledInUtc() {
        Object read = H2Dialect.INSTANCE.readValue(LOCAL);

        assertEquals(OffsetDateTime.parse("2026-11-25T15:33:11.080680Z"), read);
        assertEquals(ZoneOffset.UTC, ((OffsetDateTime) read).getOffset());
        assertEquals(LOCAL.toInstant(), ((OffsetDateTime) read).toInstant(), "only the spelling may change");
    }

    @Test
    @DisplayName("anything that is not a datetime is returned as it came")
    void otherValuesPassThrough() {
        assertNull(H2Dialect.INSTANCE.readValue(null));
        assertEquals("tok_ok", H2Dialect.INSTANCE.readValue("tok_ok"));
        Integer number = 7;
        assertSame(number, H2Dialect.INSTANCE.readValue(number));
    }

    @Test
    @DisplayName("two rows written with different offsets read back with the SAME spelling from a real H2")
    void twoRowsWrittenWithDifferentOffsetsReadBackAlike() throws Exception {
        OffsetDateTime utc = OffsetDateTime.parse("2026-11-25T15:33:11.710727Z");
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:reg360_readvalue;DB_CLOSE_DELAY=-1");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE rides (id INT PRIMARY KEY, created_at TIMESTAMP WITH TIME ZONE)");
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO rides VALUES (?, ?)")) {
                insert.setInt(1, 1);
                insert.setObject(2, LOCAL);
                insert.executeUpdate();
                insert.setInt(1, 2);
                insert.setObject(2, utc);
                insert.executeUpdate();
            }
            try (ResultSet rows = statement.executeQuery("SELECT created_at FROM rides ORDER BY id")) {
                rows.next();
                Object rawLocal = rows.getObject(1);
                rows.next();
                Object rawUtc = rows.getObject(1);

                // The premise: the driver keeps the written offset, so the raw values are spelled differently.
                assertEquals(LOCAL.getOffset(), ((OffsetDateTime) rawLocal).getOffset());
                assertEquals(ZoneOffset.UTC, ((OffsetDateTime) rawUtc).getOffset());

                // The fix: after readValue both are UTC, and each is still the instant that was written.
                OffsetDateTime readLocal = (OffsetDateTime) H2Dialect.INSTANCE.readValue(rawLocal);
                OffsetDateTime readUtc = (OffsetDateTime) H2Dialect.INSTANCE.readValue(rawUtc);
                assertEquals(ZoneOffset.UTC, readLocal.getOffset());
                assertEquals(ZoneOffset.UTC, readUtc.getOffset());
                assertEquals(LOCAL.toInstant(), readLocal.toInstant());
                assertEquals(utc.toInstant(), readUtc.toInstant());
            }
        }
    }
}
