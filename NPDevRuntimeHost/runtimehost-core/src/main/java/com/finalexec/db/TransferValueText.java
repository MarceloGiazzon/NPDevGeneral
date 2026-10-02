package com.finalexec.db;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The one text form {@code db export} writes a column value in, shared by the CSV and SQL-insert
 * serializers. java.time values go through the ISO formatters rather than {@code toString()}:
 * {@code OffsetDateTime.toString()} drops zero seconds ("2026-10-01T07:00-03:00"), which H2
 * refuses to parse back as a TIMESTAMP WITH TIME ZONE literal -- found live on Pigmentampas
 * (2026-10-01, GPU-1 G4), where no export holding a datetime could be imported again.
 */
final class TransferValueText {

    private TransferValueText() {
    }

    static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime offset) {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(offset);
        }
        if (value instanceof ZonedDateTime zoned) {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zoned);
        }
        if (value instanceof LocalDateTime local) {
            return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(local);
        }
        return String.valueOf(value);
    }
}
